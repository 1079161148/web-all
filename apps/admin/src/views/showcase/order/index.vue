<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { NButton, NCard, NInputNumber, NSpace, NTag, NTimeline, NTimelineItem, ProDescriptions, feedback } from '@admin/ui'

/**
 * 购物车与订单结算：多店铺拆单 + 优惠券叠加 + 运费模板 + 状态回滚。
 *
 * <h3>结算引擎的三个经典边界（全部显式处理）</h3>
 * <ol>
 *   <li><b>券的分摊</b>：满减/折扣优惠要按"金额占比"摊到每个商品上（退款按
 *       实付退，必须知道每件商品摊了多少券）。占比分摊会产生**尾差**
 *       （0.01 元级别），约定"最大金额项兜底余数"—— Σ分摊 必须恒等于券额，
 *       否则财务对不上账</li>
 *   <li><b>券的适用范围</b>：券只对 participating 店铺生效。门槛判定用
 *       <b>适用店铺的小计之和</b>，不是整单小计 —— 否则"跨店凑单再用券"
 *       会在拆单后变成不满足门槛的脏数据</li>
 *   <li><b>互斥叠加</b>：满减与折扣互斥（叠加会出现负毛利），前端选择时
 *       直接禁用冲突项；赠品券独立，可与任一叠加但赠品不计运费重量</li>
 * </ol>
 *
 * <h3>运费模板</h3>
 * 首重(1kg) + 续重按 kg 向上取整；单店满额包邮按**本店小计**判定。
 * 赠品重量不计 —— 否则"买赠"活动会让运费倒挂。
 */

interface CartItem {
  id: number
  shop: string
  name: string
  price: number
  qty: number
  weightG: number
}

const cart = reactive<CartItem[]>([
  { id: 1, shop: '官方旗舰店', name: '无线降噪耳机', price: 899, qty: 1, weightG: 350 },
  { id: 2, shop: '官方旗舰店', name: 'type-c 充电线', price: 39, qty: 2, weightG: 60 },
  { id: 3, shop: '运动户外店', name: '跑步鞋', price: 429, qty: 1, weightG: 900 },
  { id: 4, shop: '运动户外店', name: '运动水壶', price: 59, qty: 1, weightG: 250 },
  { id: 5, shop: '图书专营店', name: '架构整洁之道', price: 108, qty: 1, weightG: 700 }
])

interface Coupon {
  id: string
  kind: 'FULL_OFF' | 'DISCOUNT' | 'GIFT'
  label: string
  threshold: number
  amount: number
  rate?: number
  cap?: number
  gift?: string
}

const coupons: Coupon[] = [
  { id: 'c1', kind: 'FULL_OFF', label: '跨店满 800 减 100', threshold: 800, amount: 100 },
  { id: 'c2', kind: 'DISCOUNT', label: '全场 95 折（最多减 200）', threshold: 0, amount: 0, rate: 0.95, cap: 200 },
  { id: 'c3', kind: 'FULL_OFF', label: '运动户外店满 400 减 50', threshold: 400, amount: 50 },
  { id: 'c4', kind: 'GIFT', label: '下单赠定制帆布袋', threshold: 0, amount: 0, gift: '定制帆布袋 ×1' }
]

/** 选中的券。满减与折扣互斥由 toggle 时强制约束（叠加会出现负毛利）。 */
const selectedCoupons = ref<string[]>(['c1'])

function toggleCoupon(coupon: Coupon): void {
  const selected = selectedCoupons.value.includes(coupon.id)
  if (selected) {
    selectedCoupons.value = selectedCoupons.value.filter((id) => id !== coupon.id)
    return
  }
  if (coupon.kind === 'FULL_OFF') {
    // 满减与折扣互斥：选满减则移除折扣
    selectedCoupons.value = [
      ...selectedCoupons.value.filter((id) => {
        const other = coupons.find((item) => item.id === id)
        return other?.kind !== 'DISCOUNT'
      }),
      coupon.id
    ]
    return
  }
  if (coupon.kind === 'DISCOUNT') {
    selectedCoupons.value = [
      ...selectedCoupons.value.filter((id) => {
        const other = coupons.find((item) => item.id === id)
        return other?.kind !== 'FULL_OFF'
      }),
      coupon.id
    ]
    return
  }
  selectedCoupons.value = [...selectedCoupons.value, coupon.id]
}

