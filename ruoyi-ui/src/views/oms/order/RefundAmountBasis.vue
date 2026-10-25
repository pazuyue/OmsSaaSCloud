<template>
  <section class="refund-amount-basis" aria-label="本次退款金额依据">
    <div class="basis-heading"><b>退款金额依据</b><span>同一平台子订单</span><el-tooltip :content="summary.context.note || '本次上限扣除其他退款；累计已退包含本单成功金额。'" placement="top"><i class="el-icon-info" tabindex="0" aria-label="退款金额统计口径" /></el-tooltip><el-button v-if="summary.hasRelated" type="text" size="mini" class="basis-records-link" @click="openRecords">查看关联退款</el-button></div>
    <div class="basis-values" :class="{ 'basis-composition': summary.completed }"><template v-for="(field,index) in summary.fields"><span v-if="summary.completed && index>0 && index<3" :key="field.key+'-operator'" class="basis-operator" aria-hidden="true">{{ index===1 ? '+' : '=' }}</span><div :key="field.key" class="basis-value" :class="'basis-'+field.key"><span>{{ field.label }}</span><el-button v-if="field.key==='other' && Number(field.value)>0" type="text" :aria-label="'查看其他单已退 '+money(field.value)" @click="openRecords"><strong>{{ money(field.value) }}</strong></el-button><strong v-else>{{ money(field.value) }}</strong></div></template></div>
    <p v-if="summary.completed && summary.occupied.length" class="basis-occupied">其他进行中退款占用 <b>{{ money(summary.context.other_occupied) }}</b></p>
    <p v-if="summary.context.issue" class="basis-warning"><i class="el-icon-warning-outline" /> {{ summary.context.issue }}</p>
    <refund-amount-records ref="records" :refund="refund" />
  </section>
</template>
<script>
import { refundAmountSummary } from './refundAmountPresentation'
import RefundAmountRecords from './RefundAmountRecords'
import { money } from './orderAmounts'
export default {
  components: { RefundAmountRecords },
  props: { refund: { type: Object, required: true } },
  computed: { summary() { return refundAmountSummary(this.refund) } },
  methods: { money, openRecords() { this.$refs.records.open() } }
}
</script>
<style scoped>
.refund-amount-basis{padding:14px;background:#f5f8fc;border:1px solid #e5ebf3;border-radius:6px;margin-top:12px}.basis-heading{display:flex;align-items:center;gap:9px;font-size:11px;color:#8290a3;flex-wrap:wrap}.basis-heading b{font-size:12px;font-weight:500;color:#4e6179}.basis-records-link{margin-left:auto;padding:0;font-size:12px}.basis-values{display:flex;gap:28px;margin-top:12px;flex-wrap:wrap;align-items:center}.basis-value{display:flex;flex-direction:column;gap:5px;font-size:12px;color:#718198;min-width:100px}.basis-values strong{font-size:17px;color:#344b66;font-weight:500;white-space:nowrap;font-variant-numeric:tabular-nums}.basis-value .el-button{padding:0;align-self:flex-start}.basis-value .el-button strong{color:#1677d2;border-bottom:1px dashed #a5c9ef}.basis-operator{font-size:20px;color:#95a4b7;padding-top:18px}.basis-composition .basis-total strong{font-weight:600}.basis-composition .basis-remaining{margin-left:auto;padding-left:28px;border-left:1px solid #dfe7f1}.basis-warning,.basis-occupied{font-size:12px;line-height:1.7;margin:10px 0 0}.basis-warning{color:#a16c20}.basis-occupied{color:#718198}.basis-occupied b{font-weight:500;color:#425974;margin-left:8px}@media(max-width:760px){.basis-values{gap:16px}.basis-composition .basis-remaining{margin-left:0;padding-left:0;border:0}.basis-operator{display:none}.basis-value{flex:1;min-width:125px}}
</style>
