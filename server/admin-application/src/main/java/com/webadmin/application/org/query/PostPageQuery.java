package com.webadmin.application.org.query;

import com.webadmin.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 岗位分页查询条件。 */
@Getter
@Setter
public class PostPageQuery extends PageQuery {

    /** 岗位编码，模糊匹配。 */
    private String postCode;

    /** 岗位名称，模糊匹配。 */
    private String postName;

    private String status;
}
