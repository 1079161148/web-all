package com.webadmin.application.platform.port;

import com.webadmin.application.platform.dto.DictDataDTO;
import com.webadmin.application.platform.dto.DictTypeDTO;
import com.webadmin.application.platform.query.DictDataPageQuery;
import com.webadmin.application.platform.query.DictTypePageQuery;
import com.webadmin.common.api.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * 字典读取端口。
 *
 * <h3>⚠️ 这个端口的实现必须自己实现「两级 fallback」</h3>
 * {@code plt_dict_type} / {@code plt_dict_data} 在租户拦截器的忽略名单里
 * （因为它们需要读到 {@code tenant_id = 0} 的平台默认行）。
 * 换句话说，<b>租户隔离在这两张表上没有被自动施加</b>，
 * 责任转移到了实现类：必须显式写 {@code tenant_id IN (0, ?)}。
 *
 * <p>如果实现里漏了这个条件，后果是<b>跨租户数据泄露</b>（能看到别的租户的字典覆盖）。
 * 如果写成 {@code tenant_id = ?}，后果是"平台默认值全部消失"。
 * 这两种错误都不会报错，只会表现为数据不对。
 * <b>这是本模块最需要测试覆盖的一处</b>（见 {@code DictQueryPortIT}）。
 */
public interface DictQueryPort {

    PageResult<DictTypeDTO> pageTypes(DictTypePageQuery query);

    Optional<DictTypeDTO> findTypeById(long id);

    PageResult<DictDataDTO> pageData(DictDataPageQuery query);

    /**
     * 按类型取字典项（<b>前端 useDict 的唯一入口</b>）。
     *
     * <p>不分页：一个字典类型的项数在几个到几十个之间。分页只会让下拉框难用。
     *
     * <p>合并规则：同 {@code dictValue} 以<b>租户级覆盖平台级</b>；
     * 结果按 {@code sort} 升序。
     */
    List<DictDataDTO> listByType(String dictType);

    Optional<DictDataDTO> findDataById(long id);
}
