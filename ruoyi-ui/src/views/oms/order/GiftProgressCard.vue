<template>
  <article class="gift-card">
    <div class="gift-title"><div><strong>{{ line.goods_name || line.sku_sn }}</strong><el-tag size="mini" effect="plain">赠品</el-tag><p class="muted">{{ line.sku_sn }} · 已发 {{ line.shipped_quantity || 0 }} {{ unit }}</p></div><el-tag size="small" :type="needsAttention ? 'warning' : 'info'">{{ stateLabel }}</el-tag></div>
    <p v-if="line.gift_parent_sku && line.gift_parent_sku!==line.sku_sn" class="scope-note">属于赠品：{{ line.gift_parent_name || line.gift_parent_sku }} · 原赠 {{ line.gift_parent_quantity }} {{ line.gift_parent_unit }}；此卡按实际库存商品记录数量。</p>
    <p v-if="refundId" class="scope-note">原订单累计判定，包含其他售后影响；以下应退数量不代表本退款新增应退。</p>
    <div class="numbers"><div><span>原赠送</span><strong>{{ line.quantity }} {{ unit }}</strong></div><div><span>当前可保留</span><strong>{{ line.eligible_quantity }} {{ unit }}</strong></div><div><span>{{ progress.label }}</span><strong>{{ progress.quantity }} {{ unit }}</strong></div></div>
    <p v-if="line.pending_assessment" class="forecast">售后申请尚未确认：预计可保留 {{ line.projected_eligible_quantity }} {{ unit }}，尚未确认的申请不直接取消或追退。</p>
    <div v-if="needsAttention" class="next"><div><strong>下一步：{{ line.next_action }}</strong><p v-if="line.cancel_required > 0 && line.return_required > 0" class="muted">未发待取消 {{ line.cancel_required }} {{ unit }}，已发待退回 {{ line.return_required }} {{ unit }}，分别处理。</p><p v-if="line.return_required > 0" class="muted">其中尚未安排退回 {{ line.unarranged_quantity || 0 }} {{ unit }}</p></div><div class="actions">
      <el-button v-if="line.cancel_required > 0" v-hasPermi="['order:gift:execute']" size="small" type="primary" :loading="saving" @click="$emit('cancel', line)">{{ line.needs_cutoff ? '撤回后取消赠品' : '取消失效赠品' }}</el-button>
      <el-button v-if="line.unarranged_quantity > 0" v-hasPermi="['order:gift:execute']" size="small" :type="line.cancel_required > 0 ? 'default' : 'primary'" @click="$emit('return', line)">安排赠品退回</el-button>
      <el-button v-if="line.unarranged_quantity > 0" v-hasPermi="['order:gift:waive']" size="small" type="text" @click="$emit('waive', line)">确认不再追回</el-button>
    </div></div>
    <div v-if="plans.length" class="return-summary"><div><strong>{{ refundId ? '本退款退回安排' : '已关联退回安排' }}</strong><p class="muted">{{ plans.length }} 项安排 · 计划 {{ total('quantity') }} {{ unit }} · 已确认实收 {{ total('accepted_quantity') }} {{ unit }} · 已入账 {{ total('posted_quantity') }} {{ unit }}</p></div><div class="actions"><el-button v-for="target in returnTargets" :key="target.id" size="small" @click="$emit('refund', target.id)">{{ returnTargets.length === 1 ? '查看退回与包裹' : '退款 ' + target.name }}</el-button></div></div>
    <p v-else-if="refundId" class="muted">本退款尚未关联该赠品的退回安排。</p>
    <el-collapse class="evidence"><el-collapse-item title="赠送来源与数量明细" name="source"><p class="muted">{{ origin || '采用下单时的赠送方案' }}</p><el-descriptions :column="2" size="small" border><el-descriptions-item label="已取消">{{ line.cancelled_quantity || 0 }} {{ unit }}</el-descriptions-item><el-descriptions-item label="已退入账">{{ line.returned_quantity || 0 }} {{ unit }}</el-descriptions-item><el-descriptions-item label="已确认不再追回">{{ line.waived_quantity || 0 }} {{ unit }}</el-descriptions-item><el-descriptions-item label="OMS 赠品明细">#{{ line.id }}</el-descriptions-item></el-descriptions><p class="muted">赠品明细独立记录，不占用平台子单号；已发事实、赠送资格和库存入账分别核对。</p></el-collapse-item></el-collapse>
  </article>
</template>
<script>
import { giftNeedsAttention, giftStateLabel, scopedGiftPlans } from './giftPresentation'
export default {
  props: { line: { type: Object, required: true }, origin: { type: String, default: '' }, refundId: [Number, String], saving: Boolean, refunds: { type: Array, default: () => [] } },
  computed: {
    unit() { return this.line.stocked_bundle_snapshot ? '套' : '件' },
    needsAttention() { return giftNeedsAttention(this.line) },
    stateLabel() { return giftStateLabel(this.line) },
    progress() {
      const line = this.line
      if (Number(line.return_required) > 0) return { label: '当前待退回', quantity: line.return_required }
      if (Number(line.cancel_required) > 0) return { label: '当前待取消', quantity: line.cancel_required }
      if (Number(line.returned_quantity) > 0) return { label: '已退入账', quantity: line.returned_quantity }
      if (Number(line.waived_quantity) > 0) return { label: '已确认不再追回', quantity: line.waived_quantity }
      if (Number(line.cancelled_quantity) > 0) return { label: '已取消', quantity: line.cancelled_quantity }
      return { label: '当前待处理', quantity: 0 }
    },
    returnTargets() {
      return [...new Set(this.plans.map(plan => String(plan.refund_id)))].map(id => {
        const refund = this.refunds.find(row => String(row.id) === id)
        return { id, name: refund ? refund.refund_sn : '#' + id }
      })
    },
    plans() { return scopedGiftPlans(this.line, this.refundId) }
  },
  methods: { total(key) { return this.plans.reduce((sum, plan) => sum + Number(plan[key] || 0), 0) } }
}
</script>
<style scoped>
.gift-card{border:1px solid #e1e8f0;border-radius:8px;padding:18px;margin:14px 0;overflow-wrap:anywhere}.gift-title,.next,.return-summary{display:flex;justify-content:space-between;align-items:center;gap:16px}.gift-title strong{font-size:15px;margin-right:10px}.muted{color:#758397;font-size:12px;line-height:1.8;margin:6px 0}.numbers{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;background:#f5f7fa;border-radius:6px;padding:14px;margin:14px 0}.numbers span{color:#758397;font-size:12px}.numbers strong{display:block;font-size:18px;font-weight:500;color:#293b50;margin-top:6px}.scope-note,.forecast{padding:10px 12px;font-size:12px;line-height:1.8;border-radius:4px;background:#f4f7fb;color:#66768b}.forecast{background:#fff7e8;color:#986818}.next{font-size:13px;line-height:1.8;margin:16px 0}.actions{display:flex;align-items:center;justify-content:flex-end;gap:8px;flex-wrap:wrap;flex-shrink:0}.actions .el-button{margin:0}.return-summary{border-top:1px solid #edf0f5;margin-top:16px;padding-top:14px;font-size:13px}.evidence{margin-top:14px}.evidence ::v-deep .el-collapse-item__header{color:#758397;font-size:12px}@media(max-width:850px){.next,.return-summary{align-items:flex-start;flex-direction:column}.actions{justify-content:flex-start}}@media(max-width:520px){.gift-card{padding:12px}.gift-title{align-items:flex-start;flex-wrap:wrap}.numbers{padding:10px;gap:6px}.numbers strong{font-size:16px}}
</style>
