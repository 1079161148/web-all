package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.survey.command.SurveyCollectCommand;
import com.webadmin.application.survey.dto.SurveyCollectDTO;
import com.webadmin.application.survey.port.SurveyCollectPort;
import com.webadmin.application.survey.query.SurveyCollectPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.SurveyCollectMapper;
import com.webadmin.infrastructure.persistence.po.SurveyCollectPO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 数据采集端口实现。 */
@Repository
@RequiredArgsConstructor
public class SurveyCollectPortImpl implements SurveyCollectPort {

    private final SurveyCollectMapper collectMapper;

    @Override
    public PageResult<SurveyCollectDTO> page(SurveyCollectPageQuery query) {
        Page<SurveyCollectPO> page = collectMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<SurveyCollectPO>()
                        .eq(query.getTaskId() != null, SurveyCollectPO::getTaskId, query.getTaskId())
                        .like(hasText(query.getCollector()), SurveyCollectPO::getCollector, query.getCollector())
                        .eq(hasText(query.getChannel()), SurveyCollectPO::getChannel, query.getChannel())
                        .eq(hasText(query.getStatus()), SurveyCollectPO::getStatus, query.getStatus())
                        .orderByDesc(SurveyCollectPO::getCollectDate)
                        .orderByAsc(SurveyCollectPO::getId));

        return PageResult.of(page.getRecords().stream().map(SurveyCollectPortImpl::toDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<SurveyCollectDTO> findById(long id) {
        return Optional.ofNullable(collectMapper.selectById(id)).map(SurveyCollectPortImpl::toDTO);
    }

    @Override
    public Long insert(SurveyCollectCommand command) {
        SurveyCollectPO po = new SurveyCollectPO();
        po.setId(IdWorker.getId());
        apply(po, command);
        collectMapper.insert(po);
        return po.getId();
    }

    @Override
    public void update(long id, SurveyCollectCommand command) {
        SurveyCollectPO po = new SurveyCollectPO();
        po.setId(id);
        apply(po, command);
        collectMapper.updateById(po);
    }

    @Override
    public void delete(long id) {
        collectMapper.deleteById(id);
    }

    private static void apply(SurveyCollectPO po, SurveyCollectCommand command) {
        po.setTaskId(command.taskId());
        po.setTaskName(command.taskName());
        po.setChannel(command.channel() == null ? "ONLINE" : command.channel());
        po.setCollector(command.collector());
        po.setCollectDate(command.collectDate());
        po.setSampleCount(command.sampleCount() == null ? 0 : command.sampleCount());
        po.setValidCount(command.validCount() == null ? 0 : command.validCount());
        po.setQualityScore(command.qualityScore() == null ? 0 : command.qualityScore());
        po.setFileId(command.fileId());
        po.setFileName(command.fileName());
        po.setStatus(command.status() == null ? "COLLECTING" : command.status());
        po.setRemark(command.remark());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static SurveyCollectDTO toDTO(SurveyCollectPO po) {
        return new SurveyCollectDTO(po.getId(), po.getTaskId(), po.getTaskName(), po.getChannel(),
                po.getCollector(), po.getCollectDate(), po.getSampleCount(), po.getValidCount(),
                po.getQualityScore(), po.getFileId(), po.getFileName(), po.getStatus(),
                po.getRemark(), po.getTenantId(), po.getCreateTime());
    }
}
