<template>
  <section class="order-amount-summary">
    <div v-for="item in items" :key="item[0]"><span>{{ item[0] }}</span><strong>{{ money(doc[item[1]]) }}</strong></div>
    <div class="amount-check"><el-tag size="small" :type="checkTone(doc.check_state)">{{ checkLabel(doc.check_state) }}</el-tag><span>订单实付含买家运费</span></div>
  </section>
</template>
<script>
import { documentOf, money, checkLabel, checkTone } from './orderAmounts'
export default { name: 'OrderAmountSummary', props: { order: { type: Object, required: true } }, computed: { doc() { return documentOf(this.order) } }, data: () => ({ items: [['商品销售总额', 'sales_amount'], ['优惠合计', 'discount_total'], ['买家运费', 'buyer_freight']] }), methods: { money, checkLabel, checkTone } }
</script>
<style scoped>
.order-amount-summary{display:flex;flex-wrap:wrap;gap:18px 32px;padding:14px 18px;background:#f5f8fc;border-radius:8px;margin-top:12px}.order-amount-summary>div{display:flex;flex-direction:column;gap:7px;font-size:12px;color:#68768b}.order-amount-summary strong{font-size:16px;color:#25344a;font-variant-numeric:tabular-nums}.amount-check{margin-left:auto;align-items:flex-end}@media(max-width:768px){.amount-check{margin-left:0;align-items:flex-start}}
</style>
