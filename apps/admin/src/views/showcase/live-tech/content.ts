/**
 * 「直播技术专题」页面的内容数据。
 *
 * <h3>为什么把内容抽成数据而不是写死在模板里</h3>
 * 这些内容是**会持续迭代的技术素材**（新增案例、替换成自己线上数据、
 * 切到不同客户版本），与渲染逻辑分离后，改动只发生在这一处，
 * 模板不需要动 —— 与 showcase/monitor 把引擎抽到 ./engine.ts 同一思路。
 *
 * <h3>关于"真实案例"的边界（重要）</h3>
 * 下面每个案例都是 WebRTC 生产环境中**真实存在的问题类型**：技术机制、
 * 现象特征、排查路径、修复手段均可在公开规范/浏览器实现/社区事故复盘中
 * 交叉验证。但**其中的数值是典型量级示意**，不是某一个项目的线上统计。
 * 若要作为正式复盘材料对外，请把 operation/symptom/result 里的量化部分
 * 替换成你自己的监控数据（页面顶部已有对应声明，避免读者误读）。
 */

/** 单个 WebRTC 线上问题复盘。 */
export interface WebrtcCase {
  /** 标题（一句话说清"什么坏了"） */
  title: string
  /** 严重度标签 */
  severity: string
  /** 严重度配色（naive 的 tag type） */
  severityType: 'error' | 'warning' | 'info'
  /** 影响面（谁受影响、多大规模） */
  impact: string
  /** 可观测到的现象 */
  symptom: string[]
  /** 排查路径：先看什么、看到什么、如何定位 */
  diagnose: string[]
  /** 根因（一句话，直指设计缺陷而非偶发故障） */
  rootCause: string
  /** 修复动作 */
  fix: string[]
  /** 修复后的结果 */
  result: string
  /** 涉及的关键技术点 */
  keys: string[]
}

