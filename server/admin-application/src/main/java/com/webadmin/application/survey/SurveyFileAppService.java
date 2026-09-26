package com.webadmin.application.survey;

import com.webadmin.application.survey.dto.SurveyAttachmentDTO;
import com.webadmin.application.survey.port.FileStoragePort;
import com.webadmin.application.survey.port.SurveyFilePort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.survey.SurveyErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 附件应用服务：校验 → 落盘 → 记元数据。
 *
 * <h3>目录分层规则</h3>
 * {@code {tenantId}/{bizType}/{yyyy-MM}/{uuid}.{ext}}
 * <ul>
 *   <li>按租户分层：租户数据物理隔离，退租时可直接删目录</li>
 *   <li>按业务类型分层：便于按用途清理（如只清编辑器图片）</li>
 *   <li>按年月分层：单目录文件数可控 —— 所有文件堆在一个目录时，
 *       目录项过万会让文件系统与备份都变慢</li>
 *   <li>文件名用 UUID：原始名可能重复、含路径分隔符或非法字符</li>
 * </ul>
 *
 * <h3>⚠️ 大文件：本接口是一次性上传（整包读入内存）</h3>
 * 上限 {@value #MAX_SIZE_BYTES} 字节（20MB）。<b>不支持分片与断点续传</b> ——
 * 分片协议（Uppy + tus）是明确的自研红线，需要时应整体切换到那套方案，
 * 而不是在这里加一个"看起来能用"的半成品分片。因此：
 * <ul>
 *   <li>大文件（>20MB）应走对象存储直传（前端拿 STS 临时凭证直传 OSS/COS）</li>
 *   <li>本接口适合采集数据文件、报告附件、编辑器图片这类小文件</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyFileAppService {

    /** 单文件上限：20MB。 */
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /** 允许的扩展名（白名单，非黑名单：漏写黑名单会放行任意类型）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "csv", "xlsx", "xls", "txt", "pdf", "zip",
            "png", "jpg", "jpeg", "gif", "webp",
            "doc", "docx");

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SurveyFilePort filePort;
    private final FileStoragePort storagePort;

    /**
     * 上传并登记元数据。
     *
     * <p>元数据写失败时<b>补偿删除</b>已落盘的文件：否则磁盘上留下永远无人引用的孤儿文件，
     * 而且没有任何记录能说明它该不该删。
     */
    @Transactional
    public SurveyAttachmentDTO upload(String originalFileName, String contentType,
                                      byte[] content, String bizType, Long bizId) {
        if (content == null || content.length == 0) {
            throw new BizException(SurveyErrorCode.FILE_EMPTY, "上传文件为空");
        }
        if (content.length > MAX_SIZE_BYTES) {
            throw new BizException(SurveyErrorCode.FILE_TOO_LARGE,
                    "文件大小 " + (content.length / 1024 / 1024) + "MB 超过上限 20MB");
        }
        String extension = extensionOf(originalFileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BizException(SurveyErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "不支持的文件类型：." + extension + "（允许：" + String.join("/", ALLOWED_EXTENSIONS) + "）");
        }

        String relativePath = buildRelativePath(bizType, extension);
        String storedPath = storagePort.store(content, relativePath);
        try {
            Long id = filePort.insert(bizType, bizId, originalFileName, storedPath,
                    content.length, contentType);
            log.info("上传附件 id={} path={} size={} biz={}", id, storedPath, content.length, bizType);
            return filePort.findById(id).orElseThrow(() -> new BizException(
                    SurveyErrorCode.FILE_STORE_FAILED, "附件元数据写入后读取失败"));
        } catch (RuntimeException e) {
            storagePort.delete(storedPath);
            throw e;
        }
    }

    public SurveyAttachmentDTO detail(long id) {
        return filePort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.FILE_NOT_FOUND, "附件不存在"));
    }

    /** 读取文件内容（下载 / 富文本图片回显）。 */
    public FileContent read(long id) {
        SurveyAttachmentDTO meta = detail(id);
        return new FileContent(meta, storagePort.read(meta.filePath()));
    }

    /**
     * 组装相对存储路径。
     *
     * <p>租户号取 {@link TenantContext#require()}：文件必须落在租户自己的目录下，
     * 缺失租户上下文时直接失败（而不是落到公共目录）。
     */
    private String buildRelativePath(String bizType, String extension) {
        long tenantId = TenantContext.require();
        String month = LocalDate.now().format(MONTH_FORMAT);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        return tenantId + "/" + bizType + "/" + month + "/" + name;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    /** 文件内容与元数据（下载接口要同时用到两者：响应头与响应体）。 */
    public record FileContent(SurveyAttachmentDTO meta, byte[] content) {
    }
}
