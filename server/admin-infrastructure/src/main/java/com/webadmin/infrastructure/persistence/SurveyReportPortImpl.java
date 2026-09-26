package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.survey.command.SurveyReportCommand;
import com.webadmin.application.survey.dto.SurveyReportDTO;
import com.webadmin.application.survey.port.SurveyReportPort;
import com.webadmin.application.survey.query.SurveyReportPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.SurveyReportMapper;
import com.webadmin.infrastructure.persistence.po.SurveyReportPO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 调研报告端口实现（列表排除 content 列，理由同 SurveyPaperPortImpl）。 */
@Repository
@RequiredArgsConstructor
public class SurveyReportPortImpl implements SurveyReportPort {

    private final SurveyReportMapper reportMapper;

    @Override
    public PageResult<SurveyReportDTO> page(SurveyReportPageQuery query) {
        Page<SurveyReportPO> page = reportMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<SurveyReportPO>()
                        .select(SurveyReportPO.class, info -> !"content".equals(info.getColumn()))
                        .eq(query.getTaskId() != null, SurveyReportPO::getTaskId, query.getTaskId())
                        .like(hasText(query.getReportTitle()), SurveyReportPO::getReportTitle, query.getReportTitle())
                        .eq(hasText(query.getReportType()), SurveyReportPO::getReportType, query.getReportType())
                        .eq(hasText(query.getStatus()), SurveyReportPO::getStatus, query.getStatus())
                        .orderByDesc(SurveyReportPO::getCreateTime)
                        .orderByAsc(SurveyReportPO::getId));

        return PageResult.of(page.getRecords().stream().map(SurveyReportPortImpl::toDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<SurveyReportDTO> findById(long id) {
        return Optional.ofNullable(reportMapper.selectById(id)).map(SurveyReportPortImpl::toDTO);
    }

    @Override
    public Long insert(SurveyReportCommand command) {
        SurveyReportPO po = new SurveyReportPO();
        po.setId(IdWorker.getId());
        apply(po, command);
        reportMapper.insert(po);
        return po.getId();
    }

    @Override
    public void update(long id, SurveyReportCommand command) {
        SurveyReportPO po = new SurveyReportPO();
        po.setId(id);
        apply(po, command);
        reportMapper.updateById(po);
    }

    @Override
    public void delete(long id) {
        reportMapper.deleteById(id);
    }

    private static void apply(SurveyReportPO po, SurveyReportCommand command) {
        po.setTaskId(command.taskId());
        po.setTaskName(command.taskName());
        po.setReportTitle(command.reportTitle());
        po.setReportType(command.reportType() == null ? "SUMMARY" : command.reportType());
        po.setAuthor(command.author());
        po.setPublishDate(command.publishDate());
        po.setSummary(command.summary());
        po.setContent(command.content());
        po.setFileId(command.fileId());
        po.setFileName(command.fileName());
        po.setStatus(command.status() == null ? "DRAFT" : command.status());
        po.setRemark(command.remark());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static SurveyReportDTO toDTO(SurveyReportPO po) {
        return new SurveyReportDTO(po.getId(), po.getTaskId(), po.getTaskName(), po.getReportTitle(),
                po.getReportType(), po.getAuthor(), po.getPublishDate(), po.getSummary(),
                po.getContent(), po.getFileId(), po.getFileName(), po.getStatus(),
                po.getRemark(), po.getTenantId(), po.getCreateTime());
    }
}