export const webrtcCases: WebrtcCase[] = [
  {
    title: '约 1/4 用户永远连不上：ICE 候选里从来没有 relay',
    severity: '连通性',
    severityType: 'error',
    impact:
      '约 25% 的会话停留在 connecting，最终 ICE 进入 failed；投诉集中在企业内网、校园网、部分 4G 用户 —— 即网络环境越"严格"，命中率越高。',
    symptom: [
      '页面一直转圈，iceConnectionState 从 checking 走向 failed，从不出现 connected',
      '同一账号换到手机热点后又能连上 —— 与账号、客户端版本无关，只与网络有关',
      '服务端信令日志正常：offer/answer 都成功交换了，问题完全发生在媒体通道建立阶段'
    ],
    diagnose: [
      'chrome://webrtc-internals 看 ICE candidate pairs：失败会话的本地候选只有 host 与 srflx 两类，**没有 relay 类型候选**',
      '抓 SDP 搜索 a=candidate：确认整段 SDP 里不存在 typ relay',
      '用官方示例页在同网络做对照实验：只配 STUN 必失败，配 TURN 立刻成功 —— 锁定"缺中继"而非"代码写错"',
      '按 NAT 类型分类统计：失败样本几乎全部落在对称 NAT 或严格出站防火墙'
    ],
    rootCause:
      '架构上只配了 STUN 做地址发现，没有部署 TURN 中继兜底。对称 NAT 下打洞在原理上就不可能成功（映射端口每次不同，双方学到的地址无法用于对端回包），这类会话**必须有中继**才可能连通 —— 所以这不是偶发故障，而是设计缺口。',
    fix: [
      '部署 TURN（coturn）：同时开放 3478/udp、3478/tcp 与 **443/tls** —— 只放行 443 出口的网络只能靠 TLS 形态穿透',
      '分层策略：默认先尝试 P2P，ICE 超时（约 3s）未连通则强制 iceTransportPolicy=relay 重连，中继成本只落在失败分支',
      '多 TURN 节点就近接入 + 独立核算中继带宽（中继成本 ≈ 并发路数 × 单路码率，必须纳入容量规划）',
      '建立连通方式看板：P2P 与 relay 占比、ICE 建立耗时 P95、relay 峰值带宽'
    ],
    result:
      '连通率从约 92% 提升到 99% 以上；绝大多数用户仍走 P2P，仅原本失败的会话产生中继带宽，成本可控且可预测。',
    keys: ['host/srflx/prflx/relay 候选类型', '对称 NAT', 'coturn / TURN over TLS', 'iceTransportPolicy 降级']
  },
  {
    title: '有声音没画面（黑屏）：H.264 profile-level-id 协商不一致',
    severity: '兼容性',
    severityType: 'error',
    impact:
      '部分机型黑屏但音频正常，用户能听见声音、看不到画面；机型分布集中（某几款 Android 平板与老设备），桌面 Chrome 完全不复现 —— 极易被误判为"网络卡"。',
    symptom: [
      'inbound-rtp 统计里 framesDecoded 恒为 0，framesDropped 持续增长（包收到了，解不出来）',
      '接收端分辨率显示为 0×0，但码率、包数、jitter 指标都正常',
      '同一条流在桌面端正常，只有特定硬件解码能力的设备黑屏'
    ],
    diagnose: [
      'getStats() 对比收发两端：packetsReceived 正常增长而 framesDecoded 为 0 → 问题在**解码环节**，不在传输',
      '比对正常/异常两端的 SDP，发现 a=fmtp 行不同：正常侧 profile-level-id=42e01f（Baseline 3.1），异常侧协商成了 640c1f（High 3.1）',
      '查机型解码能力白名单：异常机型的硬件解码器只支持 Baseline/Main，且客户端未启用软解兜底',
      '确认是协商问题而非编码问题：服务端强制 Baseline 后，同一机型立刻出画面'
    ],
    rootCause:
      'offer 里暴露了设备支持的全部 H.264 profile（含 High），answer 选中了 High；但部分设备的硬件解码器不支持该 profile，软解又未启用 → 帧全部丢弃。媒体协商缺少一道"按接收端真实解码能力收敛"的环节。',
    fix: [
      '编码基线收敛：offer 只暴露 profile-level-id=42e01f（Baseline 3.1）+ packetization-mode=1，把兼容性放在第一位',
      '能力交集：用 RTCRtpSender.getCapabilities() 与接收端上报能力求交集，必要时用 setCodecPreferences 调整优先序',
      '兜底路径：对确实无法解码的设备，服务端下发兼容档（或切 VP8 备选）',
      '把机型真机回归纳入 CI —— 这类问题在桌面端永远测不出来'
    ],
    result:
      '黑屏问题清零；同时全平台收敛到统一编码基线，后续不再出现同类机型差异问题。',
    keys: ['H.264 profile/level', 'SDP fmtp 协商', 'packetization-mode', '硬解能力', 'setCodecPreferences']
  },
  {
    title: '一开播就糊、清晰度来回抖：带宽估计与层选择抖动',
    severity: '体验质量',
    severityType: 'warning',
    impact:
      '弱网用户开播即糊，清晰度在几秒内反复升降；卡顿率高于预期，主观体验是"不稳定"而不是"差"。',
    symptom: [
      'availableOutgoingBitrate 长时间停留在低位，qualityLimitationReason 显示由 bandwidth 主导（而非 cpu）',
      'simulcast 层在相邻两档之间来回切换，切换间隔常小于 2 秒、没有稳定期',
      '首秒码率冲高后立刻回落 —— 表现为"刚连接上画面挺好，随即变糊"'
    ],
    diagnose: [
      '连续采样 getStats()：同时记录 availableOutgoingBitrate、targetBitrate、qualityLimitationReason，确认限制来自带宽还是 CPU',
      '拉出层切换时间线：切换频繁且无滞回窗口 → 典型的"无滞回"抖动',
      '核对首帧起的码率曲线：起始即推高码率 → 触发丢包 → 拥塞控制快速降档，形成"开播即糊"',
      '确认是否启用 simulcast：单流方案无法按接收端能力降档，只能整体降质量'
    ],
    rootCause:
      '三个问题叠加：① 起始码率猜测过高，开局就撞拥塞控制；② 层选择没有滞回窗口，带宽在阈值附近抖动时反复切换；③ 缺少关键帧请求（PLI/FIR）与丢包恢复策略，恢复只能等下一个 IDR。',
    fix: [
      '起始码率显著下调后再按爬升曲线提升，宁可"先稳后清"，避免开局冲高被打回',
      '启用 simulcast（多档）或 SVC，由 SFU 按订阅端带宽选层；层切换加**滞回窗口（≥3~5s）**，杜绝反复横跳',
      '丢包恢复：开启 NACK/RTX，配合按需 PLI/FIR（关键帧请求必须限频，否则会造成请求风暴）',
      '明确 degradationPreference：移动端优先保帧率（resolution 可降），大屏端优先保分辨率',
      '建 QoE 看板：首帧时延 P95、卡顿率、平均码率、层分布、qualityLimitationReason 占比'
    ],
    result:
      '卡顿率显著下降，清晰度切换次数大幅减少；体验从"忽好忽坏"变为"稳定可用"，且问题定位有了数据抓手。',
    keys: ['TWCC/GCC 带宽估计', 'Simulcast / SVC', '层选择滞回', 'NACK/RTX、PLI/FIR', 'degradationPreference']
  }
]

