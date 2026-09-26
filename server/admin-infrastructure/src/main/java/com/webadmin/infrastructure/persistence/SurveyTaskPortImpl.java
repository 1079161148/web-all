package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.survey.command.SurveyTaskCommand;
import com.webadmin.application.survey.dto.SurveyTaskDTO;
import com.webadmin.application.survey.port.SurveyTaskPort;
import com.webadmin.application.survey.query.SurveyTaskPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.SurveyTaskMapper;
import com.webadmin.infrastructure.persistence.po.SurveyTaskPO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 调研任务端口实现。 */
@Repository
@RequiredArgsConstructor
public class SurveyTaskPortImpl implements SurveyTaskPort {

    /** 下拉选项的硬上限：防止任务量很大时把整表拉到前端。 */
    private static final int OPTION_LIMIT = 200;

    private final SurveyTaskMapper taskMapper;

    @Override
    public PageResult<SurveyTaskDTO> page(SurveyTaskPageQuery query) {
        // 走 XML Mapper 方法而不是 selectPage(wrapper)：只有"具体方法"才能标注
        // @DataScope（行级数据权限），Wrapper 调用会让列表静默绕过它
        Page<SurveyTaskPO> page = new Page<>(query.getPage(), query.getSize());
        List<SurveyTaskPO> records = taskMapper.selectTaskPage(page, query);

        return PageResult.of(records.stream().map(SurveyTaskPortImpl::toDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<SurveyTaskDTO> findById(long id) {
        return Optional.ofNullable(taskMapper.selectById(id)).map(SurveyTaskPortImpl::toDTO);
    }

    @Override
    public List<SurveyTaskDTO> listAll() {
        // 同样受数据权限约束：否则用户能把采集记录挂到自己看不见的任务上
        return taskMapper.selectTaskOptions(OPTION_LIMIT).stream()
                .map(SurveyTaskPortImpl::toDTO)
                .toList();
    }

    @Override
    public boolean codeExists(String taskCode, Long excludeId) {
        return taskMapper.exists(new LambdaQueryWrapper<SurveyTaskPO>()
                .eq(SurveyTaskPO::getTaskCode, taskCode)
                .ne(excludeId != null, SurveyTaskPO::getId, excludeId));
    }

    @Override
    public Long insert(SurveyTaskCommand command) {
        SurveyTaskPO po = new SurveyTaskPO();
        po.setId(IdWorker.getId());
        // tenant_id 由租户拦截器自动补（srvy_task 不在忽略名单里）—— 刻意不设置
        apply(po, command);
        taskMapper.insert(po);
        return po.getId();
    }

    @Override
    public void update(long id, SurveyTaskCommand command) {
        SurveyTaskPO po = new SurveyTaskPO();
        po.setId(id);
        apply(po, command);
        taskMapper.updateById(po);
    }

    @Override
    public void delete(long id) {
        taskMapper.deleteById(id);
    }

    private static void apply(SurveyTaskPO po, SurveyTaskCommand command) {
        po.setTaskCode(command.taskCode());
        po.setTaskName(command.taskName());
        po.setTaskType(command.taskType() == null ? "SURVEY" : command.taskType());
        po.setPriority(command.priority() == null ? "MEDIUM" : command.priority());
        po.setOwnerName(command.ownerName());
        po.setDeptId(command.deptId());
        po.setStartDate(command.startDate());
        po.setEndDate(command.endDate());
        po.setProgress(command.progress() == null ? 0 : command.progress());
        po.setStatus(command.status() == null ? "PENDING" : command.status());
        po.setRemark(command.remark());
    }

    private static SurveyTaskDTO toDTO(SurveyTaskPO po) {
        return new SurveyTaskDTO(po.getId(), po.getTaskCode(), po.getTaskName(), po.getTaskType(),
                po.getPriority(), po.getOwnerName(), po.getDeptId(), po.getStartDate(),
                po.getEndDate(), po.getProgress(), po.getStatus(), po.getRemark(),
                po.getTenantId(), po.getCreateTime());
    }
}
