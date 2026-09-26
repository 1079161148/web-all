-- =====================================================================
-- AI 调研：字典 + 菜单/权限 + 演示数据
--
-- 说明：
--   1. 字典（plt_dict_type / plt_dict_data）是 tenant_id=0 的平台默认，
--      租户可覆盖 —— 与既有字典一致。
--   2. 菜单是平台级共享定义（tenant_id=0）。超管授权沿用 V1.0.4 的
--      "SELECT 全量菜单" 写法，避免手抄 id。
--   3. 演示数据挂在租户 1，让页面首次打开就有内容可看（空列表无法验证
--      分页/勾选/导入导出/枚举渲染这些能力）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 字典类型（id 从 11 起，避开既有 1~7）
-- ---------------------------------------------------------------------
INSERT INTO `plt_dict_type` (`id`, `tenant_id`, `dict_name`, `dict_type`, `status`, `remark`)
VALUES (11, 0, '调研任务类型', 'srvy_task_type', 'ACTIVE', 'AI 调研内置'),
       (12, 0, '调研任务状态', 'srvy_task_status', 'ACTIVE', 'AI 调研内置'),
       (13, 0, '调研优先级', 'srvy_priority', 'ACTIVE', 'AI 调研内置'),
       (14, 0, '问卷/提纲类型', 'srvy_paper_type', 'ACTIVE', 'AI 调研内置'),
       (15, 0, '问卷状态', 'srvy_paper_status', 'ACTIVE', 'AI 调研内置'),
       (16, 0, '采集渠道', 'srvy_channel', 'ACTIVE', 'AI 调研内置'),
       (17, 0, '采集状态', 'srvy_collect_status', 'ACTIVE', 'AI 调研内置'),
       (18, 0, '报告类型', 'srvy_report_type', 'ACTIVE', 'AI 调研内置'),
       (19, 0, '报告状态', 'srvy_report_status', 'ACTIVE', 'AI 调研内置'),
       (20, 0, '模板分类', 'srvy_template_category', 'ACTIVE', 'AI 调研内置')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 字典项（id 从 801 起）
-- ---------------------------------------------------------------------
INSERT INTO `plt_dict_data` (`id`, `tenant_id`, `dict_type`, `dict_label`, `dict_value`, `sort`, `css_class`,
                             `is_default`, `status`)
