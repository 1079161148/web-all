package com.webadmin.application.survey.port;

import com.webadmin.application.survey.dto.SurveyAttachmentDTO;
import java.util.List;
import java.util.Optional;

/** 附件元数据读写端口（文件本体由 {@link FileStoragePort} 负责）。 */
public interface SurveyFilePort {

    Long insert(String bizType, Long bizId, String fileName, String filePath,
                long fileSize, String contentType);

    Optional<SurveyAttachmentDTO> findById(long id);

    /** 按业务对象取附件（同一业务可挂多个附件）。 */
    List<SurveyAttachmentDTO> listByBiz(String bizType, Long bizId);

    void delete(long id);
}
