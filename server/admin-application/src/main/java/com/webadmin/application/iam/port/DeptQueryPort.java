package com.webadmin.application.iam.port;

import com.webadmin.application.iam.dto.DeptDTO;
import java.util.List;
import java.util.Optional;

/** 部门读取端口（CQRS 读侧）。返回扁平列表，前端建树。 */
public interface DeptQueryPort {

    List<DeptDTO> listAll();

    Optional<DeptDTO> findById(long deptId);
}
