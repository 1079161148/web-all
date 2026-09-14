package com.webadmin.application.org;

import com.webadmin.application.org.port.PostPort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.iam.IamErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 岗位应用服务（L1 支撑域，事务脚本）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostAppService {

    private final PostPort postPort;

    @Transactional
    public Long create(String postCode, String postName, Integer sort,
                       String status, String remark) {
        if (postPort.codeExists(postCode, null)) {
            throw new BizException(IamErrorCode.POST_CODE_DUPLICATED,
                    "岗位编码「" + postCode + "」已存在");
        }
        Long id = postPort.insert(postCode, postName, sort, status, remark);
        log.info("创建岗位 id={} code={} name={}", id, postCode, postName);
        return id;
    }

    @Transactional
    public void update(long id, String postCode, String postName, Integer sort,
                       String status, String remark) {
        postPort.findById(id).orElseThrow(() -> new BizException(
                IamErrorCode.POST_NOT_FOUND, "岗位不存在"));
        if (postPort.codeExists(postCode, id)) {
            throw new BizException(IamErrorCode.POST_CODE_DUPLICATED,
                    "岗位编码「" + postCode + "」已存在");
        }
        postPort.update(id, postCode, postName, sort, status, remark);
    }

    @Transactional
    public void delete(long id) {
        postPort.findById(id).orElseThrow(() -> new BizException(
                IamErrorCode.POST_NOT_FOUND, "岗位不存在"));

        // 与角色/部门一致的策略：仍被引用时拒绝删除并给出具体数量。
        // 岗位不像角色那样影响鉴权，但"静默解绑"依然会让某人的岗位信息凭空消失，
        // 而"谁的岗位什么时候没的"是无法追溯的。
        long userCount = postPort.countUsers(id);
        if (userCount > 0) {
            throw new BizException(IamErrorCode.POST_NOT_FOUND,
                    "该岗位仍分配给 " + userCount + " 名员工，请先解除关联");
        }
        postPort.delete(id);
        log.info("删除岗位 id={}", id);
    }
}
