package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.org.dto.PostDTO;
import com.webadmin.application.org.port.PostPort;
import com.webadmin.application.org.query.PostPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.PostMapper;
import com.webadmin.infrastructure.persistence.mapper.UserPostMapper;
import com.webadmin.infrastructure.persistence.po.PostPO;
import com.webadmin.infrastructure.persistence.po.UserPostPO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 岗位端口实现。 */
@Repository
@RequiredArgsConstructor
public class PostPortImpl implements PostPort {

    private final PostMapper postMapper;
    private final UserPostMapper userPostMapper;

    @Override
    public PageResult<PostDTO> page(PostPageQuery query) {
        Page<PostPO> page = postMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<PostPO>()
                        .like(query.getPostCode() != null && !query.getPostCode().isBlank(),
                                PostPO::getPostCode, query.getPostCode())
                        .like(query.getPostName() != null && !query.getPostName().isBlank(),
                                PostPO::getPostName, query.getPostName())
                        .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                                PostPO::getStatus, query.getStatus())
                        .orderByAsc(PostPO::getSort)
                        .orderByAsc(PostPO::getId));

        // 一次分组统计取当页所有岗位的用户数，避免 N+1
        List<Long> postIds = page.getRecords().stream().map(PostPO::getId).toList();
        Map<Long, Integer> userCounts = postIds.isEmpty() ? Map.of()
                : userPostMapper.selectList(new LambdaQueryWrapper<UserPostPO>()
                        .select(UserPostPO::getPostId)
                        .in(UserPostPO::getPostId, postIds))
                .stream()
                .collect(Collectors.groupingBy(UserPostPO::getPostId,
                        Collectors.summingInt(row -> 1)));

        return PageResult.of(
                page.getRecords().stream()
                        .map(po -> toDTO(po, userCounts.getOrDefault(po.getId(), 0)))
                        .toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<PostDTO> findById(long id) {
        return Optional.ofNullable(postMapper.selectById(id))
                .map(po -> toDTO(po, (int) countUsers(id)));
    }

    @Override
    public List<PostDTO> findUsable() {
        return postMapper.selectList(new LambdaQueryWrapper<PostPO>()
                        .eq(PostPO::getStatus, "ACTIVE")
                        .orderByAsc(PostPO::getSort))
                .stream()
                .map(po -> toDTO(po, 0))
                .toList();
    }

    @Override
    public boolean codeExists(String postCode, Long excludeId) {
        return postMapper.exists(new LambdaQueryWrapper<PostPO>()
                .eq(PostPO::getPostCode, postCode)
                .ne(excludeId != null, PostPO::getId, excludeId));
    }

    @Override
    public Long insert(String postCode, String postName, Integer sort,
                       String status, String remark) {
        PostPO po = new PostPO();
        po.setId(IdWorker.getId());
        // tenant_id 由租户拦截器自动补（org_post 不在忽略名单里）——
        // 这里刻意不设置，从而不存在"忘了填"或"填错租户"的可能
        po.setPostCode(postCode);
        po.setPostName(postName);
        po.setSort(sort == null ? 0 : sort);
        po.setStatus(status == null ? "ACTIVE" : status);
        po.setRemark(remark);
        postMapper.insert(po);
        return po.getId();
    }

    @Override
    public void update(long id, String postCode, String postName, Integer sort,
                       String status, String remark) {
        PostPO po = new PostPO();
        po.setId(id);
        po.setPostCode(postCode);
        po.setPostName(postName);
        po.setSort(sort);
        po.setStatus(status);
        po.setRemark(remark);
        postMapper.updateById(po);
    }

    @Override
    public long countUsers(long postId) {
        Long count = userPostMapper.selectCount(new LambdaQueryWrapper<UserPostPO>()
                .eq(UserPostPO::getPostId, postId));
        return count == null ? 0L : count;
    }

    @Override
    public void delete(long id) {
        postMapper.deleteById(id);
    }

    private PostDTO toDTO(PostPO po, Integer userCount) {
        return new PostDTO(po.getId(), po.getTenantId(), po.getPostCode(), po.getPostName(),
                po.getSort(), po.getStatus(), po.getRemark(), userCount, po.getCreateTime());
    }
}
