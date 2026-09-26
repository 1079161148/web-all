package com.webadmin.application.survey;

import com.webadmin.application.survey.command.SurveyTaskCommand;
import com.webadmin.application.survey.dto.SurveyTaskDTO;
import com.webadmin.application.survey.event.SurveyTaskChangedEvent;
import com.webadmin.application.survey.port.SurveyTaskPort;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.UserId;
import com.webadmin.domain.survey.SurveyErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/** 调研任务应用服务（L1 支撑域，事务脚本）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyTaskAppService {

    private final SurveyTaskPort taskPort;
    private final CurrentUserPort currentUserPort;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Long create(SurveyTaskCommand command) {
        assertCodeAvailable(command.taskCode(), null);
        SurveyTaskCommand effective = withDefaultDept(command);
        Long id = taskPort.insert(effective);
        // 审计事件（事务性 Outbox：事件与业务数据同事务落库，提交后由消费者写审计）
        var current = currentUserPort.requireCurrentUser();
        eventPublisher.publishEvent(new SurveyTaskChangedEvent(
                "CREATED", null, taskPort.findById(id).orElse(null),
                current.tenantId(), current.userId(), current.username()));
        log.info("创建调研任务 id={} code={} deptId={}", id, command.taskCode(), effective.deptId());
        return id;
    }

    @Transactional
    public void update(long id, SurveyTaskCommand command) {
        SurveyTaskDTO existing = taskPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.TASK_NOT_FOUND, "调研任务不存在"));
        assertCodeAvailable(command.taskCode(), id);
        // 命令里没带部门时<b>保留原值</b>，不能顺手套成 null：
        // 否则一次普通编辑就把归属清空，而归属为空的任务在 DEPT / DEPT_AND_CHILD
        // 范围内对所有人（含原创建者）都不可见 —— 一次编辑等于把任务"藏起来"了
        SurveyTaskCommand effective = command.deptId() != null
                ? command
                : command.withDeptId(existing.deptId());
        taskPort.update(id, effective);
        var current = currentUserPort.requireCurrentUser();
        eventPublisher.publishEvent(new SurveyTaskChangedEvent(
                "UPDATED", existing, taskPort.findById(id).orElse(null),
                current.tenantId(), current.userId(), current.username()));
    }

    /**
     * 归属部门缺省规则：<b>未指定时取创建人所在部门</b>。
     *
     * <h3>为什么必须有这个默认值（而不是留空）</h3>
     * {@code dept_id} 是行级数据权限在任务表上的过滤列。若新建任务时留空：
     * <ul>
     *   <li>{@code DEPT} / {@code DEPT_AND_CHILD} 范围下，{@code dept_id IN (...)} 匹配不到它
     *       → <b>连创建者自己都看不到刚建的任务</b></li>
     *   <li>{@code SELF} 范围下能看到（走 {@code create_by}），于是现象变成
     *       "换个数据范围角色就找不到任务了"</li>
     * </ul>
     * 这类"数据自己把自己过滤掉"的问题不会报错，只会让人觉得系统时灵时不灵。
     * 归属必须有写入方，这是数据权限能成立的前提 —— 与 {@code create_by} 同理。
     *
     * <p>创建人未分配部门时仍然留空，但<b>记 WARN</b>：这是数据问题（新员工未分配部门），
     * 需要可见的信号，而不是静默写入一条谁都搜不到的任务。
     */
    private SurveyTaskCommand withDefaultDept(SurveyTaskCommand command) {
        if (command.deptId() != null) {
            return command;
        }
        Long deptId = currentUserPort.currentUser()
                .flatMap(user -> userRepository.findDeptId(UserId.of(user.userId())))
                .orElse(null);
        if (deptId == null) {
            log.warn("无法确定调研任务的归属部门（调用方未指定且创建人未分配部门），"
                    + "该任务在部门类数据范围内将不可见。taskCode={}", command.taskCode());
        }
        return command.withDeptId(deptId);
    }

    @Transactional
    public void delete(long id) {
        taskPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.TASK_NOT_FOUND, "调研任务不存在"));
        taskPort.delete(id);
    }

    /**
     * 批量创建（Excel 导入）。
     *
     * <p><b>失败即整批回滚</b>，并把行号带进错误消息。理由：导入是"一次意图"，
     * 部分成功会让用户拿到一个半成品数据集，而"哪几行进去了"无从得知 ——
     * 让用户改完文件重传是更清晰的行为。
     */
    @Transactional
    public int batchCreate(List<SurveyTaskCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < commands.size(); i++) {
            try {
                create(commands.get(i));
            } catch (BizException e) {
                throw new BizException(SurveyErrorCode.TASK_CODE_DUPLICATED,
                        "第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        log.info("批量导入调研任务 {} 条", commands.size());
        return commands.size();
    }

    private void assertCodeAvailable(String taskCode, Long excludeId) {
        if (taskPort.codeExists(taskCode, excludeId)) {
            throw new BizException(SurveyErrorCode.TASK_CODE_DUPLICATED,
                    "任务编码「" + taskCode + "」已存在");
        }
    }
}
