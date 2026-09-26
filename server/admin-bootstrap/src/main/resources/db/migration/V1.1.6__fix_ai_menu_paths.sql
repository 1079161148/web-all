-- =====================================================================
-- V1.1.6 修正 AI 菜单路径
--
-- 后端 path 的两种既定写法（见前端 dynamic.ts 的 resolveFullPath 注释）：
--   DIR  = 绝对路径（带前导斜杠）；MENU = 相对片段（不带斜杠）
-- V1.1.5 把 MENU 写成了 'ai/chat'（含父段）→ 拼出 /ai/ai/chat。
-- 本迁移把 DIR 归一为绝对路径、MENU 归一为相对片段。
-- =====================================================================

UPDATE `iam_menu`
SET `path` = '/ai', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 6000 AND `path` <> '/ai';

UPDATE `iam_menu`
SET `path` = 'chat', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 6001 AND `path` <> 'chat';

UPDATE `iam_menu`
SET `path` = 'overview', `update_time` = CURRENT_TIMESTAMP(3)
WHERE `id` = 6002 AND `path` <> 'overview';
