package com.webadmin.application.ai;

import com.webadmin.application.audit.port.AuditLogPort;
import com.webadmin.application.dispatch.OpsTicketService;
import com.webadmin.application.dispatch.port.OpsTicketPort;
import com.webadmin.common.error.BizException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * AI 指挥中心：把系统能力暴露为模型可调用的工具（Tool Calling）。
 *
 * <h3>"指挥千军万马"的实现本质</h3>
 * 模型本身没有执行能力 —— 它输出的是<b>工具调用意图</b>（工具名 + 参数）；
 * 真正执行的是 {@link #executeTool}。"模型出意图、服务端出行动"的循环
 * 就是 Agent Harness：指令 → 模型(带 tools schema) → tool_calls →
 * 服务端执行 → 结果回传 → 模型继续推理 → … → 最终回答（最多 4 轮防打转）。
 *
 * <h3>工具集 = 授权能力面</h3>
 * 拿到 {@code ai:agent:use} 权限即授权本工具集全部操作（含抢单/办结写操作
 * —— 指挥中心的语义就是"代为行动"）。写操作仍走各 service 的条件 UPDATE，
 * 原子性与状态机不受影响。工具集刻意小而完整：每加一个工具，授权面大一分。
 *
 * <h3>参数以 Map 进出（无 Jackson，架构分层约束）</h3>
 * JSON 解析/序列化在 interfaces 层；本层只面对 Map，返回给模型的永远是文本：
 * 成功是数据摘要，失败是失败原因 —— 让模型能解释或换方案，而不是炸掉循环。
 */
@Service
public class AiAgentService {

    private static final String TOOLS_SCHEMA = "["
            + "{\"type\":\"function\",\"function\":{\"name\":\"list_tickets\","
            + "\"description\":\"查询当前租户的工单列表（最近100条），可按状态过滤。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"state\":{"
            + "\"type\":\"string\",\"enum\":[\"PENDING\",\"CLAIMED\",\"DONE\",\"ESCALATED\"],"
            + "\"description\":\"可选，按状态过滤\"}}}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"ticket_stats\","
            + "\"description\":\"统计工单概况（各状态数量），回答战况类问题用它。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{}}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"claim_ticket\","
            + "\"description\":\"接手工单（仅PENDING/ESCALATED可接，被他人先抢会失败）。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"id\":{"
            + "\"type\":\"integer\",\"description\":\"工单ID\"}},\"required\":[\"id\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"complete_ticket\","
            + "\"description\":\"办结工单（仅接单人可办结）。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"id\":{"
            + "\"type\":\"integer\",\"description\":\"工单ID\"}},\"required\":[\"id\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"release_ticket\","
            + "\"description\":\"把工单放回待处理池（仅接单人可放回）。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"id\":{"
            + "\"type\":\"integer\",\"description\":\"工单ID\"}},\"required\":[\"id\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"recent_audit\","
            + "\"description\":\"查询最近操作审计（谁在何时做了什么变更）。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"count\":{"
            + "\"type\":\"integer\",\"description\":\"条数，默认5，最多20\"}}}}}"
            + "]";

    private final OpsTicketService ticketService;
    private final AuditLogPort auditLogPort;

    public AiAgentService(OpsTicketService ticketService, AuditLogPort auditLogPort) {
        this.ticketService = ticketService;
        this.auditLogPort = auditLogPort;
    }

    public String toolsSchemaJson() {
        return TOOLS_SCHEMA;
    }

    /** 执行一次工具调用。任何失败都转成文本返回（见类注释）。 */
    public String executeTool(String name, Map<String, Object> args) {
        try {
            return switch (name == null ? "" : name) {
                case "list_tickets" -> listTickets(args == null ? null : args.get("state"));
                case "ticket_stats" -> ticketStats();
                case "claim_ticket" -> withId(args, "接手工单", ticketService::claim);
                case "complete_ticket" -> withId(args, "办结工单", ticketService::complete);
                case "release_ticket" -> withId(args, "放回工单", ticketService::release);
                case "recent_audit" -> recentAudit(args);
                default -> "未知工具：" + name;
            };
        } catch (BizException ex) {
            return "操作失败：" + ex.getMessage();
        } catch (Exception ex) {
            return "执行异常：" + ex.getMessage();
        }
    }

    private interface TicketAction {
        void run(long ticketId);
    }

    private String withId(Map<String, Object> args, String label, TicketAction action) {
        long id = requireId(args);
        action.run(id);
        return label + "成功：#" + id;
    }

    private long requireId(Map<String, Object> args) {
        Object id = args == null ? null : args.get("id");
        if (id instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(id));
        } catch (Exception ex) {
            throw new BizException(com.webadmin.common.error.CommonErrorCode.PARAM_INVALID,
                    "缺少有效的工单 ID");
        }
    }

    private String listTickets(Object stateFilter) {
        List<OpsTicketPort.OpsTicket> tickets = ticketService.list();
        StringBuilder out = new StringBuilder();
        int matched = 0;
        for (OpsTicketPort.OpsTicket ticket : tickets) {
            if (stateFilter != null && !String.valueOf(stateFilter).equalsIgnoreCase(ticket.state())) {
                continue;
            }
            matched++;
            out.append("\n- #").append(ticket.id()).append(' ').append(ticket.title())
                    .append(" [").append(ticket.state()).append('/').append(ticket.priority())
                    .append("] 接单人=").append(ticket.claimer() == null ? "无" : ticket.claimer());
        }
        if (stateFilter != null) {
            out.insert(0, "匹配 " + matched + " 条（过滤 " + stateFilter + "），共 "
                    + tickets.size() + " 条。");
        } else {
            out.insert(0, "共 " + tickets.size() + " 条：");
        }
        return out.toString();
    }

    private String ticketStats() {
        List<OpsTicketPort.OpsTicket> tickets = ticketService.list();
        long pending = tickets.stream()
                .filter(t -> "PENDING".equals(t.state()) || "ESCALATED".equals(t.state())).count();
        long claimed = tickets.stream().filter(t -> "CLAIMED".equals(t.state())).count();
        long done = tickets.stream().filter(t -> "DONE".equals(t.state())).count();
        long escalated = tickets.stream().filter(t -> "ESCALATED".equals(t.state())).count();
        return "工单概况：待处理 " + pending + "，处理中 " + claimed + "，已完成 " + done
                + "，其中已超时升级 " + escalated + "。";
    }

    private String recentAudit(Map<String, Object> args) {
        int count = 5;
        if (args != null && args.get("count") instanceof Number number) {
            count = Math.max(1, Math.min(20, number.intValue()));
        }
        var page = auditLogPort.page(com.webadmin.common.tenant.TenantContext.require(), 1, count);
        StringBuilder out = new StringBuilder("最近审计 " + page.records().size() + " 条：");
        for (AuditLogPort.AuditLogEntry entry : page.records()) {
            out.append("\n- ").append(entry.username()).append(' ').append(entry.summary())
                    .append("（").append(entry.action()).append("）");
        }
        return out.toString();
    }
}
