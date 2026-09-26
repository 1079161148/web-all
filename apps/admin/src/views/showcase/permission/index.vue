<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getCurrentUserPermissions } from '@admin/api'
import { NCard, NInputNumber, NSelect, NSpace, NTag, NTimeline, NTimelineItem, hasPermission, feedback } from '@admin/ui'

/**
 * 细粒度权限控制：RBAC + ABAC 混合模型（本系统**已上线**能力的集中说明）。
 *
 * <h3>本项目的真实权限模型</h3>
 * <ol>
 *   <li><b>RBAC（角色 → 权限码）</b>：菜单/按钮粒度。权限码形如
 *       `iam:user:delete`，由后端菜单表维护，前端 `hasPermission()`
 *       控制显隐，后端 `@PreAuthorize` 独立复核 ——
 *       <b>前端显隐是体验，不是安全边界</b></li>
 *   <li><b>行级数据权限（DataScope）</b>：ALL / DEPT_AND_CHILD / DEPT /
 *       SELF / 自定义部门集。这不是"前端过滤"——是 MyBatis 拦截器
 *       在 SQL 层改写 WHERE，任何绕过前端的调用方式都同样被约束</li>
 *   <li><b>ABAC 式属性条件</b>：真正的 ABAC（按任意属性动态判权）
 *       通过策略表实现。下面用一个可交互的策略引擎演示其思维方式：
 *       审批链由"单据属性"（金额、部门）决定，而不是由角色决定</li>
 * </ol>
 *
 * <p>已在其他模块落地的部分（本页不再重复演示）：
 * 数据权限模拟器（角色页"数据权限预览"）、部门树展开缓存、
 * 刷新令牌轮换与重放保护 —— 见「系统管理」相关页面。
 */

const permissions = ref<Set<string>>(new Set())
const loading = ref(true)

onMounted(async () => {
  try {
    const codes = await getCurrentUserPermissions()
    permissions.value = new Set(codes)
  } catch {
    feedback.error('权限码读取失败')
  } finally {
    loading.value = false
  }
})

const permissionList = computed(() => [...permissions.value].sort())

/** 三枚按钮分别对应不同权限码：当前账号缺哪个码，哪个按钮就不可见
 *  （渲染层由 hasPermission 判定 —— 与 ProTable 列/行操作是同一套机制）。 */
const demoButtons = [
  { code: 'iam:user:create', label: '新建用户（iam:user:create）', type: 'primary' as const },
  { code: 'iam:user:delete', label: '删除用户（iam:user:delete）', type: 'error' as const },
  { code: 'showcase:not-exist', label: '虚构权限码（必然不可见）', type: 'default' as const }
]

// ---------------------------------------------------------------------
// ABAC 策略引擎演示：审批链由单据属性决定
// ---------------------------------------------------------------------

interface PolicyRule {
  attr: 'amount' | 'dept'
  op: '>' | '==' | '<='
  value: number | string
  effect: string
}

/** 规则按顺序评估，全部命中条件叠加生效 —— 这就是 ABAC 的核心思维：
 *  决策依据是"属性谓词"，角色只是属性的载体之一。 */
const policies: PolicyRule[] = [
  { attr: 'amount', op: '>', value: 5000, effect: '需要二级审批（总监）' },
  { attr: 'amount', op: '>', value: 50000, effect: '需要三级审批（VP）' },
  { attr: 'dept', op: '==', value: '财务部', effect: '免二级审批（财务自有通道）' }
]

const abacForm = reactive({ amount: 8000, dept: '研发部' })

const deptOptions = ['研发部', '财务部', '市场部', '运维部'].map((dept) => ({ label: dept, value: dept }))

