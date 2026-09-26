<template>
  <div class="live-tech">
    <!-- ===== 顶部说明：把"内容性质"一次讲清，避免读者把示意数值当成某项目的线上统计 ===== -->
    <NAlert class="live-tech__notice" type="info" :bordered="false">
      <template #header>关于内容的说明</template>
      本页汇集五个技术专题的<b>难点与业内最佳实践</b>：直播 WebRTC、视频转码、复杂流程设计器、
      AI 语音与波形、协同编辑。直播部分的问题类型、机制与排查路径均可用公开规范、浏览器实现与
      社区事故复盘交叉验证，<b>其中数值为典型量级示意</b>；其余专题给的是工程上已被验证的做法
      （点名具体方案与关键参数），同样<b>不含任何项目的线上数据</b>。
      若要作为正式材料对外，请把量化部分替换成自己的监控数据 —— 内容全在
      <code>content-*.ts</code>，模板无需改动。
    </NAlert>

    <NTabs class="live-tech__tabs" type="line" animated>
      <!-- ================================================================
           Tab 1：WebRTC 问题复盘
           ================================================================ -->
      <NTabPane name="webrtc" tab="WebRTC 直播问题复盘">
        <div class="live-tech__lead">
          <p>
            WebRTC 的问题有个共同特点：<b>在办公室永远复现不了</b>。同一份代码在不同网络、不同机型上表现完全不同，
            因此排查必须依赖「可观测数据 + 分层定位」，而不是猜。下面三个案例覆盖了三类典型故障域 ——
            连通性、编解码兼容性、体验质量。
          </p>
        </div>

        <NCard
          v-for="(item, index) in webrtcCases"
          :key="item.title"
          class="live-tech__case"
          size="small"
          :bordered="false"
        >
          <template #header>
            <div class="live-tech__case-head">
              <span class="live-tech__case-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <NTag :type="item.severityType" size="small" :bordered="false">{{ item.severity }}</NTag>
              <span class="live-tech__case-title">{{ item.title }}</span>
            </div>
          </template>

          <p class="live-tech__impact">{{ item.impact }}</p>

          <div class="live-tech__cols">
            <section class="live-tech__block">
              <h4 class="live-tech__block-title">现象（可观测）</h4>
              <ul class="live-tech__list">
                <li v-for="line in item.symptom" :key="line">{{ line }}</li>
              </ul>
            </section>

            <section class="live-tech__block">
              <h4 class="live-tech__block-title">根因</h4>
              <p class="live-tech__para">{{ item.rootCause }}</p>
            </section>
          </div>

          <!-- 排查过程与修复动作：默认收起，避免整页过长 -->
          <div v-if="isExpanded(item.title)" class="live-tech__cols">
            <section class="live-tech__block">
              <h4 class="live-tech__block-title">排查路径（按顺序做）</h4>
              <ol class="live-tech__list live-tech__list--ordered">
                <li v-for="line in item.diagnose" :key="line">{{ line }}</li>
              </ol>
            </section>

            <section class="live-tech__block">
              <h4 class="live-tech__block-title">修复动作</h4>
              <ul class="live-tech__list">
                <li v-for="line in item.fix" :key="line">{{ line }}</li>
              </ul>
            </section>
          </div>

          <div class="live-tech__case-foot">
            <NButton size="tiny" quaternary type="primary" @click="toggle(item.title)">
              {{ isExpanded(item.title) ? '收起排查与修复' : '展开排查与修复' }}
            </NButton>
            <span class="live-tech__result">
              <span class="live-tech__result-label">结果</span>{{ item.result }}
            </span>
          </div>

          <div class="live-tech__keys">
            <NTag v-for="key in item.keys" :key="key" size="tiny" :bordered="false" class="live-tech__key">
              {{ key }}
            </NTag>
          </div>
        </NCard>

        <NCard class="live-tech__card" size="small" title="值班速查：症状 → 先看哪里 → 常见根因" :bordered="false">
          <div class="live-tech__table">
            <div class="live-tech__tr live-tech__tr--head">
              <span>症状</span>
              <span>先看哪里</span>
              <span>常见根因</span>
            </div>
            <div v-for="row in symptomTable" :key="row.symptom" class="live-tech__tr">
              <span class="live-tech__td-strong">{{ row.symptom }}</span>
              <span>{{ row.check }}</span>
              <span class="live-tech__td-muted">{{ row.cause }}</span>
            </div>
          </div>
        </NCard>
      </NTabPane>

      <!-- ================================================================
           Tab 2：视频转码
           ================================================================ -->
      <NTabPane name="transcode" tab="视频转码：难点与亮点">
        <div class="live-tech__lead">
          <p>
            转码链路的价值不在于"能转"，而在于<b>成本、延迟、稳定性三者的取舍</b>。
            下面先看链路全景，再看八个真实难点各自对应的做法 —— 每一条都能落成一项具体的技术决策。
          </p>
        </div>

        <NCard class="live-tech__card" size="small" title="链路全景" :bordered="false">
          <div class="live-tech__chain">
            <template v-for="(stage, index) in transcodeChain" :key="stage.name">
              <div class="live-tech__chain-node">
                <div class="live-tech__chain-name">{{ stage.name }}</div>
                <div class="live-tech__chain-detail">{{ stage.detail }}</div>
              </div>
              <div v-if="index < transcodeChain.length - 1" class="live-tech__chain-arrow">→</div>
            </template>
          </div>
        </NCard>

        <div class="live-tech__points">
          <NCard
            v-for="point in transcodePoints"
            :key="point.title"
            class="live-tech__point"
            size="small"
            :bordered="false"
          >
            <h3 class="live-tech__point-title">{{ point.title }}</h3>
            <div class="live-tech__point-row">
              <span class="live-tech__point-label live-tech__point-label--pain">难点</span>
              <span>{{ point.pain }}</span>
            </div>
            <div class="live-tech__point-row">
              <span class="live-tech__point-label live-tech__point-label--hi">亮点</span>
              <span class="live-tech__point-hi">{{ point.highlight }}</span>
            </div>
            <ul class="live-tech__list">
              <li v-for="line in point.detail" :key="line">{{ line }}</li>
            </ul>
            <div class="live-tech__keys">
              <NTag v-for="tag in point.tags" :key="tag" size="tiny" :bordered="false" class="live-tech__key">
                {{ tag }}
              </NTag>
            </div>
          </NCard>
        </div>

        <div class="live-tech__cols">
          <NCard class="live-tech__card" size="small" title="降本三板斧" :bordered="false">
            <div v-for="lever in costLever" :key="lever.name" class="live-tech__lever">
              <span class="live-tech__lever-name">{{ lever.name }}</span>
              <span class="live-tech__lever-desc">{{ lever.desc }}</span>
            </div>
          </NCard>

          <NCard class="live-tech__card" size="small" title="对外承诺与对内排查共用同一套 QoE 口径" :bordered="false">
            <div v-for="metric in qoeMetrics" :key="metric.name" class="live-tech__metric">
              <span class="live-tech__metric-name">{{ metric.name }}</span>
              <span class="live-tech__metric-why">{{ metric.why }}</span>
            </div>
          </NCard>
        </div>
      </NTabPane>

      <!-- ================================================================
           其余专题：结构完全一致（导语 → 难点/最佳实践要点 → 对照表 → 结论列表），
           因此共用同一段模板渲染 —— 新增专题只需在 content-*.ts 里加数据，
           本文件一行都不用改
           ================================================================ -->
      <NTabPane
        v-for="topic in generalTopics"
        :key="topic.key"
        :name="topic.key"
        :tab="topic.tab"
      >
        <div class="live-tech__lead">
          <p>{{ topic.lead }}</p>
        </div>

        <div class="live-tech__points">
          <NCard
            v-for="point in topic.points"
            :key="point.title"
            class="live-tech__point"
            size="small"
            :bordered="false"
          >
            <h3 class="live-tech__point-title">{{ point.title }}</h3>
            <div class="live-tech__point-row">
              <span class="live-tech__point-label live-tech__point-label--pain">难点</span>
              <span>{{ point.pain }}</span>
            </div>
            <div class="live-tech__point-row">
              <span class="live-tech__point-label live-tech__point-label--hi">最佳实践</span>
              <span class="live-tech__point-hi">{{ point.highlight }}</span>
            </div>
            <ul class="live-tech__list">
              <li v-for="line in point.detail" :key="line">{{ line }}</li>
            </ul>
            <div class="live-tech__keys">
              <NTag v-for="tag in point.tags" :key="tag" size="tiny" :bordered="false" class="live-tech__key">
                {{ tag }}
              </NTag>
            </div>
          </NCard>
        </div>

        <NCard
          v-if="topic.table"
          class="live-tech__card"
          size="small"
          :title="topic.table.title"
          :bordered="false"
        >
          <div class="live-tech__table">
            <div class="live-tech__tr live-tech__tr--head">
              <span v-for="head in topic.table.head" :key="head">{{ head }}</span>
            </div>
            <div v-for="row in topic.table.rows" :key="row[0]" class="live-tech__tr">
              <span
                v-for="(cell, cellIndex) in row"
                :key="cellIndex"
                :class="{ 'live-tech__td-strong': cellIndex === 0 }"
              >
                {{ cell }}
              </span>
            </div>
          </div>
        </NCard>

        <div v-if="topic.lists" class="live-tech__cols">
          <NCard
            v-for="list in topic.lists"
            :key="list.title"
            class="live-tech__card"
            size="small"
            :title="list.title"
            :bordered="false"
          >
            <div v-for="item in list.items" :key="item.name" class="live-tech__lever">
              <span class="live-tech__lever-name">{{ item.name }}</span>
              <span class="live-tech__lever-desc">{{ item.desc }}</span>
            </div>
          </NCard>
        </div>
      </NTabPane>
    </NTabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NAlert, NButton, NCard, NTabPane, NTabs, NTag } from '@admin/ui'
