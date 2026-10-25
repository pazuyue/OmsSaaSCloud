<template>
  <section v-if="conflict" class="refund-amount-resolution" aria-label="退款金额冲突处理">
    <el-alert title="申请金额超出可退额度，需先处理平台申请" type="warning" :closable="false" show-icon />
    <dl><div v-for="field in fields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ money(field.value) }}</dd></div></dl>
    <p>人工审核仍需遵守累计退款额度。当前申请超出 {{ money(conflict.excess) }}，填写审核依据或协商凭证不能改变平台申请金额。</p>
    <ol><li>与买家协商，将平台本次申请调整为不超过 {{ money(conflict.limit) }}，或撤销后重新申请。</li><li>在“平台处理与资金”查询平台最新状态；新申请同步后重新检查额度，再按当前规则处理。</li><li>如不同意本次申请，可进入平台处理，按平台可用操作提交协商或拒绝，并提供所需凭证。</li></ol>
    <div class="resolution-actions"><el-button type="primary" size="small" @click="$emit('platform')">查看平台处理</el-button><el-button size="small" @click="$emit('refresh')">刷新已同步金额</el-button></div>
    <p class="resolution-hint">打开平台处理不会提交同意、拒绝或付款；刷新金额只读取已同步资料。</p>
  </section>
</template>
<script>
import { refundAmountConflict } from './refundAmountPresentation'
import { money } from './orderAmounts'
export default {
  name: 'RefundAmountResolution',
  props: { refund: { type: Object, required: true } },
  computed: {
    conflict() { return refundAmountConflict(this.refund) },
    fields() { const c = this.conflict || {}; return [{ label: '原子单商品实付', value: c.original }, { label: '此前已退', value: c.confirmed }, { label: '其他退款占用', value: c.occupied }, { label: '本次平台申请', value: c.requested }, { label: '本次可退上限', value: c.limit }, { label: '超出金额', value: c.excess }] }
  },
  methods: { money }
}
</script>
<style scoped>
.refund-amount-resolution{padding:16px;margin:16px 0;border:1px solid #efdab6;border-radius:6px;background:#fffdf8;color:#65533d;font-size:13px;line-height:1.8}.refund-amount-resolution dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:16px 0}.refund-amount-resolution dt{font-size:12px;color:#8b7964}.refund-amount-resolution dd{margin:0;font-size:18px;font-weight:500;font-variant-numeric:tabular-nums}.refund-amount-resolution ol{padding-left:20px}.resolution-actions{display:flex;gap:10px;flex-wrap:wrap}.resolution-actions .el-button{margin:0}.resolution-hint{font-size:12px;color:#8b7964;margin-bottom:0}@media(max-width:700px){.refund-amount-resolution dl{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
