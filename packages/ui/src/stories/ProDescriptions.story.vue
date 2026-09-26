<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { NCheckbox, NSpace } from 'naive-ui'
import ProDescriptions from '../components/pro/ProDescriptions.vue'
import type { ProDescriptionItem } from '../index'
import { resetStoryPermission, setStoryPermission } from './setup'

/**
 * ProDescriptions 的 story。
 *
 * <h3>⚠️ 权限变体为什么必须"用完还原"</h3>
 * `setPermissionResolver` 注入的是<b>全局</b>接线（见 registry/permission.ts），
 * 而 story 之间共享同一个运行时。若这里的权限开关关掉后不还原，
 * <b>下一个打开的 story 会继续受影响</b> ——
 * 表现就是"某个 story 的按钮莫名其妙不见了"，
 * 而它自己完全没问题。这类跨 story 的串扰极难归因，
 * 所以用 `onBeforeUnmount` 强制还原。
 */

interface UserDetail {
  username: string
  nickname: string
  status: string
  sex: string
  phone: string
  email: string
  deptName: string
  createTime: string
  remark: string
  avatarBytes: number
}

const detail: UserDetail = {
  username: 'admin',
  nickname: '系统管理员',
  status: 'ACTIVE',
  sex: '1',
  phone: '13812345678',
  email: 'admin@example.com',
  deptName: '总部 / 技术中心',
  createTime: '2026-01-08T09:31:20.000Z',
  // 刻意留空：用于演示占位符与 hideWhenEmpty 的差别
  remark: '',
  avatarBytes: 2048576
}

/** 基础字段集：字典翻译 + 内置格式渲染各一处。 */
const baseItems: ProDescriptionItem<UserDetail>[] = [
  { key: 'username', label: '账号' },
  { key: 'nickname', label: '姓名' },
  { key: 'status', label: '状态', dict: 'sys_user_status' },
  { key: 'sex', label: '性别', dict: 'sys_user_sex' },
  { key: 'deptName', label: '所属部门' },
  { key: 'createTime', label: '创建时间', render: 'datetime' },
  { key: 'avatarBytes', label: '头像大小', render: 'bytes' },
  { key: 'remark', label: '备注' }
]

/** 带权限的字段集：同一份数据，两种权限策略。 */
const permissionItems: ProDescriptionItem<UserDetail>[] = [
  { key: 'username', label: '账号' },
  { key: 'nickname', label: '姓名' },
  // 默认策略：无权限 → 整项不渲染（连标签都不出现）
  { key: 'phone', label: '手机号', permission: 'iam:user:phone' },
  // 显式策略：无权限 → 保留标签，值替换为替代文案
  {
    key: 'email',
    label: '邮箱',
    permission: 'iam:user:email',
    deniedText: '无权限查看，请联系管理员申请'
  }
]

/** 演示 span 占用（单位是"列"，不是 24 栅格）。 */
const spanItems: ProDescriptionItem<UserDetail>[] = [
  { key: 'username', label: '账号' },
  { key: 'status', label: '状态', dict: 'sys_user_status' },
  { key: 'deptName', label: '所属部门', span: 3 },
  { key: 'remark', label: '备注', span: 4 }
]

/** 隐藏空值：字段多的详情页用。 */
const hideEmptyItems: ProDescriptionItem<UserDetail>[] = [
  { key: 'username', label: '账号' },
  { key: 'nickname', label: '姓名' },
  { key: 'remark', label: '备注', hideWhenEmpty: true }
]

const restricted = ref(false)

function toggleRestricted(next: boolean): void {
  restricted.value = next
  if (next) {
    // 只授予"姓名"，手机号与邮箱都无权限
    setStoryPermission(() => false)
  } else {
    resetStoryPermission()
  }
}

// 离开本 story 时还原全局权限接线（见上方说明）
onBeforeUnmount(resetStoryPermission)
</script>

<template>
  <Story title="Pro 组件/ProDescriptions" :layout="{ type: 'grid', width: '100%' }">
    <Variant
      title="基础用法：字典翻译 + 内置格式"
      doc="字典字段自动翻成中文（与表格页一致），createTime 按 datetime 渲染，avatarBytes 转成可读体积。空值显示占位符而不是 null。"
    >
      <ProDescriptions :items="baseItems" :data="detail" :columns="3" />
    </Variant>

    <Variant
      title="span 与 columns 的配合"
      doc="⚠️ span 的单位是「列」而不是 24 栅格（与 ProForm 不同）。columns=4 时 span=4 表示独占一行。"
    >
      <ProDescriptions :items="spanItems" :data="detail" :columns="4" />
    </Variant>

    <Variant
      title="字段权限：默认隐藏 vs deniedText"
      doc="手机号无权限时整项消失（字段的存在本身就是信息）；邮箱声明了 deniedText，因此保留标签、值替换为提示文案。"
    >
      <NSpace vertical :size="12">
        <NCheckbox
          :checked="restricted"
          @update:checked="toggleRestricted"
        >
          模拟「只能看到姓名」的受限角色
        </NCheckbox>
        <ProDescriptions :items="permissionItems" :data="detail" :columns="2" />
      </NSpace>
    </Variant>

    <Variant
      title="隐藏空值（hideWhenEmpty）"
      doc="备注为空时不渲染该项。默认关闭 —— 详情页的字段构成本身是信息，静默少一行会让人怀疑「是不是没查到」。"
    >
      <ProDescriptions :items="hideEmptyItems" :data="detail" :columns="2" />
    </Variant>

    <Variant title="无数据" doc="整体无数据时显示空态，而不是渲染一屏的占位符 —— 后者看起来像加载失败。">
      <ProDescriptions :items="baseItems" :data="null" :columns="2" />
    </Variant>

    <Variant title="单列布局" doc="columns=1 适合窄容器（如抽屉里的详情）。">
      <ProDescriptions :items="baseItems" :data="detail" :columns="1" label-placement="top" />
    </Variant>
  </Story>
</template>
