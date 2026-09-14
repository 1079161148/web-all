package com.webadmin.application.platform.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 参数配置分页查询条件。 */
@Getter
@Setter
public class ConfigPageQuery extends PageQuery {

    /** 参数名称，模糊匹配。 */
    private String configName;

    /** 参数键，模糊匹配。 */
    private String configKey;
}
