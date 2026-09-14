package com.webadmin.common.fieldpermission;

/**
 * 脱敏算法实现（纯函数，无任何框架依赖）。
 *
 * <h3>为什么是纯函数而不是服务</h3>
 * 它不需要任何上下文（当前用户、数据库、配置）—— 输入字符串与策略、输出脱敏串。
 * 做成纯函数后：
 * <ul>
 *   <li>单元测试成本极低（不需要起 Spring、不需要 mock）</li>
 *   <li>可以被任何层复用：响应装配、Excel 导出、日志脱敏</li>
 *   <li>不会因为"想在里面读一下当前用户"而逐渐演变成一个隐式依赖上下文的上帝类</li>
 * </ul>
 * <b>需要上下文的判断（当前用户有没有权限）留在调用方，这里只管"怎么打码"。</b>
 */
public final class Masker {

    private Masker() {
    }

    /**
     * 按策略脱敏。
     *
     * @param raw      原始值，null 或空串原样返回（空值不脱敏，避免把 null 变成 "***" 后
     *                 前端无法区分"没有值"与"被脱敏"）
     * @param strategy 策略
     * @return 脱敏后的值
     */
    public static String mask(String raw, MaskStrategy strategy) {
        if (raw == null || raw.isEmpty() || strategy == null || strategy == MaskStrategy.NONE) {
            return raw;
        }
        // 统一按 Code Point 处理：emoji / 生僻字由代理对构成，
        // 按 char 截断会产生半个字符（乱码）。中文姓名、国际化场景都会踩到。
        int[] points = raw.codePoints().toArray();
        int length = points.length;

        return switch (strategy) {
            case NONE -> raw;
            case PHONE -> keepHeadTail(points, 3, 4, '*');
            case ID_CARD -> keepHeadTail(points, 6, 4, '*');
            case BANK_CARD -> maskBankCard(points);
            case EMAIL -> maskEmail(raw);
            case NAME -> maskName(points);
            case SENSITIVE -> maskSensitive(points);
        };
    }

    // ------------------------------------------------------------------
    // 各策略实现
    // ------------------------------------------------------------------

    private static String keepHeadTail(int[] points, int head, int tail, char maskChar) {
        int length = points.length;
        // 头尾保留位数之和已覆盖整个串（或更短）时全部打码。
        // 若不这样处理，会出现"脱敏后比原文还长"或"原文全保留"的荒谬结果。
        if (length <= head + tail) {
            return repeat(maskChar, length);
        }
        StringBuilder sb = new StringBuilder(length);
        appendRange(sb, points, 0, head);
        sb.append(repeat(maskChar, length - head - tail));
        appendRange(sb, points, length - tail, length);
        return sb.toString();
    }

    /** 银行卡按 4 位分组展示，中间两组打码：{@code 6222 **** **** 1234}。 */
    private static String maskBankCard(int[] points) {
        int length = points.length;
        if (length <= 8) {
            return repeat('*', length);
        }
        StringBuilder sb = new StringBuilder();
        appendRange(sb, points, 0, 4);
        int maskedCount = length - 8;
        // 按 4 位分组插入空格，保持与真实卡号一致的视觉结构，
        // 便于财务/客服核对而不必数位置
        int remaining = maskedCount;
        while (remaining > 0) {
            int group = Math.min(4, remaining);
            sb.append(' ').append(repeat('*', group));
            remaining -= group;
        }
        sb.append(' ');
        appendRange(sb, points, length - 4, length);
        return sb.toString();
    }

    /**
     * 邮箱：保留首字符与完整域名（{@code a***@example.com}）。
     *
     * <p>保留完整域名是刻意的：判断"是不是公司邮箱"、"发到哪个域"是常见业务需求，
     * 而域名本身的隐私敏感度远低于本地部分。
     */
    private static String maskEmail(String raw) {
        int at = raw.indexOf('@');
        if (at <= 0) {
            // 不是合法邮箱：按通用敏感串处理，而不是原样返回
            // （原样返回等于"格式异常就泄露"，这是不能接受的默许）
            return mask(raw, MaskStrategy.SENSITIVE);
        }
        String local = raw.substring(0, at);
        String domain = raw.substring(at);
        if (local.codePointCount(0, local.length()) <= 1) {
            return "*" + domain;
        }
        int firstEnd = local.offsetByCodePoints(0, 1);
        return local.substring(0, firstEnd) + "***" + domain;
    }

    /** 姓名：保留姓氏，其余打码（{@code 张**}）。 */
    private static String maskName(int[] points) {
        if (points.length <= 1) {
            return repeat('*', points.length);
        }
        StringBuilder sb = new StringBuilder();
        appendRange(sb, points, 0, 1);
        sb.append(repeat('*', points.length - 1));
        return sb.toString();
    }

    /** 通用：保留前 1/4 后 1/4；长度 < 4 全部打码。 */
    private static String maskSensitive(int[] points) {
        int length = points.length;
        if (length < 4) {
            return repeat('*', length);
        }
        int keep = Math.max(1, length / 4);
        return keepHeadTail(points, keep, keep, '*');
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void appendRange(StringBuilder sb, int[] points, int from, int to) {
        for (int i = from; i < to && i < points.length; i++) {
            sb.appendCodePoint(points[i]);
        }
    }

    private static String repeat(char ch, int count) {
        if (count <= 0) {
            return "";
        }
        // JDK 11+ 的 String.repeat 比手工循环更清晰，且对小串有优化
        return String.valueOf(ch).repeat(count);
    }
}
