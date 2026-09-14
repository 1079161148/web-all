package com.webadmin.application.iam.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户分页查询条件。
 *
 * <p>这是<b>应用层的读侧查询对象</b>，与接口层的请求对象分开：
 * 请求对象承载 HTTP 关注点（参数绑定、校验注解），查询对象承载业务查询语义。
 * 二者字段高度重合，但合并会让应用层依赖 jakarta.validation 与 springdoc ——
 * <b>应用层不该为了省一个类而引入 Web 框架的注解。</b>
 */
@Getter
@Setter
public class UserPageQuery extends PageQuery {

    /** 账号，模糊匹配。 */
    private String username;

    /** 昵称/姓名，模糊匹配。 */
    private String nickname;

    /** 手机号，模糊匹配。 */
    private String phone;

    /** 精确匹配的部门 ID。 */
    private Long deptId;

    /** {@code true} 时连同子部门一起查（对应部门树点选"包含下级"）。 */
    private Boolean includeSubDept;

    /** 状态精确匹配。 */
    private String status;

    /** 角色 ID：筛选"拥有该角色的用户"。 */
    private Long roleId;
}