/** 运费模板（按店铺）。真实系统里这是可配置的计费规则表。 */
const freightTemplates: Record<string, { firstFee: number; unitFee: number; freeOver: number }> = {
  官方旗舰店: { firstFee: 8, unitFee: 2, freeOver: 999 },
  运动户外店: { firstFee: 10, unitFee: 3, freeOver: 599 },
  图书专营店: { firstFee: 6, unitFee: 1, freeOver: 199 }
}

/** 把金额分摊到一组商品：按金额占比 + 最大项兜底尾差。Σ结果 === 总额。 */
function allocate(total: number, items: Array<{ id: number; amount: number }>): Map<number, number> {
  const result = new Map<number, number>()
  if (total <= 0 || items.length === 0) {
    items.forEach((item) => result.set(item.id, 0))
    return result
  }
  const sum = items.reduce((acc, item) => acc + item.amount, 0)
  let allocated = 0
  for (const item of items) {
    const share = Math.round((total * item.amount) / sum)
    result.set(item.id, share)
    allocated += share
  }
  // 尾差给金额最大的项：占比最高，分摊误差相对最小
  const diff = total - allocated
  if (diff !== 0) {
    const maxItem = items.reduce((max, item) => (item.amount > max.amount ? item : max))
    result.set(maxItem.id, (result.get(maxItem.id) ?? 0) + diff)
  }
  return result
}

// ---------------------------------------------------------------------
// 结算引擎（纯函数：输入购物车 + 券，输出子订单）
// ---------------------------------------------------------------------

interface OrderLine {
  itemId: number
  name: string
  qty: number
  original: number
  couponShare: number
  payable: number
}

interface SubOrder {
  shop: string
  lines: OrderLine[]
  subtotal: number
  couponOff: number
  freight: number
  payable: number
  gift?: string
  freeShipping: boolean
}

interface Settlement {
  subOrders: SubOrder[]
  totalOriginal: number
  totalCouponOff: number
  totalFreight: number
  totalPayable: number
  warnings: string[]
}