VALUES (801, 0, 'srvy_task_type', '问卷调研', 'SURVEY', 1, 'primary', 1, 'ACTIVE'),
       (802, 0, 'srvy_task_type', '深度访谈', 'INTERVIEW', 2, 'info', 0, 'ACTIVE'),
       (803, 0, 'srvy_task_type', '实地观察', 'OBSERVE', 3, 'warning', 0, 'ACTIVE'),
       (804, 0, 'srvy_task_type', '数据分析', 'DATASET', 4, 'success', 0, 'ACTIVE'),

       (811, 0, 'srvy_task_status', '待开始', 'PENDING', 1, 'default', 1, 'ACTIVE'),
       (812, 0, 'srvy_task_status', '进行中', 'RUNNING', 2, 'primary', 0, 'ACTIVE'),
       (813, 0, 'srvy_task_status', '已暂停', 'PAUSED', 3, 'warning', 0, 'ACTIVE'),
       (814, 0, 'srvy_task_status', '已完成', 'DONE', 4, 'success', 0, 'ACTIVE'),

       (821, 0, 'srvy_priority', '高', 'HIGH', 1, 'danger', 0, 'ACTIVE'),
       (822, 0, 'srvy_priority', '中', 'MEDIUM', 2, 'warning', 1, 'ACTIVE'),
       (823, 0, 'srvy_priority', '低', 'LOW', 3, 'default', 0, 'ACTIVE'),

       (831, 0, 'srvy_paper_type', '问卷', 'QUESTIONNAIRE', 1, 'primary', 1, 'ACTIVE'),
       (832, 0, 'srvy_paper_type', '访谈提纲', 'OUTLINE', 2, 'info', 0, 'ACTIVE'),

       (841, 0, 'srvy_paper_status', '草稿', 'DRAFT', 1, 'default', 1, 'ACTIVE'),
       (842, 0, 'srvy_paper_status', '已发布', 'PUBLISHED', 2, 'success', 0, 'ACTIVE'),
       (843, 0, 'srvy_paper_status', '已下线', 'OFFLINE', 3, 'warning', 0, 'ACTIVE'),

       (851, 0, 'srvy_channel', '线上', 'ONLINE', 1, 'primary', 1, 'ACTIVE'),
       (852, 0, 'srvy_channel', '线下', 'OFFLINE', 2, 'info', 0, 'ACTIVE'),
       (853, 0, 'srvy_channel', '电话', 'PHONE', 3, 'warning', 0, 'ACTIVE'),
       (854, 0, 'srvy_channel', '邮件', 'EMAIL', 4, 'default', 0, 'ACTIVE'),

       (861, 0, 'srvy_collect_status', '采集中', 'COLLECTING', 1, 'primary', 1, 'ACTIVE'),
       (862, 0, 'srvy_collect_status', '已结束', 'FINISHED', 2, 'success', 0, 'ACTIVE'),
       (863, 0, 'srvy_collect_status', '已终止', 'ABORTED', 3, 'danger', 0, 'ACTIVE'),

       (871, 0, 'srvy_report_type', '汇总报告', 'SUMMARY', 1, 'primary', 1, 'ACTIVE'),
       (872, 0, 'srvy_report_type', '交叉分析', 'CROSS', 2, 'info', 0, 'ACTIVE'),
       (873, 0, 'srvy_report_type', '定制报告', 'CUSTOM', 3, 'warning', 0, 'ACTIVE'),

       (881, 0, 'srvy_report_status', '草稿', 'DRAFT', 1, 'default', 1, 'ACTIVE'),
       (882, 0, 'srvy_report_status', '评审中', 'REVIEWING', 2, 'warning', 0, 'ACTIVE'),
       (883, 0, 'srvy_report_status', '已发布', 'PUBLISHED', 3, 'success', 0, 'ACTIVE'),

       (891, 0, 'srvy_template_category', 'NPS 满意度', 'NPS', 1, 'primary', 1, 'ACTIVE'),
       (892, 0, 'srvy_template_category', '可用性测试', 'USABILITY', 2, 'info', 0, 'ACTIVE'),
       (893, 0, 'srvy_template_category', '访谈提纲', 'INTERVIEW', 3, 'warning', 0, 'ACTIVE'),
       (894, 0, 'srvy_template_category', '其他', 'OTHER', 4, 'default', 0, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- ---------------------------------------------------------------------
-- 菜单 + 权限点
--   DIR 3「AI调研」 → 5 个 MENU，每个 MENU 下挂 query/create/update/delete/import
--   图标取 @vicons/ionicons5 的组件名（前端按名字解析）
-- ---------------------------------------------------------------------
INSERT INTO `iam_menu` (`id`, `tenant_id`, `parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`,
                        `icon`, `sort`, `visible`, `keep_alive`, `status`)
VALUES
    -- ===== AI 调研 =====
    (3, 0, 0, 'AI调研', 'DIR', '/survey', NULL, NULL, 'Analytics', 3, 1, 1, 'ACTIVE'),

    (900, 0, 3, '调研任务管理', 'MENU', 'task', 'survey/task/index', 'srvy:task:query', 'List', 1, 1, 1, 'ACTIVE'),
    (9001, 0, 900, '查询任务', 'BUTTON', NULL, NULL, 'srvy:task:query', NULL, 1, 1, 1, 'ACTIVE'),
    (9002, 0, 900, '新增任务', 'BUTTON', NULL, NULL, 'srvy:task:create', NULL, 2, 1, 1, 'ACTIVE'),
    (9003, 0, 900, '修改任务', 'BUTTON', NULL, NULL, 'srvy:task:update', NULL, 3, 1, 1, 'ACTIVE'),
    (9004, 0, 900, '删除任务', 'BUTTON', NULL, NULL, 'srvy:task:delete', NULL, 4, 1, 1, 'ACTIVE'),
    (9005, 0, 900, '导入任务', 'BUTTON', NULL, NULL, 'srvy:task:import', NULL, 5, 1, 1, 'ACTIVE'),

    (1000, 0, 3, '问卷/提纲设计', 'MENU', 'paper', 'survey/paper/index', 'srvy:paper:query', 'Create', 2, 1, 1, 'ACTIVE'),
    (10001, 0, 1000, '查询问卷', 'BUTTON', NULL, NULL, 'srvy:paper:query', NULL, 1, 1, 1, 'ACTIVE'),
    (10002, 0, 1000, '新增问卷', 'BUTTON', NULL, NULL, 'srvy:paper:create', NULL, 2, 1, 1, 'ACTIVE'),
    (10003, 0, 1000, '修改问卷', 'BUTTON', NULL, NULL, 'srvy:paper:update', NULL, 3, 1, 1, 'ACTIVE'),
    (10004, 0, 1000, '删除问卷', 'BUTTON', NULL, NULL, 'srvy:paper:delete', NULL, 4, 1, 1, 'ACTIVE'),
    (10005, 0, 1000, '导入问卷', 'BUTTON', NULL, NULL, 'srvy:paper:import', NULL, 5, 1, 1, 'ACTIVE'),

    (1100, 0, 3, '数据采集', 'MENU', 'collect', 'survey/collect/index', 'srvy:collect:query', 'CloudUpload', 3, 1, 1, 'ACTIVE'),
    (11001, 0, 1100, '查询采集', 'BUTTON', NULL, NULL, 'srvy:collect:query', NULL, 1, 1, 1, 'ACTIVE'),
    (11002, 0, 1100, '新增采集', 'BUTTON', NULL, NULL, 'srvy:collect:create', NULL, 2, 1, 1, 'ACTIVE'),
    (11003, 0, 1100, '修改采集', 'BUTTON', NULL, NULL, 'srvy:collect:update', NULL, 3, 1, 1, 'ACTIVE'),
    (11004, 0, 1100, '删除采集', 'BUTTON', NULL, NULL, 'srvy:collect:delete', NULL, 4, 1, 1, 'ACTIVE'),
    (11005, 0, 1100, '导入采集', 'BUTTON', NULL, NULL, 'srvy:collect:import', NULL, 5, 1, 1, 'ACTIVE'),
    (11006, 0, 1100, '上传附件', 'BUTTON', NULL, NULL, 'srvy:file:upload', NULL, 6, 1, 1, 'ACTIVE'),

    (1200, 0, 3, '调研分析报告', 'MENU', 'report', 'survey/report/index', 'srvy:report:query', 'StatsChart', 4, 1, 1, 'ACTIVE'),
    (12001, 0, 1200, '查询报告', 'BUTTON', NULL, NULL, 'srvy:report:query', NULL, 1, 1, 1, 'ACTIVE'),
    (12002, 0, 1200, '新增报告', 'BUTTON', NULL, NULL, 'srvy:report:create', NULL, 2, 1, 1, 'ACTIVE'),
    (12003, 0, 1200, '修改报告', 'BUTTON', NULL, NULL, 'srvy:report:update', NULL, 3, 1, 1, 'ACTIVE'),
    (12004, 0, 1200, '删除报告', 'BUTTON', NULL, NULL, 'srvy:report:delete', NULL, 4, 1, 1, 'ACTIVE'),
    (12005, 0, 1200, '导入报告', 'BUTTON', NULL, NULL, 'srvy:report:import', NULL, 5, 1, 1, 'ACTIVE'),

    (1300, 0, 3, '调研模板库', 'MENU', 'template', 'survey/template/index', 'srvy:template:query', 'Library', 5, 1, 1, 'ACTIVE'),
    (13001, 0, 1300, '查询模板', 'BUTTON', NULL, NULL, 'srvy:template:query', NULL, 1, 1, 1, 'ACTIVE'),
    (13002, 0, 1300, '新增模板', 'BUTTON', NULL, NULL, 'srvy:template:create', NULL, 2, 1, 1, 'ACTIVE'),
    (13003, 0, 1300, '修改模板', 'BUTTON', NULL, NULL, 'srvy:template:update', NULL, 3, 1, 1, 'ACTIVE'),
    (13004, 0, 1300, '删除模板', 'BUTTON', NULL, NULL, 'srvy:template:delete', NULL, 4, 1, 1, 'ACTIVE'),
    (13005, 0, 1300, '导入模板', 'BUTTON', NULL, NULL, 'srvy:template:import', NULL, 5, 1, 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

-- 超管拥有全部菜单（含本次新增的 AI 调研）
INSERT INTO `iam_role_menu` (`tenant_id`, `role_id`, `menu_id`)
SELECT 1, 1, `id`
FROM `iam_menu`
WHERE `del_flag` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `iam_role_menu` rm
                  WHERE rm.`role_id` = 1
                    AND rm.`menu_id` = `iam_menu`.`id`);

-- ---------------------------------------------------------------------
-- 演示数据（租户 1）
-- ---------------------------------------------------------------------
INSERT INTO `srvy_task` (`id`, `tenant_id`, `task_code`, `task_name`, `task_type`, `priority`, `owner_name`,
                         `start_date`, `end_date`, `progress`, `status`, `remark`)
VALUES (20001, 1, 'TASK-NPS-2026Q3', '2026Q3 客户满意度调研', 'SURVEY', 'HIGH', '张调研', '2026-07-01', '2026-09-30', 65,
        'RUNNING', '覆盖全部付费租户，按 NPS 标准问卷执行'),
       (20002, 1, 'TASK-UX-ONBOARD', '新用户上手体验访谈', 'INTERVIEW', 'MEDIUM', '李可用', '2026-08-10', '2026-09-10', 100,
        'DONE', '已完成 12 场深度访谈，产出体验问题清单'),
       (20003, 1, 'TASK-CHURN-ANALYSIS', '流失客户数据分析', 'DATASET', 'LOW', '王数据', '2026-09-01', '2026-10-31', 10,
        'PENDING', '基于近 6 个月行为数据做流失归因')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

INSERT INTO `srvy_paper` (`id`, `tenant_id`, `paper_code`, `title`, `paper_type`, `version_no`, `content`, `status`,
                          `remark`)
VALUES (21001, 1, 'PAPER-NPS-V3', 'NPS 满意度问卷 v3', 'QUESTIONNAIRE', 3,
        '<h3>客户满意度问卷</h3><p>请根据最近 30 天的使用体验作答。</p><ol><li>您向朋友推荐本产品的可能性（0-10 分）？</li><li>最让您满意的功能是？</li><li>最需要改进的一点是什么？</li></ol>',
        'PUBLISHED', '含 8 道必答 + 3 道选答'),
       (21002, 1, 'PAPER-ONBOARD-OUTLINE', '新用户上手访谈提纲', 'OUTLINE', 1,
        '<h3>访谈提纲（45 分钟）</h3><p><strong>开场</strong>：介绍目的与隐私说明。</p><ul><li>首次登录时的第一印象？</li><li>哪一步卡住了？当时怎么处理的？</li><li>如果只能改一件事，改什么？</li></ul>',
        'DRAFT', '访谈用，问题按漏斗式递进')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

INSERT INTO `srvy_collect` (`id`, `tenant_id`, `task_id`, `task_name`, `channel`, `collector`, `collect_date`,
                            `sample_count`, `valid_count`, `quality_score`, `file_name`, `status`, `remark`)
VALUES (22001, 1, 20001, '2026Q3 客户满意度调研', 'ONLINE', '张调研', '2026-08-20', 500, 468, 92, 'nps-2026q3-online.csv',
        'FINISHED', '线上回收，剔除 32 份作答时长过短的样本'),
       (22002, 1, 20001, '2026Q3 客户满意度调研', 'PHONE', '赵电话', '2026-09-05', 80, 61, 85, 'nps-2026q3-phone.xlsx',
        'COLLECTING', '电话回访重点客户中'),
       (22003, 1, 20002, '新用户上手体验访谈', 'OFFLINE', '李可用', '2026-08-28', 12, 12, 98, NULL, 'FINISHED',
        '线下访谈，已全部转写')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

INSERT INTO `srvy_report` (`id`, `tenant_id`, `task_id`, `task_name`, `report_title`, `report_type`, `author`,
                           `publish_date`, `summary`, `content`, `status`, `remark`)
VALUES (23001, 1, 20002, '新用户上手体验访谈', '新用户上手体验问题汇总报告', 'SUMMARY', '李可用', '2026-09-12',
        '共识别 17 个体验问题，其中 4 个为阻塞级（影响首次成功配置）。',
        '<h3>核心结论</h3><p>首次配置流程是最大的流失点。<strong>4 个阻塞级问题</strong>全部集中在"权限与数据源配置"环节。</p><h4>建议</h4><ol><li>合并配置向导为 3 步</li><li>提供配置模板导入</li></ol>',
        'PUBLISHED', '已同步产品与研发排期'),
       (23002, 1, 20001, '2026Q3 客户满意度调研', '2026Q3 NPS 中期观察', 'CROSS', '王数据', NULL,
        '中期 NPS 为 42，较上季度提升 6 分，企业版客户满意度显著高于专业版。',
        '<h3>交叉分析</h3><p>按套餐维度交叉后，企业版 NPS 58 / 专业版 35。</p>', 'REVIEWING', '数据待复核后发布')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);

INSERT INTO `srvy_template` (`id`, `tenant_id`, `template_code`, `template_name`, `category`, `content`, `usage_count`,
                             `status`, `remark`)
VALUES (24001, 1, 'TPL-NPS-01', '标准 NPS 满意度模板', 'NPS',
        '<h3>NPS 满意度问卷</h3><p>0-10 分推荐意愿 + 开放题</p>', 12, 'ACTIVE', '通用模板，可直接套用'),
       (24002, 1, 'TPL-USABILITY-01', '可用性测试任务脚本', 'USABILITY',
        '<h3>可用性测试脚本</h3><ol><li>任务 1：完成注册并创建第一个项目</li><li>任务 2：邀请一名成员加入</li></ol>', 5,
        'ACTIVE', '含任务清单与观察记录表'),
       (24003, 1, 'TPL-INTERVIEW-01', '深度访谈通用提纲', 'INTERVIEW',
        '<h3>深度访谈提纲</h3><p>背景 → 现状 → 痛点 → 期望</p>', 8, 'DISABLED', '待产品确认后启用')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP(3);
