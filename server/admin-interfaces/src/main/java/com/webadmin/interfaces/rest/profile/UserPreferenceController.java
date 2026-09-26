package com.webadmin.interfaces.rest.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webadmin.application.iam.UserPreferenceService;
import com.webadmin.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户偏好（本人配置的读写）。
 *
 * <h3>值是"任意 JSON"，为什么服务端不定义 schema</h3>
 * 偏好的消费方是<b>前端功能自身</b>（表格列布局、面板开关……），
 * 每个键的内部结构由它的读写两端约定 —— 服务端定义一份全局 schema
 * 意味着每加一种偏好都要改后端，而它得到的只是"这个键是合法 JSON"这条
 * 它本来就不需要知道的信息。服务端守住键格式与大小上限（见
 * {@link UserPreferenceService}），结构问题交给版本化的前端契约。
 */
@Tag(name = "用户偏好", description = "用户级 KV 配置（表格列布局等）")
@RestController
@RequestMapping("/api/v1/profile/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService preferenceService;

    // 本项目未注册 ObjectMapper bean（同 MonitorWebSocketHandler 惯例），直接实例化
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(operationId = "getUserPreference", summary = "读取一项偏好",
            description = "未设置时 data 为 null（区别于'设为空'）。键格式：小写字母/数字/: _ . -，≤64 字符")
    @GetMapping("/{key}")
    public R<JsonNode> get(@PathVariable String key) {
        String raw = preferenceService.find(key);
        if (raw == null) {
            return R.ok(null);
        }
        try {
            return R.ok(objectMapper.readTree(raw));
        } catch (IOException ex) {
            // 值损坏（理论上不会发生）：按"未设置"处理，前端走默认布局
            return R.ok(null);
        }
    }

    @Operation(operationId = "putUserPreference", summary = "写入一项偏好（upsert）",
            description = "请求体为任意 JSON 值；上限 8KB")
    @PutMapping("/{key}")
    public R<Void> put(@PathVariable String key, @RequestBody JsonNode value) {
        preferenceService.save(key, value.toString());
        return R.ok();
    }
}
