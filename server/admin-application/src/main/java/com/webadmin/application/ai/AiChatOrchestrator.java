package com.webadmin.application.ai;

import com.webadmin.application.dispatch.OpsTicketService;
import com.webadmin.application.dispatch.port.OpsTicketPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * AI 对话编排器（纯 Java，无 servlet/Jackson 依赖 —— 架构门禁约束）。
 *
 * <h3>这一层负责 Harness 中"与协议无关"的部分</h3>
 * <ul>
 *   <li><b>校验</b>：历史条数、单条长度 —— 与传输形态无关的规则；</li>
 *   <li><b>上下文注入</b>：把当前租户的工单概况（真实数据库）组装为
 *       system 提示词。这就是"RAG-lite"的接缝 —— 未来接向量检索，
 *       只替换本方法的取数来源，上下游不动；</li>
 *   <li><b>演示模式回答</b>：无 Key 环境的确定性回答生成，
 *       让演示环境与生产走同一条 UX 路径；</li>
 *   <li><b>设置快照</b>：上游地址/Key/模型以不可变 record 传入 ——
 *       本层不知道"配置从哪来"（Spring 绑定在 interfaces 层）。</li>
 * </ul>
 *
 * <h3>会话历史存放在前端</h3>
 * 服务端无状态：多轮对话由前端把历史回传（标准 OpenAI 协议形态），
 * 删除/新建会话是纯前端操作；代价是历史随请求多传 ——
 * 对话历史本来就属于提示词的一部分。
 */
@Service
public class AiChatOrchestrator {

    private static final String SYSTEM_PROMPT = """
            你是本后台管理系统的 AI 助手（客服/运维向）。回答要求：
            1. 用简体中文，简洁、直接，必要时用列表；使用标准 Markdown（标题井号后加空格）；
            2. 涉及工单/数据的问题，优先依据下方"平台实时上下文"回答，不要编造数字；
            3. 当数据适合可视化时（趋势/占比/对比），在回答中输出一个 ```echarts 代码块，
               内容为合法的 ECharts option JSON（type 仅用 bar/line/pie，数据来自上下文，不要编造）；
            4. 超出你掌握信息的问题，明确说明需要用户在系统里进一步确认；
            5. 不要输出与后台管理无关的营销内容。
            """;

    public static final int MAX_MESSAGES = 20;
    public static final int MAX_CONTENT = 4000;

    private final OpsTicketService ticketService;

