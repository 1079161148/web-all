package com.webadmin.infrastructure.security;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * 图形验证码生成器（纯 Java2D，不引入第三方验证码库）。
 *
 * <h3>字符集为什么是这 32 个字符</h3>
 * {@code 23456789ABCDEFGHJKLMNPQRSTUVWXYZ} —— 去掉了 {@code 0 O 1 I L}：
 * 它们在多数无衬线字体里字形几乎相同，用户"看对了却输错了"，
 * 表现为"验证码老是错"，最后被当成系统毛病。字符数仍是 32（5 bit/字符），
 * 4 位长度对应 2^20 ≈ 100 万组合，配合 3 分钟有效期与一次性消费足够。
 *
 * <h3>抗自动识别手段（按性价比排序）</h3>
 * <ol>
 *   <li><b>字符随机旋转 + 随机基线偏移</b>：破坏"整齐排列"这一最强先验，
 *       OCR 需要为每个字符做角度回归</li>
 *   <li><b>随机颜色 + 随机字号</b>：让阈值二值化难以一刀切</li>
 *   <li><b>干扰线穿过字符</b>：破坏连通域，分割字符变难</li>
 *   <li><b>噪点</b>：提高背景噪声，抑制干净的模板匹配</li>
 * </ol>
 * 目标是"把自动化成本抬到不划算"，而不是"理论上不可破解" ——
 * 图形验证码从来不是密码学防线，它的价值在于挡住无脑脚本。
 * 真正的防线是后面的 {@code @RateLimit} 与失败锁定。
 *
 * <h3>headless 兼容</h3>
 * Spring Boot 默认 {@code java.awt.headless=true}，{@link BufferedImage} 与
 * {@link Graphics2D} 在无显示设备的环境下完全可用 —— 服务端渲染不依赖 GUI。
 * 字体选择走 JDK 内置逻辑字体（{@code SansSerif}），不假设系统装了某个具体字体。
 */
@Component
public class CaptchaImageGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int DEFAULT_LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int NOISE_LINES = 5;
    private static final int NOISE_DOTS = 60;

    /** SecureRandom 线程安全，静态共享即可（实例化有熵池开销）。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成答案文本（大写；比对时统一转小写）。 */
    public String generateText() {
        return generateText(DEFAULT_LENGTH);
    }

    public String generateText(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    /** 把答案渲染成 PNG 并做 Base64（可直接放进 {@code <img src="data:image/png;base64,...">}）。 */
    public String renderBase64(String text) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 背景：随机浅色（不用纯白 —— 纯白背景让阈值分割更容易）
            g.setColor(new Color(240 + RANDOM.nextInt(16), 240 + RANDOM.nextInt(16),
                    240 + RANDOM.nextInt(16)));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线：穿过整个画布，颜色取中等深度，避免被"亮度阈值"一键抹掉
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < NOISE_LINES; i++) {
                g.setColor(randomColor(120, 200));
                g.drawLine(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT),
                        RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT));
            }

            // 噪点
            for (int i = 0; i < NOISE_DOTS; i++) {
                g.setColor(randomColor(120, 220));
                int size = 1 + RANDOM.nextInt(2);
                g.fillRect(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), size, size);
            }

            // 字符：逐个绘制并做小幅旋转 + 基线抖动
            int slot = WIDTH / (text.length() + 1);
            for (int i = 0; i < text.length(); i++) {
                int fontSize = 24 + RANDOM.nextInt(5);
                g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
                g.setColor(randomColor(20, 110));

                AffineTransform saved = g.getTransform();
                double angle = (RANDOM.nextDouble() - 0.5) * 0.6; // ±17°
                double x = slot * (i + 1) - fontSize * 0.35;
                double y = HEIGHT * 0.72 + (RANDOM.nextDouble() - 0.5) * 6;
                g.rotate(angle, x, y);
                g.drawString(String.valueOf(text.charAt(i)), (float) x, (float) y);
                g.setTransform(saved);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException ex) {
            // 内存图片写 ByteArrayOutputStream 不会失败；真发生则属环境异常，快速失败
            throw new IllegalStateException("验证码图片生成失败", ex);
        }
    }

    /** 取偏深颜色：验证码字符必须在浅底上清晰可读（可用性优先于抗识别）。 */
    private static Color randomColor(int min, int max) {
        int range = max - min;
        return new Color(min + RANDOM.nextInt(range), min + RANDOM.nextInt(range),
                min + RANDOM.nextInt(range));
    }
}
