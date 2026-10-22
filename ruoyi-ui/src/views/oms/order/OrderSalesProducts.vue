<template>
  <section class="order-sales-products">
    <div class="sales-overview"><div><b>购买 {{ summary.quantity_text }}</b><span v-if="summary.components" class="muted">包含组件 {{ summary.components }} 件</span></div><div>商品实付 <strong>{{ money((order.document || {}).goods_payment) }}</strong></div></div>
    <el-table class="sales-products-table" :data="rows" row-key="key" :expand-row-keys="expandedKeys" @expand-change="onExpand">
      <el-table-column type="expand" width="45"><template slot-scope="s"><order-sale-amounts :value="s.row.money"/><order-sales-bundles :row="s.row" :order="order" @open-shipment="$emit('open-shipment', $event)"/></template></el-table-column>
      <el-table-column label="销售商品" min-width="220"><template slot-scope="s"><div class="sales-product-name"><b>{{ s.row.name }}</b><el-tag size="mini">{{ s.row.type_label }}</el-tag><el-tag v-if="s.row.has_replacement" size="mini" type="warning">含换品履约</el-tag></div><div class="muted">{{ s.row.sku }}<span v-if="s.row.spec"> · {{ s.row.spec }}</span></div><div v-if="s.row.kind === 'LUCKY_BAG' || s.row.kind === 'VIRTUAL_BUNDLE'" class="muted">实际组件 {{ s.row.physical_quantity }} 件，已出库 {{ s.row.shipped_physical_quantity }} 件</div></template></el-table-column>
      <el-table-column label="平台子订单号" min-width="180"><template slot-scope="s"><span class="oid">{{ s.row.oid }}</span><el-button v-clipboard:copy="s.row.oid" v-clipboard:success="copied" v-clipboard:error="copyFailed" type="text" icon="el-icon-document-copy" aria-label="复制平台子订单号"/></template></el-table-column>
      <el-table-column label="购买数量" width="100" align="right"><template slot-scope="s"><b>{{ s.row.quantity }} {{ s.row.unit }}</b></template></el-table-column>
      <el-table-column label="发货进度" min-width="140"><template slot-scope="s"><el-tag size="small" :type="s.row.progress_tone">{{ s.row.progress }}</el-tag><div class="progress-count">{{ ['LUCKY_BAG','VIRTUAL_BUNDLE'].includes(s.row.kind) ? '完整发出' : '已发' }} {{ s.row.shipped }} {{ s.row.unit }}<span v-if="s.row.pending"> · {{ s.row.canceled === null ? '未完整发出' : '待发' }} {{ s.row.pending }} {{ s.row.unit }}</span></div><div v-if="s.row.canceled" class="muted">已终止 {{ s.row.canceled }} {{ s.row.unit }}</div><div v-else-if="s.row.canceled === null" class="muted">部分组件已终止，展开查看</div></template></el-table-column>
      <el-table-column label="退货进度" min-width="170"><template slot-scope="s"><template v-if="s.row.returns.title"><span :class="{warning:s.row.returns.pending_review}">{{ s.row.returns.title }}</span><div v-for="text in s.row.returns.details" :key="text" class="muted">{{ text }}</div><div v-if="['LUCKY_BAG','VIRTUAL_BUNDLE'].includes(s.row.kind)" class="muted">按实际组件统计</div><div v-for="refund in s.row.returns.refunds" :key="refund.id"><router-link :to="{path:'/oms-order/businessRefunds',query:{id:refund.id}}" target="_blank" class="return-link">查看退款退回</router-link></div></template><span v-else class="muted">—</span></template></el-table-column>
      <el-table-column label="销售金额" width="115" align="right"><template slot-scope="s">{{ money(s.row.money.sales_amount) }}</template></el-table-column>
      <el-table-column label="优惠合计" width="105" align="right"><template slot-scope="s">{{ money(s.row.money.discount_total) }}</template></el-table-column>
      <el-table-column label="商品实付" width="115" align="right"><template slot-scope="s"><strong>{{ money(s.row.money.goods_payment) }}</strong></template></el-table-column>
    </el-table>
    <p class="muted sales-help">展开销售商品查看实际组成；仓库批次、库存来源和物流包裹见下方“仓库发货明细”。</p>
  </section>
</template>
<script>
import OrderSalesBundles from './OrderSalesBundles'
import OrderSaleAmounts from './OrderSaleAmounts'
import { money } from './orderAmounts'
import { salesRows, salesSummary } from './orderSalesPresentation'
export default {
  name: 'OrderSalesProducts', components: { OrderSalesBundles, OrderSaleAmounts }, props: { order: { type: Object, required: true } },
  data: () => ({ expanded: null }),
  computed: { rows() { return salesRows(this.order) }, summary() { return salesSummary(this.rows) }, expandedKeys() { return this.expanded === null ? this.rows.length === 1 && this.rows[0].kind === 'LUCKY_BAG' && this.rows[0].units.length <= 3 ? [this.rows[0].key] : [] : this.expanded } },
  watch: { 'order.id'() { this.expanded = null } },
  methods: { onExpand(row, expandedRows) { this.expanded = expandedRows.map(r => r.key) }, money, copied() { this.$message.success('平台子订单号已复制') }, copyFailed() { this.$message.error('复制失败，请选中单号手动复制') } }
}
</script>
<style scoped>
.sales-overview{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;background:#f5f8fc;border-radius:8px;padding:14px 18px;margin:12px 0 16px}.sales-overview .muted{margin-left:16px}.sales-overview strong{font-size:18px;margin-left:8px}.sales-product-name{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-bottom:6px}.muted{color:#7a8798;font-size:12px;line-height:1.7}.oid{font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.progress-count{margin-top:6px;font-size:12px}.sales-help{margin:10px 0}.return-link{font-size:12px;color:#1681e8;line-height:1.8}.warning{color:#a56b14}.order-sales-products ::v-deep .el-table__expanded-cell{padding:16px 22px;background:#fafcff}
</style>