    public AiChatOrchestrator(OpsTicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 一条对话消息（前端回传的历史）。
     *
     * <p>{@code content} 刻意是 {@code Object}：OpenAI 协议里它可以是
     * 纯字符串，也可以是<b>内容块数组</b>（多模态：text + image_url）。
     * 编排层不解释数组结构（那是模型的事），只做"原样透传 + 尺寸守门"。
     */
    public record ChatTurn(String role, Object content) {
    }

    /** 上游设置的不可变快照（interfaces 层从配置绑定后传入）。 */
    public record AiSettings(
            boolean realMode,
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds
    ) {
    }

    public void validate(List<ChatTurn> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "对话消息不能为空");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "历史消息超限（最多 " + MAX_MESSAGES + " 条）");
        }
        for (ChatTurn turn : messages) {
            Object content = turn.content();
            if (content == null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "消息内容不能为空");
            }
            if (content instanceof String text) {
                if (text.isBlank() || text.length() > MAX_CONTENT) {
                    throw new BizException(CommonErrorCode.PARAM_INVALID,
                            "消息内容为空或超长（≤" + MAX_CONTENT + " 字）");
                }
            } else if (content instanceof List<?> blocks) {
                // 多模态内容块：只允许 text / image_url，且 image_url 必须是 http(s)
                // （拒绝 file: 与巨型 base64 data: —— 后者会把请求体打成几十 MB）
                if (blocks.isEmpty() || blocks.size() > 8) {
                    throw new BizException(CommonErrorCode.PARAM_INVALID,
                            "内容块数量须在 1~8 之间");
                }
                for (Object blockObj : blocks) {
                    if (!(blockObj instanceof Map<?, ?> block)) {
                        throw new BizException(CommonErrorCode.PARAM_INVALID, "内容块格式非法");
                    }
                    Object type = block.get("type");
                    if ("text".equals(type)) {
                        Object text = block.get("text");
                        if (!(text instanceof String s) || s.isBlank()) {
                            throw new BizException(CommonErrorCode.PARAM_INVALID, "text 块不能为空");
                        }
                    } else if ("image_url".equals(type)) {
                        if (!(block.get("image_url") instanceof Map<?, ?> image)) {
                            throw new BizException(CommonErrorCode.PARAM_INVALID,
                                    "image_url 块缺少 image_url 对象");
                        }
                        Object url = image.get("url");
                        if (!(url instanceof String u) || u.isBlank()) {
                            throw new BizException(CommonErrorCode.PARAM_INVALID,
                                    "图片 URL 不能为空");
                        }
                        // 两种形态都放行：http(s) URL（图床图）与 data URL
                        // （前端上传文件转 base64 —— 标准 OpenAI 协议形态）。
                        // data: 必须是白名单图片类型；总量限制防巨型 base64 打爆请求体。
                        boolean httpUrl = u.startsWith("http://") || u.startsWith("https://");
                        boolean dataImage = u.matches("data:image/(png|jpeg|jpg|webp|gif);base64,[A-Za-z0-9+/=]+");
                        if (!httpUrl && !dataImage) {
                            throw new BizException(CommonErrorCode.PARAM_INVALID,
                                    "图片仅支持 http(s) URL 或 png/jpeg/webp/gif 的 base64 data URL");
                        }
                        if (u.length() > 3_000_000) {
                            throw new BizException(CommonErrorCode.PARAM_INVALID,
                                    "图片过大（base64 后超过 3MB），请压缩后重试");
                        }
                    } else {
                        throw new BizException(CommonErrorCode.PARAM_INVALID,
                                "不支持的内容块类型：" + type);
                    }
                }
            } else {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "content 类型非法");
            }
        }
    }

    /** 提取一条消息里的纯文本（数组形态时拼 text 块），供演示回答引用提问。 */
    public String textOf(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> blocks) {
            StringBuilder text = new StringBuilder();
            for (Object blockObj : blocks) {
                if (blockObj instanceof Map<?, ?> block && "text".equals(block.get("type"))) {
                    text.append(block.get("text"));
                }
            }
            return text.toString();
        }
        return "";
    }

    /** system 提示词 = 人设 + 注入的真实平台上下文。 */
    public String systemPrompt(String platformContext) {
        return SYSTEM_PROMPT + "\n\n[平台实时上下文]\n" + platformContext;
    }

    /**
     * 上下文注入点：当前租户的工单概况（真实数据库）。
     * 换成检索/向量召回只是换这一段的取数来源。
     *
     * <p>⚠️ 必须在<b>请求线程</b>调用并传快照：流式发送跑在虚拟线程上，
     * ThreadLocal（TenantContext）不会跟随 —— 在虚拟线程里取租户
     * 会拿到空（实测返回"上下文暂不可用"）。凡是"请求线程取、
     * 后台线程用"的数据都要走快照，这是异步化改造的通用陷阱。
     */
    public String platformContextSnapshot() {
        try {
            List<OpsTicketPort.OpsTicket> tickets = ticketService.list();
            long pending = tickets.stream()
                    .filter(t -> "PENDING".equals(t.state()) || "ESCALATED".equals(t.state())).count();
            long claimed = tickets.stream().filter(t -> "CLAIMED".equals(t.state())).count();
            long done = tickets.stream().filter(t -> "DONE".equals(t.state())).count();
            StringBuilder context = new StringBuilder();
            context.append("工单概况：待处理 ").append(pending).append("，处理中 ")
                    .append(claimed).append("，已完成 ").append(done).append("。最近工单：");
            int limit = Math.min(3, tickets.size());
            for (int i = 0; i < limit; i++) {
                OpsTicketPort.OpsTicket ticket = tickets.get(i);
                context.append("\n- #").append(ticket.id()).append(' ')
                        .append(ticket.title()).append('（').append(ticket.state()).append('）');
            }
            return context.toString();
        } catch (Exception ex) {
            // 上下文获取失败不阻断对话：没有上下文的回答依然可用
            return "（工单上下文暂不可用）";
        }
    }

    /**
     * 演示模式的确定性回答（无 Key 环境的降级）。
     * 回答里嵌入真实工单概况 —— 降级的是模型，不是数据。
     * {@code context} 是请求线程预取的快照（见 platformContextSnapshot）。
     */
    public String demoAnswer(String question, String context) {
        return """
                【演示模式】当前服务端未配置 AI Key（webadmin.ai.api-key），以下回答由 \
                内置编排器生成，数据来自数据库。

                关于「%s」：

                %s

                1. 本页链路已完整：鉴权 → 上下文注入（上方工单概况来自数据库）→ \
                限流 → 流式转发；
                2. 配置 DeepSeek Key 后，同一请求会转发到 /chat/completions（stream=true），\
                由真实模型回答 —— 前端无需任何改动；
                3. 架构要点：模型是能力，编排才是产品。
                """.formatted(question.replace('\n', ' ').trim(), context);
    }
}
