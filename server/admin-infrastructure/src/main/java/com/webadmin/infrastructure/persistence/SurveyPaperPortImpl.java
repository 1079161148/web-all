package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.survey.command.SurveyPaperCommand;
import com.webadmin.application.survey.dto.SurveyPaperDTO;
import com.webadmin.application.survey.port.SurveyPaperPort;
import com.webadmin.application.survey.query.SurveyPaperPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.SurveyPaperMapper;
import com.webadmin.infrastructure.persistence.po.SurveyPaperPO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 问卷 / 提纲端口实现。
 *
 * <p>分页查询<b>显式排除 {@code content} 列</b>：正文是富文本 HTML，动辄几十 KB，
 * 一页 20 条就会把整页正文一起传回（还会拖慢 count 与网络）。
 * 列表只需要标题与状态，正文走详情接口。
 */
@Repository
@RequiredArgsConstructor
public class SurveyPaperPortImpl implements SurveyPaperPort {

    private final SurveyPaperMapper paperMapper;

    @Override
    public PageResult<SurveyPaperDTO> page(SurveyPaperPageQuery query) {
        Page<SurveyPaperPO> page = paperMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<SurveyPaperPO>()
                        .select(SurveyPaperPO.class, info -> !"content".equals(info.getColumn()))
                        .like(hasText(query.getPaperCode()), SurveyPaperPO::getPaperCode, query.getPaperCode())
                        .like(hasText(query.getTitle()), SurveyPaperPO::getTitle, query.getTitle())
                        .eq(hasText(query.getPaperType()), SurveyPaperPO::getPaperType, query.getPaperType())
                        .eq(hasText(query.getStatus()), SurveyPaperPO::getStatus, query.getStatus())
                        .orderByDesc(SurveyPaperPO::getCreateTime)
                        .orderByAsc(SurveyPaperPO::getId));

        return PageResult.of(page.getRecords().stream().map(SurveyPaperPortImpl::toDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<SurveyPaperDTO> findById(long id) {
        return Optional.ofNullable(paperMapper.selectById(id)).map(SurveyPaperPortImpl::toDTO);
    }

    @Override
    public boolean codeExists(String paperCode, Long excludeId) {
        return paperMapper.exists(new LambdaQueryWrapper<SurveyPaperPO>()
                .eq(SurveyPaperPO::getPaperCode, paperCode)
                .ne(excludeId != null, SurveyPaperPO::getId, excludeId));
    }

    @Override
    public Long insert(SurveyPaperCommand command) {
        SurveyPaperPO po = new SurveyPaperPO();
        po.setId(IdWorker.getId());
        apply(po, command);
        paperMapper.insert(po);
        return po.getId();
    }

    @Override
    public void update(long id, SurveyPaperCommand command) {
        SurveyPaperPO po = new SurveyPaperPO();
        po.setId(id);
        apply(po, command);
        paperMapper.updateById(po);
    }

    @Override
    public void delete(long id) {
        paperMapper.deleteById(id);
    }

    private static void apply(SurveyPaperPO po, SurveyPaperCommand command) {
        po.setPaperCode(command.paperCode());
        po.setTitle(command.title());
        po.setPaperType(command.paperType() == null ? "QUESTIONNAIRE" : command.paperType());
        po.setVersionNo(command.versionNo() == null ? 1 : command.versionNo());
        po.setContent(command.content());
        po.setStatus(command.status() == null ? "DRAFT" : command.status());
        po.setRemark(command.remark());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static SurveyPaperDTO toDTO(SurveyPaperPO po) {
        return new SurveyPaperDTO(po.getId(), po.getPaperCode(), po.getTitle(), po.getPaperType(),
                po.getVersionNo(), po.getContent(), po.getStatus(), po.getRemark(),
                po.getTenantId(), po.getCreateTime());
    }
}