function computeSettlement(): Settlement {
  const warnings: string[] = []
  const shops = [...new Set(cart.map((item) => item.shop))]

  const applied = selectedCoupons.value
    .map((id) => coupons.find((coupon) => coupon.id === id))
    .filter((coupon): coupon is Coupon => Boolean(coupon))

  // 预先算出每个店铺的小计，用于券门槛判定
  const shopSubtotals = new Map<string, number>()
  for (const shop of shops) {
    shopSubtotals.set(
      shop,
      cart.filter((item) => item.shop === shop).reduce((acc, item) => acc + item.price * item.qty, 0)
    )
  }

  const subOrders: SubOrder[] = []
  for (const shop of shops) {
    const items = cart.filter((item) => item.shop === shop)
    const subtotal = shopSubtotals.get(shop) ?? 0

    // 本店适用的券：满减按"本店小计"复核门槛（跨店券的门槛已在选购时按合计判定）
    let couponOff = 0
    let gift: string | undefined
    for (const coupon of applied) {
      if (coupon.kind === 'GIFT') {
        gift = coupon.gift
        continue
      }
      if (coupon.threshold > 0 && coupon.label.includes('跨店')) {
        // 跨店券：门槛在"券适用店铺的合计"上判定，金额只摊到达标店铺
        const eligibleTotal = [...shopSubtotals.entries()]
          .filter(([name]) => name === shop || (shopSubtotals.get(name) ?? 0) > 0)
          .reduce((acc, [, value]) => acc + value, 0)
        if (eligibleTotal < coupon.threshold) {
          warnings.push(`「${coupon.label}」未达门槛（${eligibleTotal} < ${coupon.threshold}），本单未使用`)
          continue
        }
      } else if (subtotal < coupon.threshold) {
        continue
      }
      const off =
        coupon.kind === 'DISCOUNT'
          ? Math.min(Math.round(subtotal * (1 - (coupon.rate ?? 1))), coupon.cap ?? Number.MAX_SAFE_INTEGER)
          : coupon.amount
      couponOff += Math.min(off, subtotal)
    }
    couponOff = Math.min(couponOff, subtotal)

    // 优惠分摊到行（尾差兜底）
    const shares = allocate(
      couponOff,
      items.map((item) => ({ id: item.id, amount: item.price * item.qty }))
    )

    // 运费：首重 + 续重向上取整；满额包邮；赠品不计重
    const template = freightTemplates[shop]
    const weightKg = items.reduce((acc, item) => acc + (item.weightG * item.qty) / 1000, 0)
    const freeShipping = template.freeOver > 0 && subtotal >= template.freeOver
    const freight = freeShipping
      ? 0
      : template.firstFee + (weightKg > 1 ? Math.ceil(weightKg - 1) * template.unitFee : 0)

    const lines: OrderLine[] = items.map((item) => {
      const original = item.price * item.qty
      const share = shares.get(item.id) ?? 0
      return {
        itemId: item.id,
        name: item.name,
        qty: item.qty,
        original,
        couponShare: share,
        payable: original - share
      }
    })

    subOrders.push({
      shop,
      lines,
      subtotal,
      couponOff,
      freight,
      payable: subtotal - couponOff + freight,
      gift,
      freeShipping
    })
  }

  const totalOriginal = subOrders.reduce((acc, order) => acc + order.subtotal, 0)
  const totalCouponOff = subOrders.reduce((acc, order) => acc + order.couponOff, 0)
  const totalFreight = subOrders.reduce((acc, order) => acc + order.freight, 0)
  return {
    subOrders,
    totalOriginal,
    totalCouponOff,
    totalFreight,
    totalPayable: totalOriginal - totalCouponOff + totalFreight,
    warnings
  }
}

const settlement = computed(() => computeSettlement())

/** ProDescriptions 的固定列定义（items 定义形状，data 提供值）。 */
const SUB_SUMMARY_ITEMS = [
  { key: 'freight', label: '运费' },
  { key: 'summary', label: '本店小计' }
]

const TOTAL_ITEMS = [
  { key: 'original', label: '商品总额' },
  { key: 'coupon', label: '优惠合计' },
  { key: 'freight', label: '运费合计' },
  { key: 'payable', label: '应付总额' }
]

function updateQty(item: CartItem, qty: number | null): void {
  if (qty === null || qty < 1) {
    // 数量清空/非法 → 移除该商品（购物车的约定：数量为 0 即删除）
    const index = cart.findIndex((entry) => entry.id === item.id)
    if (index >= 0) {
      cart.splice(index, 1)
    }
    feedback.info(`已移除「${item.name}」`)
    return
  }
  item.qty = qty
}

// ---------------------------------------------------------------------
// 订单状态机（库存锁定与回滚）
// ---------------------------------------------------------------------

const orderState = ref<'EDITING' | 'LOCKED' | 'PAID' | 'REFUNDED'>('EDITING')
const orderEvents = ref<Array<{ title: string; detail: string; type: 'default' | 'info' | 'success' | 'error' }>>([
  { title: '创建订单', detail: '购物车结算完成，生成待支付订单', type: 'default' }
])

