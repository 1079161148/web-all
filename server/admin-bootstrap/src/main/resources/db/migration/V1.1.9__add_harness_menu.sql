-- =====================================================================
-- V1.1.9 「DeepSeek Harness」菜单（AI 落地 → Harness 工作台）
--
-- 嵌入方式：同源反向代理 + iframe（见 apps/admin/vite.config.ts 的
-- dev-harness-proxy 插件）。之所以必须同源代理而不能让 iframe 直连
-- 127.0.0.1:3080：
--   1. dsh 的浏览器会话 cookie 是 host-only 且无 SameSite 属性 ——
--      跨站 iframe 里浏览器按 Lax 处理，请求不会带上 cookie（401）；
--   2. 同源代理后 cookie 种在中台域名下、Host 由代理改写，
--      dsh 的 authority 校验（cookie 内嵌签发时的 Host）才能通过。
--
-- 与 V1.1.6/1.1.7 同一约定：MENU 的 path 是不带斜杠的**相对片段**，
-- component 为 views 下的相对路径（前端 glob 查表映射，不硬编码路由）。
-- =====================================================================

INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    (6103, 0, 6000, 'Harness 工作台', 'MENU', 'harness', 'showcase/harness/index',
     'ai:harness:use', 'Cpu', 3, 1, 0, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- keep_alive = 0：iframe 页面被 keep-alive 缓存后，切走再切回会保留
-- 上一轮的 dsh 会话状态；Harness 是"外部应用的工作台"，重新进入时
-- 重新握手更符合预期（且避免 iframe 在隐藏容器里持续占用连接）。

-- 超管拥有全部菜单（与前序迁移保持同一写法）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);