/** 症状 → 排查起点 → 常见根因 的快速定位表（值班时先用它缩小范围）。 */
export interface SymptomRow {
  symptom: string
  check: string
  cause: string
}

export const symptomTable: SymptomRow[] = [
  { symptom: '一直 connecting，最终 failed', check: 'webrtc-internals 的候选类型', cause: '没有 relay 候选 / TURN 不通' },
  { symptom: '连上几秒后自己断开', check: 'DTLS 状态与证书指纹', cause: '证书异常、系统时钟偏差、中间盒干扰' },
  { symptom: '有声音、没画面', check: 'framesDecoded 是否增长', cause: 'profile/level 协商不匹配，解码失败' },
  { symptom: '没声音、卡顿或变调', check: '音频 codec 与 jitter buffer', cause: 'Opus 参数不匹配 / 抖动缓冲不足' },
  { symptom: '画面糊且频繁升降', check: 'availableOutgoingBitrate、层切换节奏', cause: '起始码率过高 / 层选择无滞回' },
  { symptom: '切网络（WiFi↔4G）后卡死', check: 'ICE 状态是否停住', cause: '需要触发 ICE restart' },
  { symptom: '多路并发时整体变卡', check: 'qualityLimitationReason、CPU 占用', cause: '编码 CPU 瓶颈，未启用硬编' },
  { symptom: '首帧特别慢', check: '首个 IDR 到达时间', cause: 'GOP 过长 / 未做关键帧缓存' }
]

/** 转码链路的一段。 */
export interface ChainStage {
  name: string
  detail: string
}

export const transcodeChain: ChainStage[] = [
  { name: '接入', detail: 'RTMP / SRT / WHIP(WebRTC) 推流，鉴权 + 流名规整' },
  { name: '解封装 / 解码', detail: '提取音视频轨、时间基归一，必要时先解码' },
  { name: '转码', detail: 'GPU 硬编优先后 CPU 兜底，按档位并行出流' },
  { name: '封装切片', detail: 'fMP4/TS 切片，HLS / LL-HLS / DASH 多封装' },
  { name: '分发', detail: 'CDN 回源、多档位并行、切片缓存与预热' },
  { name: '播放', detail: 'ABR 自适应、首帧优化、按场景选超低延迟通道' }
]

/** 转码难点 → 亮点。 */
export interface TranscodePoint {
  title: string
  pain: string
  highlight: string
  detail: string[]
  tags: string[]
}

