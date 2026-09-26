/**
 * 技术专题的通用内容模型。
 *
 * <h3>为什么把类型单独抽一个文件</h3>
 * 四个专题（直播 / 流程设计器 / 语音波形 / 协同编辑）共用同一套呈现结构：
 * 每个专题 = 一句导语 + 若干「难点 → 最佳实践 → 落地要点」要点 + 可选一张对照表。
 * 把结构定义在此，新增专题只需写数据、不需要碰模板 —— 这也是本页
 * 内容与渲染分离的落点。
 *
 * <h3>「针对什么难点」的写法约定</h3>
 * 每条内容是给工程师看的，因此：
 * <ul>
 *   <li>{@code pain} 必须说清<b>为什么难</b>（不是复述需求），例如
 *       "坐标与语义混在一份 JSON 里，升级引擎就会冲掉用户摆好的位置"</li>
 *   <li>{@code highlight} 要给出<b>业内已经验证的做法</b>（点名技术方案，
 *       如 ELK / AudioWorklet / Yjs），而不是"优化一下"这类空话</li>
 *   <li>{@code detail} 是可执行要点：参数、边界、易错点</li>
 * </ul>
 */

/** 一个「难点 → 最佳实践」要点。 */
export interface TopicPoint {
  /** 要点标题（一句话点题） */
  title: string
  /** 为什么难：说清痛点本质 */
  pain: string
  /** 业内最佳实践：点名方案 */
  highlight: string
  /** 落地要点：参数、边界、易错点 */
  detail: string[]
  /** 涉及的关键技术名词 */
  tags: string[]
}

/** 一张三列对照表（用于选型对比、规则清单、延迟预算这类结构化信息）。 */
export interface TopicTable {
  title: string
  head: [string, string, string]
  rows: [string, string, string][]
}

/** 一个「名称 + 说明」列表块（用于结论性要点，如延迟预算分解、指标口径）。 */
export interface TopicList {
  title: string
  items: { name: string; desc: string }[]
}

/** 一个完整专题。 */
export interface Topic {
  /** Tab 的 key */
  key: string
  /** Tab 标题 */
  tab: string
  /** 导语：这个专题真正难在哪 */
  lead: string
  /** 要点列表 */
  points: TopicPoint[]
  /** 可选的对照表 */
  table?: TopicTable
  /** 可选的结论性列表（最多两块，多了页面会散） */
  lists?: TopicList[]
}
