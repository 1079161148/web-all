-- =====================================================================
-- V1.1.10 修复 5 个菜单的图标名
--
-- <h3>问题</h3>
-- 这些菜单的 icon 值不是 @vicons/ionicons5 的导出名，前端按名字精确解析
-- 失败后<b>降级为名字首字母字形</b>（设计如此：不做模糊容错，避免把拼写
-- 错误静默掩盖）。表现就是侧栏里出现 "M" / "C" / "I" / "T" 这样的占位字。
--
-- 根因是录入时凭印象写了图标名，而写成的是**别的图标库**的名字：
--   TrendChart / ChatDotRound / InfoFilled —— 这些是 Element Plus 的命名；
--   MagicStick / Cpu —— ionicons5 里并不存在这两个导出名。
--
-- <h3>修复原则</h3>
-- 只替换成 @vicons/ionicons5 中<b>确实存在</b>的导出名（逐个在
-- node_modules/@vicons/ionicons5/es 下核对过），并按语义选型：
--   百万级图表   → BarChart      （图表语义，且与"统计"类图标区分）
--   AI 指挥中心  → ColorWand     （指挥/调度 = 施法棒）
--   AI 客服工作台 → Chatbubbles   （多轮对话）
--   AI 落地方案  → Rocket        （落地上线）
--   Harness 工作台 → Terminal    （Harness 是命令行工具）
--
-- <h3>以后的约定</h3>
-- 新增/修改菜单图标时，<b>用菜单管理页的图标选择器选</b>（它的候选集就是
-- 注册表本身），不要手写名字。手写时唯一可靠的来源是
-- {@code @vicons/ionicons5} 的导出名。
--
-- 语句带 {@code AND icon = '旧值'}：既是幂等（重复执行无副作用），
-- 也避免"人工已在界面上改过图标"时被本迁移覆盖回旧值。
-- =====================================================================

UPDATE `iam_menu` SET `icon` = 'BarChart', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 5980 AND `icon` = 'TrendChart';

UPDATE `iam_menu` SET `icon` = 'ColorWand', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 6102 AND `icon` = 'MagicStick';

UPDATE `iam_menu` SET `icon` = 'Chatbubbles', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 6100 AND `icon` = 'ChatDotRound';

UPDATE `iam_menu` SET `icon` = 'Rocket', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 6101 AND `icon` = 'InfoFilled';

UPDATE `iam_menu` SET `icon` = 'Terminal', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 6103 AND `icon` = 'Cpu';
