package com.webadmin.infrastructure.fieldpermission;

import com.webadmin.application.iam.port.PermissionCachePort.CachedPermissions;
import com.webadmin.application.iam.security.PermissionResolver;
import com.webadmin.application.security.CurrentUser;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import com.webadmin.common.fieldpermission.FieldPermission;
import com.webadmin.common.fieldpermission.Masker;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 字段级权限脱敏器：读 {@link FieldPermission} 注解，按当前用户权限产出脱敏后的对象。
 *
 * <h3>为什么做在「响应装配」而不是「JSON 序列化」</h3>
 * 设计文档 §7.4 的示例是 Jackson 序列化器。但实际实现必须换一种方式，原因有二：
 * <ol>
 *   <li><b>Spring Boot 4 已切到 Jackson 3</b>（{@code tools.jackson.core:3.1.5}），
 *       而 Jackson 3 <b>移除了 {@code ContextualSerializer}</b> ——
 *       序列化器再也拿不到"自己正被用在哪个属性上"的信息，
 *       也就无法读取属性上的注解。旧写法在新版本上根本无法实现</li>
 *   <li>更本质的原因：<b>脱敏不只服务于 JSON</b>。设计文档明确要求
 *       "导出 Excel 与日志复用同一套策略"。做在序列化层，导出路径就要再实现一遍，
 *       而这正是「页面打码、导出泄露」这个经典漏洞的成因</li>
 * </ol>
 * 因此改为：在响应写出之前，对返回对象做一次**结构化脱敏**。
 * 所有出口（JSON 响应、Excel 导出、日志）只要拿着同一批 DTO，
 * 就自动继承同一套策略。
 *
 * <h3>为什么能重建 record</h3>
 * {@code R} / {@code PageResult} / 各业务 DTO 全部是 record，
 * 具备「规范化构造器」这一稳定重建入口。因此脱敏不是"改字段"（record 不可变），
 * 而是<b>用同样的组件顺序构造一个新实例</b>。这比反射改字段更安全：
 * record 的不变性没有被破坏，也不需要处理 {@code final} 字段的可见性问题。
 *
 * <h3>深度限制</h3>
 * 递归处理嵌套结构（{@code R<PageResult<List<UserResponse>>>}）时设了最大深度。
 * 这不是防"正常嵌套太深"，而是防<b>循环引用</b>——
 * 一旦将来某个 DTO 出现自引用且忘了加 {@code @JsonIgnore}，
 * 没有深度限制就会在本类里先 StackOverflow，而错误位置离根因很远。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldMasker {

    /** 最大递归深度。正常响应结构不超过 4 层，10 层已有充足余量。 */
    private static final int MAX_DEPTH = 10;

    /** 规范化构造器缓存。脱敏在每个响应上执行，反射查找不能重复做。 */
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    /** 无注解字段的快速判定缓存，避免对每个组件都做一次 getAnnotation。 */
    private static final Map<Class<?>, Boolean> HAS_ANNOTATION_CACHE = new ConcurrentHashMap<>();

    private final CurrentUserPort currentUserPort;
    private final PermissionResolver permissionResolver;

    /**
     * 对任意响应对象做脱敏。
     *
     * <p>返回新对象；<b>没有需要脱敏的字段时原样返回</b>，
     * 避免为绝大多数不含敏感字段的接口白白创建新实例。
     */
    public Object mask(Object body) {
        if (body == null) {
            return null;
        }
        return maskInternal(body, resolveRoles(), 0);
    }

    // ------------------------------------------------------------------
    // 递归实现
    // ------------------------------------------------------------------

    private Object maskInternal(Object value, MaskContext context, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            if (depth > MAX_DEPTH) {
                log.warn("字段脱敏达到最大深度 {}，已停止向下递归。可能存在循环引用", MAX_DEPTH);
            }
            return value;
        }

        if (value instanceof Optional<?> optional) {
            return optional.map(inner -> maskInternal(inner, context, depth + 1));
        }
        if (value instanceof List<?> list) {
            return maskList(list, context, depth);
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(item -> maskInternal(item, context, depth + 1))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }

        // 先处理"需要重建"的容器类型，再做通用 record 处理
        if (value instanceof R<?> wrapper) {
            return new R<>(wrapper.code(), wrapper.msg(),
                    maskInternal(wrapper.data(), context, depth + 1));
        }
        if (value instanceof PageResult<?> page) {
            return new PageResult<>(
                    (List<Object>) maskList(page.records(), context, depth),
                    page.total(), page.page(), page.size());
        }

        Class<?> type = value.getClass();
        if (!type.isRecord() || !hasAnnotatedComponent(type)) {
            // 不是 record、或不含任何 @FieldPermission —— 无需处理。
            // 这样绝大多数响应对象直接返回，零额外开销。
            return value;
        }
        return rebuildRecord(value, type, context);
    }

    private List<Object> maskList(List<?> list, MaskContext context, int depth) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(maskInternal(item, context, depth + 1));
        }
        return result;
    }

    /** 用脱敏后的组件值重建 record。 */
    private Object rebuildRecord(Object value, Class<?> type, MaskContext context) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        boolean changed = false;

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            Object original = readComponent(component, value);
            Object masked = original;

            FieldPermission annotation = component.getAnnotation(FieldPermission.class);
            if (annotation != null && original instanceof String text) {
                masked = applyPolicy(annotation, text, context);
            } else if (annotation != null && original != null) {
                // 注解标在非字符串上：不做脱敏，但记录一条提示。
                // 静默忽略更危险 —— 开发者会以为"标了就生效了"。
                log.debug("字段 {}#{} 标了 @FieldPermission 但不是 String 类型（{}），已跳过脱敏。"
                                + "如需脱敏请改为 String 或在装配层处理",
                        type.getSimpleName(), component.getName(), original.getClass().getSimpleName());
            } else if (original != null) {
                masked = maskInternal(original, context, 1);
            }

            args[i] = masked;
            if (!Objects.equals(original, masked)) {
                changed = true;
            }
        }

        if (!changed) {
            return value;
        }
        return construct(type, components, args);
    }

    /**
     * 应用字段权限策略。
     *
     * <p>顺序（与 {@link FieldPermission} 的文档一致，不可调换）：
     * 超管直通 → {@code hiddenFor} 命中即隐藏 → {@code visibleFor} 命中即原值 → 否则脱敏。
     * 把"隐藏"放在"可见"之前，是为了避免同时配置两者时因判定顺序而绕过隐藏。
     */
    private String applyPolicy(FieldPermission annotation, String value, MaskContext context) {
        if (value.isEmpty()) {
            return annotation.emptyAsBlank() ? "" : value;
        }
        // 超管直通：超管若也要脱敏，就没人能核对数据正确性了
        if (context.superAdmin()) {
            return value;
        }
        if (containsAny(annotation.hiddenFor(), context.roleKeys())) {
            return null;
        }
        if (annotation.visibleFor().length > 0
                && containsAny(annotation.visibleFor(), context.roleKeys())) {
            return value;
        }
        return Masker.mask(value, annotation.maskStrategy());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private MaskContext resolveRoles() {
        Optional<CurrentUser> current = currentUserPort.currentUser();
        if (current.isEmpty()) {
            // 未登录（如公开接口）：按"无任何角色 + 非超管"处理 ——
            // 这是最严格的一档，所有敏感字段都会走脱敏分支。
            // fail-closed：宁可让公开接口少返回数据，也不能因为"没拿到身份"就原样返回。
            return new MaskContext(Set.of(), false);
        }
        CurrentUser user = current.get();
        CachedPermissions permissions =
                permissionResolver.resolve(user.tenantId(), user.userId());
        return new MaskContext(permissions.roleKeys(), permissions.superAdmin());
    }

    private static boolean containsAny(String[] candidates, Set<String> roles) {
        if (candidates == null || candidates.length == 0) {
            return false;
        }
        for (String candidate : candidates) {
            if (roles.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotatedComponent(Class<?> type) {
        return HAS_ANNOTATION_CACHE.computeIfAbsent(type, key ->
                Arrays.stream(key.getRecordComponents())
                        .anyMatch(component -> component.getAnnotation(FieldPermission.class) != null));
    }

    private static Object readComponent(RecordComponent component, Object target) {
        try {
            return component.getAccessor().invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "读取 record 组件失败：" + component.getName(), ex);
        }
    }

    private static Object construct(Class<?> type, RecordComponent[] components, Object[] args) {
        Constructor<?> constructor = CONSTRUCTOR_CACHE.computeIfAbsent(type, key -> {
            Class<?>[] parameterTypes = Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class[]::new);
            try {
                Constructor<?> found = key.getDeclaredConstructor(parameterTypes);
                found.setAccessible(true);
                return found;
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException(
                        "无法定位 record 的规范化构造器：" + key.getName(), ex);
            }
        });
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("重建 record 失败：" + type.getName(), ex);
        }
    }

    /** 当前用户角色快照（一次解析，供整个对象图复用）。 */
    private record MaskContext(Set<String> roleKeys, boolean superAdmin) {
    }
}