import {
  costLever,
  qoeMetrics,
  symptomTable,
  transcodeChain,
  transcodePoints,
  webrtcCases
} from './content'
import { collabTopic } from './content-collab'
import { docPreviewTopic } from './content-doc-preview'
import { voiceTopic } from './content-voice'
import { workflowTopic } from './content-workflow'
import type { Topic } from './types'

/**
 * 技术专题：五个专题的难点与业内最佳实践。
 *
 * <h3>为什么把这些放在一个页面里</h3>
 * 这五块是同类性质的东西：<b>会议室里讲不清、只有踩过的人才知道坑在哪</b>。
 * WebRTC 的问题在办公室不可复现、转码的成本决策被"能不能转"掩盖、
 * 流程设计器的难点被"能拖能连"掩盖、语音延迟与协同冲突更是看不见摸不着。
 * 把它们的复盘结构与"难点 → 最佳实践"固化成页面，等于把个人经验
 * 变成团队可复用的判断依据。
 *
 * <h3>两种呈现形态（有意不同）</h3>
 * <ul>
 *   <li>直播两部分：<b>事故复盘</b>形态（影响面 → 现象 → 根因 → 排查 → 修复 → 结果），
 *       因为这两类问题的价值在"排查路径"</li>
 *   <li>其余三个专题：<b>设计决策</b>形态（难点 → 最佳实践 → 落地要点），
 *       因为它们不是"某次故障"，而是"一开始就该做对的选择"</li>
 * </ul>
 * 后者用同一段模板渲染（{@link generalTopics}），新增专题只需加数据文件。
 *
 * <h3>内容与渲染分离</h3>
 * 全部素材在 content*.ts / types.ts，本文件只负责呈现。替换成自己项目的线上数据、
 * 或按客户删减专题时不需要改模板 —— 与 showcase/monitor 抽 engine.ts 同一思路。
 *
 * <h3>真诚提示</h3>
 * 页面顶部固定一条"内容性质"说明：机制与做法真实可查、数值为示意。
 * 这既让材料经得起追问，也避免把示意数据误当成某项目实测结论。
 */