const abacResult = computed(() => {
  const effects: string[] = ['一级审批（直属主管）']
  for (const rule of policies) {
    const actual = abacForm[rule.attr]
    const hit =
      rule.op === '>' && typeof rule.value === 'number'
        ? (actual as number) > rule.value
        : rule.op === '==' && actual === rule.value
    if (hit) {
      effects.push(rule.effect)
    }
  }
  // 财务部免二级：从结果里移除（ demonstrating 负向效果同样可表达）
  const exempt = policies.some(
    (rule) => rule.attr === 'dept' && rule.effect.includes('免二级审批') && abacForm.dept === rule.value
  )
  const chain = exempt
    ? effects.filter((effect) => !effect.startsWith('需要二级'))
    : effects
  return { chain, hitCount: chain.length - 1 }
})
</script>

<template>
  <div class="perm">
    <div class="perm__layout">
      <NCard title="RBAC：按钮级权限（当前账号权限码）" :bordered="false" class="perm__card">
        <div class="perm__codes">
          <NTag v-for="code in permissionList" :key="code" size="small" :bordered="false">
            {{ code }}
          </NTag>
          <span v-if="!loading && permissionList.length === 0" class="perm__hint">（无权限码）</span>
        </div>

        <p class="perm__subtitle">受权限码控制的操作按钮：</p>
        <NSpace>
          <NButton
            v-for="button in demoButtons"
            v-show="hasPermission(button.code)"
            :key="button.code"
            size="small"
            :type="button.type"
            @click="feedback.success(`已触发 ${button.code}`)"
          >
            {{ button.label }}
          </NButton>
        </NSpace>
        <p class="perm__hint">
          按钮 v-show 由 hasPermission 判定；同一权限码在后端 @PreAuthorize 独立复核。
          修改任一角色的权限码后回到本页，可看到按钮集变化。
        </p>
      </NCard>

      <NCard title="ABAC：属性条件策略引擎（审批链示例）" :bordered="false" class="perm__card">
        <div class="perm__field">
          <label>单据金额（元）</label>
          <NInputNumber v-model:value="abacForm.amount" :min="0" :step="1000" />
        </div>
        <div class="perm__field">
          <label>申请部门</label>
          <NSelect v-model:value="abacForm.dept" :options="deptOptions" />
        </div>

        <div class="perm__policies">
          <NTag v-for="rule in policies" :key="rule.effect" size="small" :bordered="false">
            {{ rule.attr }} {{ rule.op }} {{ rule.value }} → {{ rule.effect }}
          </NTag>
        </div>

        <NTimeline class="perm__chain">
          <NTimelineItem
            v-for="step in abacResult.chain"
            :key="step"
            :title="step"
            :type="step.startsWith('需要') ? 'warning' : 'default'"
          />
        </NTimeline>
        <p class="perm__hint">
          当前命中 {{ abacResult.hitCount }} 条策略。把金额调到 60000 或部门切到财务部，
          观察审批链变化 —— 规则是数据，不是代码。
        </p>
      </NCard>
    </div>

    <NCard title="行级数据权限（已落地）" :bordered="false" class="perm__card">
      <p class="perm__hint">
        五档数据范围（ALL / 本部门及以下 / 本部门 / 仅本人 / 自定义部门集）由
        MyBatis 拦截器在 SQL 层施加条件，count 与 select 同源改写；
        部门树带缓存与提交后失效。验证工具：「系统管理 → 角色管理 → 数据权限预览」，
        可以任意角色视角预览可见数据条数与生效部门 —— 预览与真实列表出自同一拦截器。
      </p>
    </NCard>
  </div>
</template>

<style scoped>
.perm__layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 1100px) {
  .perm__layout {
    grid-template-columns: 1fr;
  }
}

.perm__card {
  margin-bottom: 16px;
}

.perm__codes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
  margin-bottom: 12px;
}

.perm__subtitle {
  margin: 0 0 8px;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-primary, #1f2329);
}

.perm__hint {
  margin: 8px 0 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}

.perm__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.perm__field > label {
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.perm__policies {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
  margin-bottom: 12px;
}

.perm__chain {
  margin-top: 4px;
}
</style>
