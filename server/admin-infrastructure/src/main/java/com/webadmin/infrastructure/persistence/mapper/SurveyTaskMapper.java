package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.survey.query.SurveyTaskPageQuery;
import com.webadmin.common.datascope.DataScope;
import com.webadmin.infrastructure.persistence.po.SurveyTaskPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 调研任务 Mapper。
 *
 * <p>单表 CRUD 继承自 {@link BaseMapper}；<b>列表与下拉用 XML 方法</b> ——
 * 不是为了 JOIN，而是为了能标注 {@link DataScope}（理由见
 * {@code UserMapper.selectUserPage} 的注释：拦截器只能通过
 * {@code mappedStatementId} 反查注解，而 {@code selectPage(wrapper)}
 * 这种调用没有可标注的方法，等于绕过数据权限）。
 *
 * <h3>为什么这两个方法都要施加数据权限</h3>
 * <ul>
 *   <li>{@code selectTaskPage}：任务列表 —— 漏标的后果是"多看到别的部门的任务"，
 *       没有任何报错</li>
 *   <li>{@code selectTaskOptions}：任务下拉（数据采集/报告表单里选"所属任务"）。
 *       它同样必须受约束：否则用户能把采集记录挂到自己<b>看不见</b>的任务上，
 *       数据归属关系与可见范围就互相矛盾了</li>
 * </ul>
 */
@Mapper
public interface SurveyTaskMapper extends BaseMapper<SurveyTaskPO> {

    /**
     * 任务分页查询。
     *
     * <p>{@code deptAlias = "dept_id"} 不带表别名：本查询是单表，
     * 加了别名反而要与 SQL 里写的一致，属于无谓的耦合。
     */
    @DataScope(table = "srvy_task", deptAlias = "dept_id", userAlias = "create_by")
    List<SurveyTaskPO> selectTaskPage(@Param("page") Page<SurveyTaskPO> page,
                                      @Param("query") SurveyTaskPageQuery query);

    /** 任务下拉选项（受数据权限约束，见类注释）。 */
    @DataScope(table = "srvy_task", deptAlias = "dept_id", userAlias = "create_by")
    List<SurveyTaskPO> selectTaskOptions(@Param("limit") int limit);

    /**
     * 统计"当前（模拟）主体可见"的调研任务数 —— 数据权限模拟器用。
     *
     * <p>与 {@code selectTaskPage} 使用同一注解，因此条件与列表查询一致
     * （理由见 {@code UserMapper.countVisibleUsers()}）。
     */
    @DataScope(table = "srvy_task", deptAlias = "dept_id", userAlias = "create_by")
    @Select("SELECT COUNT(*) FROM srvy_task WHERE del_flag = 0")
    long countVisibleTasks();
}