/** 以"设计决策"形态呈现的专题（顺序即 Tab 顺序）。 */
const generalTopics: Topic[] = [workflowTopic, voiceTopic, collabTopic, docPreviewTopic]

/** 案例展开状态：key 用案例标题（标题即业务唯一键，无需额外 id）。 */
const expanded = ref<Record<string, boolean>>({})

function toggle(key: string): void {
  expanded.value[key] = !expanded.value[key]
}

function isExpanded(key: string): boolean {
  return expanded.value[key] === true
}
</script>

<style scoped>
.live-tech {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.live-tech__notice {
  border-radius: 8px;
}

.live-tech__notice code {
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(128, 128, 128, 0.15);
}

.live-tech__lead {
  margin: 4px 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--n-text-color-2, #666);
}

.live-tech__case,
.live-tech__card {
  margin-bottom: 12px;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
}

.live-tech__case-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.live-tech__case-index {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  opacity: 0.45;
}

.live-tech__case-title {
  font-size: 15px;
  font-weight: 600;
}

/* 影响面：左侧色条 + 浅底，视觉上把"严重程度"先立住 */
.live-tech__impact {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-left: 3px solid var(--n-primary-color, #18a058);
  border-radius: 0 6px 6px 0;
  background: rgba(128, 128, 128, 0.08);
  font-size: 13px;
  line-height: 1.7;
}

.live-tech__cols {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  margin-bottom: 12px;
}

.live-tech__block-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  opacity: 0.75;
}

.live-tech__list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.75;
}

