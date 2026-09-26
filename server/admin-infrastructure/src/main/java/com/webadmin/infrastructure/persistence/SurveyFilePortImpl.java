package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.webadmin.application.survey.dto.SurveyAttachmentDTO;
import com.webadmin.application.survey.port.SurveyFilePort;
import com.webadmin.infrastructure.persistence.mapper.SurveyAttachmentMapper;
import com.webadmin.infrastructure.persistence.po.SurveyAttachmentPO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 附件元数据端口实现。 */
@Repository
@RequiredArgsConstructor
public class SurveyFilePortImpl implements SurveyFilePort {

    /**
     * 下载地址前缀。
     *
     * <p>在这里拼而不是让前端拼：路径属于接口契约的一部分，
     * 将来换成签名 URL（OSS 直链）时只改这一处。
     */
    private static final String CONTENT_URL_PREFIX = "/api/v1/survey/files/";

    private final SurveyAttachmentMapper attachmentMapper;

    @Override
    public Long insert(String bizType, Long bizId, String fileName, String filePath,
                       long fileSize, String contentType) {
        SurveyAttachmentPO po = new SurveyAttachmentPO();
        po.setId(IdWorker.getId());
        po.setBizType(bizType);
        po.setBizId(bizId);
        po.setFileName(fileName);
        po.setFilePath(filePath);
        po.setFileSize(fileSize);
        po.setContentType(contentType);
        po.setStorageType("LOCAL");
        attachmentMapper.insert(po);
        return po.getId();
    }

    @Override
    public Optional<SurveyAttachmentDTO> findById(long id) {
        return Optional.ofNullable(attachmentMapper.selectById(id)).map(SurveyFilePortImpl::toDTO);
    }

    @Override
    public List<SurveyAttachmentDTO> listByBiz(String bizType, Long bizId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<SurveyAttachmentPO>()
                        .eq(SurveyAttachmentPO::getBizType, bizType)
                        .eq(bizId != null, SurveyAttachmentPO::getBizId, bizId)
                        .orderByDesc(SurveyAttachmentPO::getCreateTime))
                .stream()
                .map(SurveyFilePortImpl::toDTO)
                .toList();
    }

    @Override
    public void delete(long id) {
        attachmentMapper.deleteById(id);
    }

    private static SurveyAttachmentDTO toDTO(SurveyAttachmentPO po) {
        return new SurveyAttachmentDTO(po.getId(), po.getBizType(), po.getBizId(), po.getFileName(),
                po.getFilePath(), po.getFileSize(), po.getContentType(), po.getStorageType(),
                CONTENT_URL_PREFIX + po.getId() + "/content", po.getCreateTime());
    }
}
