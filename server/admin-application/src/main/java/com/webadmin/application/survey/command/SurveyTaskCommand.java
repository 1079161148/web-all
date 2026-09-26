package com.webadmin.application.survey.command;

import java.time.LocalDate;

/**
 * 调研任务新增/修改命令。
 *
 * <p>用 record 而不是十几个方法参数：字段一多，位置参数就变成"靠顺序记忆"的接口 ——
 * 调用处把 {@code status} 与 {@code priority} 传反了不会报错。
 */
public record SurveyTaskCommand(
        String taskCode,
        String taskName,
        String taskType,
        String priority,
        String ownerName,
        Long deptId,
        LocalDate startDate,
        LocalDate endDate,
        Integer progress,
        String status,
        String remark) {

    /**
     * 复制并替换归属部门。
     *
     * <p>用于两种"命令里没带部门"的场景：新增时补默认值（创建人所在部门）、
     * 修改时保留原值。放在 record 上比在调用处 new 一遍十一个参数更不容易出错
     * —— 也不会出现"复制时漏传某个字段"这类静默丢数据的问题。
     */
    public SurveyTaskCommand withDeptId(Long newDeptId) {
        return new SurveyTaskCommand(taskCode, taskName, taskType, priority, ownerName,
                newDeptId, startDate, endDate, progress, status, remark);
    }
}
