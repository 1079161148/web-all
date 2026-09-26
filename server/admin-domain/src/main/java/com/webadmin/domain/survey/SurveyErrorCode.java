package com.webadmin.domain.survey;

import com.webadmin.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * AI 调研限界上下文错误码（36xxx）。
 *
 * <p>调研是<b>支撑域（L1）</b>：没有充血聚合，取值与结构约束分别落在
 * Request 的 Bean Validation 与 AppService 的业务校验上。错误码仍然登记在这里，
 * 是为了让"这个上下文会有哪些失败"集中可见 —— 散在各处的字面量消息无法被审查。
 */
@RequiredArgsConstructor
public enum SurveyErrorCode implements ErrorCode {

    // ---- 调研任务 ----
    TASK_NOT_FOUND(36001, "调研任务不存在"),
    TASK_CODE_DUPLICATED(36002, "任务编码已存在"),

    // ---- 问卷 / 提纲 ----
    PAPER_NOT_FOUND(36101, "问卷不存在"),
    PAPER_CODE_DUPLICATED(36102, "问卷编码已存在"),

    // ---- 数据采集 ----
    COLLECT_NOT_FOUND(36201, "采集记录不存在"),

    // ---- 分析报告 ----
    REPORT_NOT_FOUND(36301, "报告不存在"),

    // ---- 模板库 ----
    TEMPLATE_NOT_FOUND(36401, "模板不存在"),
    TEMPLATE_CODE_DUPLICATED(36402, "模板编码已存在"),

    // ---- 附件 ----
    FILE_NOT_FOUND(36501, "附件不存在"),
    FILE_EMPTY(36502, "上传文件为空"),
    FILE_TYPE_NOT_ALLOWED(36503, "不支持的文件类型"),
    FILE_TOO_LARGE(36504, "文件大小超出上限"),
    FILE_STORE_FAILED(36505, "文件保存失败"),
    ;

    private final int code;
    private final String message;

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
