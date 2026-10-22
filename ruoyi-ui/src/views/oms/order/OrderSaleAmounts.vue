<template>
  <section class="sale-amount-details"><h4>销售金额明细</h4>
    <el-descriptions border :column="3" size="small"><el-descriptions-item v-for="item in fields" :key="item[0]" :label="item[0]">{{ money(value[item[1]]) }}</el-descriptions-item></el-descriptions>
    <p class="muted">商品实付来源：{{ value.payment_source || '未提供' }}。以上金额对应整行购买数量；优惠分摊不再次计入订单优惠合计。</p>
  </section>
</template>
<script>
import { money } from './orderAmounts'
export default { name: 'OrderSaleAmounts', props: { value: { type: Object, default: () => ({}) } }, data: () => ({ fields: [['优惠前销售单价', 'sales_price'], ['优惠前销售金额', 'sales_amount'], ['商品实付（不含运费）', 'goods_payment'], ['商品自身优惠', 'item_discount'], ['整单优惠分摊', 'order_discount_share'], ['优惠合计', 'discount_total'], ['按销售及优惠计算商品实付', 'calculated_goods_payment'], ['商品实付核对差额', 'payment_difference'], ['子单改价', 'adjustment'], ['平台原始子单实付', 'platform_payment'], ['平台分摊实付', 'divided_payment']] }), methods: { money } }
</script>
<style scoped>.sale-amount-details{margin:0 0 20px}.sale-amount-details h4{margin:0 0 12px;color:#34445a}.muted{color:#7a8798;font-size:12px;line-height:1.7}</style>
