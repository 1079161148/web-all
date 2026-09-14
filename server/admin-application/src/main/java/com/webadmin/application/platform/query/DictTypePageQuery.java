package com.webadmin.application.platform.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 字典类型分页查询条件。 */
@Getter
@Setter
public class DictTypePageQuery extends PageQuery {

    /** 字典名称，模糊匹配。 */
    private String dictName;

    /** 字典类型编码，模糊匹配。 */
    private String dictType;

    private String status;
}