.live-tech__list li {
  margin-bottom: 4px;
}

.live-tech__list--ordered {
  list-style: decimal;
}

.live-tech__para {
  margin: 0;
  font-size: 13px;
  line-height: 1.75;
}

.live-tech__case-foot {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 8px;
  border-top: 1px dashed rgba(128, 128, 128, 0.25);
}

.live-tech__result {
  flex: 1;
  min-width: 240px;
  font-size: 13px;
  line-height: 1.7;
}

.live-tech__result-label {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 3px;
  background: var(--n-success-color-pressed, #18a058);
  color: #fff;
  font-size: 12px;
}

.live-tech__keys {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.live-tech__key {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

/* 速查表：用 grid 而不是 NTable —— @admin/ui 未导出 NTable，且此处仅需三列文本 */
.live-tech__table {
  font-size: 13px;
}

.live-tech__tr {
  display: grid;
  grid-template-columns: 1.2fr 1.1fr 1.3fr;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(128, 128, 128, 0.12);
  line-height: 1.6;
}

.live-tech__tr--head {
  font-weight: 600;
  opacity: 0.7;
  border-bottom: 1px solid rgba(128, 128, 128, 0.3);
}

.live-tech__td-strong {
  font-weight: 500;
}

.live-tech__td-muted {
  color: var(--n-text-color-2, #666);
}

/* 链路全景：横向流水线，窄屏自动换行 */
.live-tech__chain {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: 8px;
}

.live-tech__chain-node {
  flex: 1;
  min-width: 130px;
  padding: 10px 12px;
  border: 1px solid rgba(128, 128, 128, 0.2);
  border-radius: 8px;
  background: rgba(128, 128, 128, 0.05);
}

.live-tech__chain-name {
  margin-bottom: 4px;
  font-size: 13px;
  font-weight: 600;
}

.live-tech__chain-detail {
  font-size: 12px;
  line-height: 1.6;
  color: var(--n-text-color-2, #666);
}

.live-tech__chain-arrow {
  align-self: center;
  opacity: 0.4;
}

.live-tech__points {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.live-tech__point {
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
}

.live-tech__point-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.live-tech__point-row {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
  line-height: 1.7;
}

.live-tech__point-label {
  flex: none;
  height: 20px;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 20px;
}

.live-tech__point-label--pain {
  background: rgba(208, 48, 80, 0.14);
  color: #d03050;
}

.live-tech__point-label--hi {
  background: rgba(24, 160, 88, 0.14);
  color: #18a058;
}

.live-tech__point-hi {
  font-weight: 500;
}

.live-tech__lever,
.live-tech__metric {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.18);
  font-size: 13px;
  line-height: 1.65;
}

.live-tech__lever:last-child,
.live-tech__metric:last-child {
  border-bottom: none;
}

.live-tech__lever-name,
.live-tech__metric-name {
  flex: none;
  width: 112px;
  font-weight: 600;
  word-break: break-word;
}

.live-tech__lever-desc,
.live-tech__metric-why {
  color: var(--n-text-color-2, #666);
}

@media (max-width: 720px) {
  .live-tech__tr {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>