function transition(action: 'lock' | 'pay' | 'refund'): void {
  if (action === 'lock') {
    if (orderState.value !== 'EDITING') {
      return
    }
    orderState.value = 'LOCKED'
    orderEvents.value.push({
      title: '库存锁定',
      detail: `锁定 ${settlement.value.subOrders.reduce((acc, order) => acc + order.lines.reduce((sum, line) => sum + line.qty, 0), 0)} 件商品（预占 15 分钟）`,
      type: 'info'
    })
    return
  }
  if (action === 'pay') {
    if (orderState.value !== 'LOCKED') {
      return
    }
    orderState.value = 'PAID'
    orderEvents.value.push({ title: '支付成功', detail: `实付 ¥${settlement.value.totalPayable}，库存扣减`, type: 'success' })
    return
  }
  // 退款：按行级实付逆向回滚库存与优惠 —— 优惠分摊在这里兑现价值
  if (orderState.value !== 'PAID') {
    return
  }
  orderState.value = 'REFUNDED'
  const totalGifts = settlement.value.subOrders.filter((order) => order.gift).length
  orderEvents.value.push({
    title: '售后退款',
    detail: `按行级实付退回 ¥${settlement.value.totalPayable}，释放库存${totalGifts > 0 ? '，赠品随单退回' : ''}`,
    type: 'error'
  })
}
</script>

<template>
  <div class="order">
    <div class="order__layout">
      <NCard title="① 购物车（多店铺）" :bordered="false" class="order__card">
        <div v-for="item in cart" :key="item.id" class="order__line">
          <NTag size="small" :bordered="false">{{ item.shop }}</NTag>
          <span class="order__line-name">{{ item.name }}</span>
          <span class="order__line-price">¥{{ item.price }}</span>
          <NInputNumber
            :value="item.qty"
            size="small"
            :min="0"
            :max="99"
            class="order__line-qty"
            @update:value="(value: number | null) => updateQty(item, value)"
          />
          <span class="order__line-weight">{{ ((item.weightG * item.qty) / 1000).toFixed(2) }}kg</span>
        </div>
      </NCard>

      <NCard title="② 优惠券（满减与折扣互斥）" :bordered="false" class="order__card">
        <div
          v-for="coupon in coupons"
          :key="coupon.id"
          class="order__coupon"
          :class="{ 'order__coupon--active': selectedCoupons.includes(coupon.id) }"
          @click="toggleCoupon(coupon)"
        >
          <span>{{ coupon.label }}</span>
          <NTag v-if="selectedCoupons.includes(coupon.id)" size="small" type="success">已选</NTag>
        </div>
        <p class="order__hint">
          互斥在前端直接约束（点满减自动取消折扣），
          但后端仍会复核 —— 前端约束是体验，不是边界。
        </p>
      </NCard>
    </div>

    <NCard title="③ 结算结果（拆单明细）" :bordered="false" class="order__card">
      <div v-for="sub in settlement.subOrders" :key="sub.shop" class="order__sub">
        <div class="order__sub-head">
          <strong>{{ sub.shop }}</strong>
          <NTag v-if="sub.freeShipping" size="small" type="success">满额包邮</NTag>
          <NTag v-if="sub.gift" size="small" type="warning">赠品：{{ sub.gift }}</NTag>
          <span class="order__sub-payable">应付 ¥{{ sub.payable }}</span>
        </div>
        <div v-for="line in sub.lines" :key="line.itemId" class="order__sub-line">
          <span>{{ line.name }} ×{{ line.qty }}</span>
          <span>¥{{ line.original }}</span>
          <span v-if="line.couponShare > 0">− 券 ¥{{ line.couponShare }}</span>
          <strong>实付 ¥{{ line.payable }}</strong>
        </div>
        <ProDescriptions
          :items="SUB_SUMMARY_ITEMS"
          :data="{
            freight: sub.freeShipping ? '包邮' : `¥${sub.freight}（首重 + 续重按模板计费）`,
            summary: `¥${sub.subtotal} − 券 ¥${sub.couponOff} + 运费 ¥${sub.freight} = ¥${sub.payable}`
          }"
          :column="1"
          size="small"
        />
      </div>

      <ProDescriptions
        class="order__total"
        :items="TOTAL_ITEMS"
        :data="{
          original: `¥${settlement.totalOriginal}`,
          coupon: `− ¥${settlement.totalCouponOff}`,
          freight: `¥${settlement.totalFreight}`,
          payable: `¥${settlement.totalPayable}`
        }"
        :column="4"
        size="small"
      />

      <NTag v-for="warning in settlement.warnings" :key="warning" type="warning" size="small" class="order__warning">
        {{ warning }}
      </NTag>
    </NCard>

    <NCard title="④ 订单状态机（库存锁定 / 支付 / 退款回滚）" :bordered="false" class="order__card">
      <NSpace class="order__actions">
        <NButton
          size="small"
          type="primary"
          :disabled="orderState !== 'EDITING'"
          @click="transition('lock')"
        >
          提交订单（锁定库存）
        </NButton>
        <NButton size="small" type="primary" :disabled="orderState !== 'LOCKED'" @click="transition('pay')">
          模拟支付成功
        </NButton>
        <NButton size="small" type="error" :disabled="orderState !== 'PAID'" @click="transition('refund')">
          申请退款（回滚）
        </NButton>
        <NTag size="small">{{ orderState }}</NTag>
      </NSpace>

      <NTimeline class="order__timeline">
        <NTimelineItem
          v-for="(event, index) in orderEvents"
          :key="index"
          :title="event.title"
          :content="event.detail"
          :type="event.type"
        />
      </NTimeline>

      <p class="order__hint">
        状态机的意义：每个动作只在合法前态下可用（按钮 disabled 由状态派生）。
        退款按<b>行级实付</b>逆向回滚 —— 这正是"优惠分摊到行"必须精确到分的原因；
        未支付的锁单超时（15 分钟）由库存服务自动释放，不依赖用户操作。
      </p>
    </NCard>
  </div>
