package com.webadmin.application.platform.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 字典项分页查询条件。 */
@Getter
@Setter
public class DictDataPageQuery extends PageQuery {

    /** 所属字典类型编码。<b>几乎总是必填</b> —— 不限定类型的字典项列表没有使用场景。 */
    private String dictType;

    /** 标签，模糊匹配。 */
    private String dictLabel;

    private String status;
}
