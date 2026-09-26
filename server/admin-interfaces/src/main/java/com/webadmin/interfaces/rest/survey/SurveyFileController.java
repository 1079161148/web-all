package com.webadmin.interfaces.rest.survey;

import com.webadmin.application.survey.SurveyFileAppService;
import com.webadmin.application.survey.dto.SurveyAttachmentDTO;
import com.webadmin.application.survey.port.SurveyFilePort;
import com.webadmin.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件接口（上传 / 下载 / 列表）。
 *
 * <h3>上限与"大文件"的边界</h3>
 * 单文件 20MB，一次性上传（不支持分片/断点续传 —— 自研分片协议是明确红线，
 * 需要时应整体切到 Uppy + tus 或对象存储直传）。因此：
 * <ul>
 *   <li>采集数据文件、报告附件、编辑器插图：走本接口</li>
 *   <li>大文件（>20MB，如原始访谈录音/视频）：应改用对象存储直传，
 *       前端拿临时凭证直传后把 URL 交给业务接口</li>
 * </ul>
 *
 * <h3>下载为什么只要求登录</h3>
 * 附件是被富文本正文或列表引用的内容，能拿到 id 说明已经在业务上下文里。
 * 再加一道 {@code srvy:*:query} 会让"能看报告但不能下载报告附件"这种
 * 割裂状态出现，而附件本身不含越权信息（id 由服务端生成、且带租户隔离）。
 */
@Tag(name = "AI调研-附件", description = "上传（按租户/业务/年月分目录落盘）、下载、按业务查询")
@RestController
@RequestMapping("/api/v1/survey/files")
@RequiredArgsConstructor
@Validated
public class SurveyFileController {

    private final SurveyFileAppService fileAppService;
    private final SurveyFilePort filePort;

    @Operation(operationId = "uploadSurveyFile", summary = "上传附件",
            description = "单文件上限 20MB。落盘路径由服务端决定：{租户}/{业务类型}/{年月}/{uuid}.{ext}")
    @PreAuthorize("@ps.hasPermission('srvy:file:upload')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<FileResponse> upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam("bizType") String bizType,
                                 @RequestParam(value = "bizId", required = false) Long bizId)
            throws IOException {
        SurveyAttachmentDTO dto = fileAppService.upload(file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), bizType, bizId);
        return R.ok(FileResponse.from(dto));
    }

    @Operation(operationId = "getSurveyFile", summary = "查询附件元数据")
    @GetMapping("/{id}")
    public R<FileResponse> detail(@PathVariable Long id) {
        return R.ok(FileResponse.from(fileAppService.detail(id)));
    }

    @Operation(operationId = "listSurveyFiles", summary = "按业务对象查询附件")
    @GetMapping
    public R<List<FileResponse>> list(@RequestParam String bizType,
                                      @RequestParam(required = false) Long bizId) {
        return R.ok(filePort.listByBiz(bizType, bizId).stream().map(FileResponse::from).toList());
    }

    @Operation(operationId = "downloadSurveyFile", summary = "下载/预览附件内容",
            description = "图片可直接作为富文本 img src 使用")
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        SurveyFileAppService.FileContent file = fileAppService.read(id);
        String encodedName = URLEncoder.encode(file.meta().fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        MediaType mediaType = file.meta().contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(file.meta().contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedName)
                .body(file.content());
    }

    // ==================================================================

    @Schema(description = "附件")
    public record FileResponse(
            @Schema(description = "附件 ID") Long id,
            @Schema(description = "业务类型：COLLECT_FILE/REPORT_FILE/EDITOR_IMAGE") String bizType,
            @Schema(description = "业务 ID") Long bizId,
            @Schema(description = "原始文件名") String fileName,
            @Schema(description = "相对存储路径") String filePath,
            @Schema(description = "字节数") Long fileSize,
            @Schema(description = "MIME 类型") String contentType,
            @Schema(description = "存储介质：LOCAL/OSS/COS") String storageType,
            @Schema(description = "访问地址（下载/预览）") String url) {

        public static FileResponse from(SurveyAttachmentDTO dto) {
            return dto == null ? null : new FileResponse(dto.id(), dto.bizType(), dto.bizId(),
                    dto.fileName(), dto.filePath(), dto.fileSize(), dto.contentType(),
                    dto.storageType(), dto.url());
        }
    }
}