</template>

<style scoped>
.order__layout {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 16px;
  margin-bottom: 12px;
}

@media (max-width: 1100px) {
  .order__layout {
    grid-template-columns: 1fr;
  }
}

.order__card {
  margin-bottom: 12px;
}

.order__line {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px dashed var(--wa-border-light, #f0f2f5);
}

.order__line-name {
  flex: 1;
  font-size: var(--wa-font-size-sm, 13px);
}

.order__line-price {
  width: 80px;
  font-variant-numeric: tabular-nums;
}

.order__line-qty {
  width: 110px;
}

.order__line-weight {
  width: 70px;
  text-align: right;
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: 12px;
}

.order__coupon {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  border: 1px dashed var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  font-size: var(--wa-font-size-sm, 13px);
}

.order__coupon--active {
  border-style: solid;
  border-color: var(--wa-color-success, #18a058);
  background: color-mix(in srgb, var(--wa-color-success, #18a058) 5%, transparent);
}

.order__hint {
  margin: 8px 0 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}

.order__sub {
  padding: 10px 12px;
  margin-bottom: 10px;
  border: 1px solid var(--wa-border-light, #f0f2f5);
  border-radius: var(--wa-radius-md, 4px);
}

.order__sub-line {
  display: flex;
  gap: 12px;
  padding: 3px 0;
  font-size: var(--wa-font-size-sm, 13px);
}

.order__sub-line > :first-child {
  flex: 1;
}

.order__sub-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.order__sub-payable {
  margin-left: auto;
  font-weight: 600;
  color: var(--wa-color-primary, #2563eb);
  font-variant-numeric: tabular-nums;
}

.order__total {
  margin-top: 8px;
}

.order__warning {
  margin: 6px 6px 0 0;
}

.order__actions {
  margin-bottom: 16px;
}

.order__timeline {
  margin-bottom: 8px;
}
</style>