export const transcodePoints: TranscodePoint[] = [
  {
    title: '算力成本：1 路进、N 档出',
    pain: '一次直播要同时产出 360p～1080p 多档，算力按档位线性放大；峰值并发时扩容跟不上，低谷期又想省机器。',
    highlight: '能转发就不转码：Simulcast/SVC 由 SFU 转发，只有需要改变编码格式或分辨率时才真正转码。',
    detail: [
      '硬件编码优先（NVENC / QSV / VAAPI），同画质下 CPU 占用可降一个数量级',
      '按需转码：只为"确实有人观看"的档位启动转码任务，观看触发、无人则停',
      'GPU 池化调度 + 任务亲和（同一路流固定节点），避免上下文频繁切换',
      '宁可"少档位 + 智能码率"，也不要"档位齐全但每档都吃不饱"'
    ],
    tags: ['NVENC/QSV', 'Simulcast/SVC', '按需转码', 'GPU 池']
  },
  {
    title: '秒开：观众不愿等第一个关键帧',
    pain: '切片没有对齐关键帧时，播放器拿到切片也必须等到下一个 IDR 才能出画面，首帧时延直接被 GOP 长度放大。',
    highlight: '所有档位的 IDR 严格对齐 + 关键帧缓存，新观众从最近的关键帧切片开始播。',
    detail: [
      '统一 GOP 与切片边界，多档位在同一时间点切 IDR，保证任意档位都能立即起播',
      '维护 GOP/切片缓存，新观众直接投喂当前 GOP 的起始切片',
      '起始档位按带宽探测从低档起播，稳定后再升档（快速出画 > 一开始就高清）',
      '首帧优化单独设指标（首帧时延 P95），不与"平均延迟"混在一起看'
    ],
    tags: ['IDR 对齐', 'GOP 缓存', '起始码率探测', '首帧 P95']
  },
  {
    title: '延迟与流畅的取舍',
    pain: 'HLS 天生带切片延迟；追求低延迟就必须缩短切片与缓冲，代价是卡顿概率上升。二者在单一路径上无法同时最优。',
    highlight: '按场景分层选路：互动连麦走 WebRTC（百毫秒级），大规模观看走 LL-HLS（秒级），点播回看走标准 HLS。',
    detail: [
      '互动场景（连麦、答题）用 WebRTC/SFU，端到端延迟可压到数百毫秒',
      '大规模单向观看用 LL-HLS，用部分切片与阻塞式加载换取 2~5 秒延迟',
      '切片长度、播放器缓冲按场景配置，不做"一套参数打天下"',
      '把"端到端延迟"作为可观测指标，而不是只在接入层看推流是否正常'
    ],
    tags: ['WebRTC/SFU', 'LL-HLS', '切片长度', '分层选路']
  },
  {
    title: '多档切换无缝',
    pain: '切片边界不对齐或时间基不一致时，ABR 切换会出现花屏、回退、卡顿 —— 观众感知为"看直播突然跳了一下"。',
    highlight: '统一时间基 + 切片边界严格 IDR 对齐，切换点提前预取目标档位。',
    detail: [
      '所有档位共用同一时间基与切片序列号，可一一对应替换',
      'ABR 策略同时考虑带宽与缓冲水位（双因子），避免带宽瞬时抖动就切档',
      '切换点预取：探测到需要升档时提前拉取目标档位切片，减少切换空窗',
      '对切换做埋点：统计切换次数、切换耗时与切换后的卡顿率'
    ],
    tags: ['时间基统一', '切片对齐', 'ABR 双因子', '切换埋点']
  },
  {
    title: '音视频同步',
    pain: '转码后 PTS/DTS 可能漂移，变帧率、音频重采样都会引入偏差，最终表现为唇音不同步。',
    highlight: '统一时钟源 + 音频轻微拉伸对齐，转码后自动校验 A/V offset。',
    detail: [
      '以统一时间基归一所有输入轨，禁止各档位各自为政',
      '音频用 atempo 这类不变调的拉伸做微调，避免丢帧造成的断裂感',
      '转码完成后落库记录 A/V offset，超出阈值直接判失败并降级重试',
      '端到端做 lip-sync 抽检，把"不同步"变成可测量的指标'
    ],
    tags: ['PTS/DTS', 'atempo 拉伸', 'offset 校验', 'lip-sync']
  },
  {
    title: '终端兼容性',
    pain: '浏览器与终端对编码（H.264 level、AV1）、封装（fMP4/TS）的支持差异很大，一套输出无法覆盖所有观众。',
    highlight: '能力探测 + 多封装并行 + 统一兼容基线（Baseline 3.1 起）。',
    detail: [
      '编码基线从兼容性最好的档位起步（如 Baseline 3.1），高画质档位单独提供',
      '同时产出 fMP4（现代浏览器）与 TS（老设备/机顶盒）封装',
      '播放端做能力探测，服务端按探测结果返回合适的播放列表',
      '把"机型/浏览器 × 播放成功率"做成矩阵看板，兼容问题不靠猜'
    ],
    tags: ['H.264 level', 'fMP4/TS', '能力探测', '兼容矩阵']
  },
  {
    title: '稳定性：坏输入不能把服务打挂',
    pain: '异常输入（超大文件、损坏文件、异常时间戳）会让转码器卡死或崩溃，单点故障影响整批任务。',
    highlight: '进程隔离 + 超时熔断 + 分级重试，输入先预检再转码。',
    detail: [
      '每个任务独立进程/容器，资源限额（CPU、内存、时长）并强制超时熔断',
      '转码前用 ffprobe 预检：时长、轨道、时间戳异常直接拒绝并给出可读原因',
      '分级重试：同参数失败不盲目重试；降级参数（更低档位/更低帧率）再试一次',
      '失败原因结构化落库，值班可读，不依赖翻日志'
    ],
    tags: ['进程隔离', '超时熔断', 'ffprobe 预检', '分级重试']
  },
  {
    title: '存储与分发成本',
    pain: '切片数量随观看时长线性增长，回源放大与冷切片堆积会迅速推高存储与带宽成本。',
    highlight: '生命周期策略 + CDN 分层缓存，直播切片分钟级回收、按需转点播。',
    detail: [
      '直播切片设置短生命周期（分钟级清理），只有被点播/录制标记的内容才转长期存储',
      'CDN 分层缓存 + 热点预热，降低回源放大',
      '多档位切片按实际观看分布保留（冷档位更早回收）',
      '把"单路成本"（算力 + 带宽 + 存储）作为一项运营指标持续跟踪'
    ],
    tags: ['生命周期', 'CDN 分层', '回源放大', '单路成本']
  }
]

/** 降本三板斧（结论性要点）。 */
export const costLever: { name: string; desc: string }[] = [
  { name: '免转码', desc: 'Simulcast/SVC 直接转发，不改变编码格式就不解码转码' },
  { name: '按需转码', desc: '只为有人观看的档位启动任务，无观看即停' },
  { name: '硬件编码', desc: 'GPU 硬编替代 CPU 软编，同画质下算力占用大幅下降' }
]

/** QoE 指标（对外承诺与对内排查共用同一套口径）。 */
export const qoeMetrics: { name: string; why: string }[] = [
  { name: 'join 成功率', why: '能不能看 —— 连通性问题的最终体现' },
  { name: '首帧时延 P95', why: '愿不愿等 —— 秒开优化的直接指标' },
  { name: '卡顿率', why: '看得顺不顺 —— 弱网与带宽策略的效果' },
  { name: '端到端延迟', why: '能不能互动 —— 决定业务形态（连麦/答题）的可行性' },
  { name: '转码失败率', why: '供给侧稳定性 —— 坏输入与资源不足的暴露面' },
  { name: '单路成本', why: '能不能规模化 —— 算力 + 带宽 + 存储的综合账' }
]
