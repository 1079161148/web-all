package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.survey.command.SurveyTemplateCommand;
import com.webadmin.application.survey.dto.SurveyTemplateDTO;
import com.webadmin.application.survey.port.SurveyTemplatePort;
import com.webadmin.application.survey.query.SurveyTemplatePageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.SurveyTemplateMapper;
import com.webadmin.infrastructure.persistence.po.SurveyTemplatePO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 调研模板端口实现（列表排除 content 列，理由同 SurveyPaperPortImpl）。 */
@Repository
@RequiredArgsConstructor
public class SurveyTemplatePortImpl implements SurveyTemplatePort {

    private final SurveyTemplateMapper templateMapper;

    @Override
    public PageResult<SurveyTemplateDTO> page(SurveyTemplatePageQuery query) {
        Page<SurveyTemplatePO> page = templateMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<SurveyTemplatePO>()
                        .select(SurveyTemplatePO.class, info -> !"content".equals(info.getColumn()))
                        .like(hasText(query.getTemplateCode()), SurveyTemplatePO::getTemplateCode, query.getTemplateCode())
                        .like(hasText(query.getTemplateName()), SurveyTemplatePO::getTemplateName, query.getTemplateName())
                        .eq(hasText(query.getCategory()), SurveyTemplatePO::getCategory, query.getCategory())
                        .eq(hasText(query.getStatus()), SurveyTemplatePO::getStatus, query.getStatus())
                        .orderByDesc(SurveyTemplatePO::getUsageCount)
                        .orderByAsc(SurveyTemplatePO::getId));

        return PageResult.of(page.getRecords().stream().map(SurveyTemplatePortImpl::toDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<SurveyTemplateDTO> findById(long id) {
        return Optional.ofNullable(templateMapper.selectById(id)).map(SurveyTemplatePortImpl::toDTO);
    }

    @Override
    public boolean codeExists(String templateCode, Long excludeId) {
        return templateMapper.exists(new LambdaQueryWrapper<SurveyTemplatePO>()
                .eq(SurveyTemplatePO::getTemplateCode, templateCode)
                .ne(excludeId != null, SurveyTemplatePO::getId, excludeId));
    }

    @Override
    public Long insert(SurveyTemplateCommand command) {
        SurveyTemplatePO po = new SurveyTemplatePO();
        po.setId(IdWorker.getId());
        apply(po, command);
        templateMapper.insert(po);
        return po.getId();
    }

    @Override
    public void update(long id, SurveyTemplateCommand command) {
        SurveyTemplatePO po = new SurveyTemplatePO();
        po.setId(id);
        apply(po, command);
        templateMapper.updateById(po);
    }

    @Override
    public void delete(long id) {
        templateMapper.deleteById(id);
    }

    @Override
    public void increaseUsage(long id) {
        // 读-改-写而不是 `SET usage_count = usage_count + 1`：
        // 后者需要手写 SQL 片段，而这里的并发量（人工点"套用"）不值得为它引入 UpdateWrapper。
        // 若将来出现高频调用，再改成 SQL 原子自增。
        SurveyTemplatePO existing = templateMapper.selectById(id);
        if (existing == null) {
            return;
        }
        SurveyTemplatePO po = new SurveyTemplatePO();
        po.setId(id);
        po.setUsageCount((existing.getUsageCount() == null ? 0 : existing.getUsageCount()) + 1);
        templateMapper.updateById(po);
    }

    private static void apply(SurveyTemplatePO po, SurveyTemplateCommand command) {
        po.setTemplateCode(command.templateCode());
        po.setTemplateName(command.templateName());
        po.setCategory(command.category() == null ? "NPS" : command.category());
        po.setContent(command.content());
        po.setUsageCount(command.usageCount() == null ? 0 : command.usageCount());
        po.setStatus(command.status() == null ? "ACTIVE" : command.status());
        po.setRemark(command.remark());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static SurveyTemplateDTO toDTO(SurveyTemplatePO po) {
        return new SurveyTemplateDTO(po.getId(), po.getTemplateCode(), po.getTemplateName(),
                po.getCategory(), po.getContent(), po.getUsageCount(), po.getStatus(),
                po.getRemark(), po.getTenantId(), po.getCreateTime());
    }
}
