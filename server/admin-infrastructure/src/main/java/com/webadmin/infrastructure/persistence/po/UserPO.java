package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.webadmin.domain.iam.model.user.UserStatus;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 用户持久化对象（字段与 {@code V1.0.1__create_iam_tables.sql} 的列一一对应）。 */
@Getter
@Setter
@TableName("iam_user")
public class UserPO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    /*
      ⚠️⚠️ 下面这些字段必须显式声明 updateStrategy = ALWAYS，否则它们的"清空"操作会静默失效。

      【问题本质】
      MyBatis-Plus 的 updateById 默认按 NOT_NULL 策略生成 SET 子句 ——
      **值为 null 的字段会被整个跳过**，不写进 SQL。
      于是"把字段设为 null"这个动作在数据库里等于什么都没做。

      【实测后果（不是理论推演）】
      User.unlock() / resetPassword() 会把 lockUntil 置为 null。
      由于它被跳过，数据库里的 lock_until 仍是那个未来时间戳。
      而 User.assertCanLogin() 会**先**检查 lockUntil、再看 status，
      于是出现极其违背直觉的现象：
        管理员点"解锁" → status 变成 ACTIVE、界面显示已解锁
                      → 用户依然被拒绝："账号已被锁定，请 29 分钟后重试"
      这个 bug 是在集成测试里稳定复现后才发现根因的 ——
      光看日志与接口返回，会一直以为是"解锁逻辑没生效"。

      【判定规则】
      任何**允许被业务逻辑置回 null** 的字段，都必须标 ALWAYS。
      纯追加型的字段（create_time、create_by）不需要。
      这里没有改用全局 update-strategy=always，是因为那会让
      "用新建 PO 做更新"的地方（如 MenuRepositoryImpl）把未填充的列一并写成 null，
      造成真正的数据丢失。**局部精确 > 全局粗暴。**
    */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long deptId;

    private String username;
    private String nickname;
    /** BCrypt 哈希。**不要**在日志里打印本字段（值对象已覆盖 toString 防泄露）。 */
    private String password;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String email;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String phone;

    private Integer sex;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatar;

    /** MyBatis 默认按 {@code name()} 存取，与数据库中的字符串列天然对应。 */
    private UserStatus status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String loginIp;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant loginTime;

    private Integer failCount;

    /** ★ 最关键的受害者：整段 ALWAYS 说明的原因就是它。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant lockUntil;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

    @TableLogic
    private Long delFlag;

    @Version
    private Integer version;

    /**
     * 部门名称（<b>非本表字段</b>，由列表查询的 LEFT JOIN 带出）。
     *
     * <h3>为什么用 {@code exist = false} 而不是另建一个 Row 类</h3>
     * 这个字段只服务于读侧（列表展示"所属部门"）。
     * 另建 {@code UserRow} 会让"PO 与 Row 的字段映射"多出一份需要同步的代码，
     * 而它们的差异仅仅是<b>多一个字段</b>。
     *
     * <p>{@code @TableField(exist = false)} 明确告诉 MyBatis-Plus
     * "这不是本表的列"，因此 INSERT/UPDATE 不会带上它 ——
     * 这一点由 MP 保证，而不是靠开发者记得别把它写进 SQL。
     *
     * <p>代价是这个字段在写侧是 null（无人填充）。可接受：
     * 写侧不读它，读侧不写它，二者边界清晰。
     */
    @TableField(exist = false)
    private String deptName;
}
