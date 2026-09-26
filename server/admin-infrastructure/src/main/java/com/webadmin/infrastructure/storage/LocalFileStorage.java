package com.webadmin.infrastructure.storage;

import com.webadmin.application.survey.port.FileStoragePort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.survey.SurveyErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘文件存储。
 *
 * <h3>路径安全：一切写入都必须落在 {@code root} 之内</h3>
 * {@code relativePath} 由应用层拼装（含 uuid），理论上不含 {@code ..}，
 * 但这里仍然做<b>归一化后前缀校验</b> —— 一旦将来某处把用户输入拼进路径，
 * 没有这道校验就是任意文件写入（可覆盖配置文件、写 Web 目录拿 shell）。
 * 这类校验的成本是几行代码，缺失的代价是不可逆的。
 *
 * <h3>为什么不用 {@code MultipartFile.transferTo}</h3>
 * 应用层已把文件读成 {@code byte[]}（便于校验大小与类型），
 * 这里只需落盘；保持端口只依赖字节数组，换成 OSS/COS 实现时无需改应用层。
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStoragePort {

    /** 存储根目录。默认 {@code ./data/uploads}（相对启动目录），生产用环境变量覆盖。 */
    private final Path root;

    public LocalFileStorage(@Value("${webadmin.file.root:./data/uploads}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // 启动即失败优于"上传时才失败"：后者要到用户操作时才暴露，且错误现场已丢失
            throw new IllegalStateException("无法创建文件存储根目录：" + root, e);
        }
        log.info("文件存储根目录：{}", root);
    }

    @Override
    public String store(byte[] content, String relativePath) {
        Path target = resolveSafely(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            return relativePath;
        } catch (IOException e) {
            throw new BizException(SurveyErrorCode.FILE_STORE_FAILED,
                    "文件保存失败，请重试或联系管理员");
        }
    }

    @Override
    public byte[] read(String relativePath) {
        Path target = resolveSafely(relativePath);
        if (!Files.exists(target)) {
            throw new BizException(SurveyErrorCode.FILE_NOT_FOUND, "附件文件已不存在");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new BizException(SurveyErrorCode.FILE_NOT_FOUND, "附件文件读取失败");
        }
    }

    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolveSafely(relativePath));
        } catch (IOException | RuntimeException e) {
            // 补偿清理失败不该掩盖原始异常（调用方正在处理主异常），只记日志
            log.warn("清理文件失败，可能残留孤儿文件：{}", relativePath, e);
        }
    }

    private Path resolveSafely(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new BizException(SurveyErrorCode.FILE_TYPE_NOT_ALLOWED, "非法文件路径");
        }
        return target;
    }
}
