<template>
  <el-table :data="displayLines" row-key="id" size="small" :empty-text="gift ? '没有赠品明细' : '没有有效履约商品'">
    <el-table-column type="expand" width="45"><template slot-scope="s"><order-physical-sources :line="s.row" :order="order" @open-shipment="$emit('open-shipment', $event)"/></template></el-table-column>
    <el-table-column label="实际商品 / SKU" min-width="280"><template slot-scope="s"><order-line-identity :line="s.row" compact summary-only @open-shipment="$emit('open-shipment', $event)"/></template></el-table-column>
    <el-table-column v-if="!hidePlatformOid" label="平台子订单号" min-width="190"><template slot-scope="s"><span v-if="Number(s.row.gift_plan_id) > 0" class="muted">无平台子单</span><span v-else class="platform-oid">{{ s.row.platform_oid || '未提供' }}</span></template></el-table-column>
    <el-table-column :label="gift ? '原赠数量' : '有效履约数量'" width="105" align="right"><template slot-scope="s">{{ gift ? s.row.quantity : active(s.row) }} {{ unit(s.row) }}</template></el-table-column>
    <el-table-column label="已发货" width="100" align="right"><template slot-scope="s">{{ s.row.shipped_quantity || 0 }} {{ unit(s.row) }}</template></el-table-column>
    <el-table-column label="待发货" width="100" align="right"><template slot-scope="s">{{ voided ? 0 : Math.max(0, Number(s.row.quantity) - Number(s.row.cancelled_quantity || 0) - Number(s.row.shipped_quantity || 0)) }} {{ unit(s.row) }}</template></el-table-column>
    <el-table-column label="当前进度" min-width="180"><template slot-scope="s"><span v-if="voided">已作废</span><span v-else-if="Number(s.row.quantity) <= Number(s.row.cancelled_quantity || 0)">已终止</span><span v-else-if="Number(s.row.shipped_quantity) >= Number(s.row.quantity) - Number(s.row.cancelled_quantity || 0)">已发货</span><span v-else-if="Number(s.row.shipped_quantity) > 0">部分发货</span><span v-else>待发货</span><div v-if="s.row.cancelled_quantity" class="muted">已取消 {{ s.row.cancelled_quantity }} {{ unit(s.row) }}</div><div v-if="!voided && Number(s.row.pending_quantity) > 0" class="muted">待预占 {{ s.row.pending_quantity }} {{ unit(s.row) }}</div></template></el-table-column>
  </el-table>
</template>
<script>
import OrderLineIdentity from './OrderLineIdentity'
import OrderPhysicalSources from './OrderPhysicalSources'
import { salesOrigin, physicalUnit, activeQuantity } from './orderSalesPresentation'
export default { components: { OrderLineIdentity, OrderPhysicalSources }, props: { lines: { type: Array, default: () => [] }, bundles: { type: Array, default: () => [] }, order: { type: Object, default: () => ({}) }, gift: Boolean, voided: Boolean, hidePlatformOid: Boolean }, computed: { displayLines() { return this.lines.map(line => ({ ...line, sales_origin: salesOrigin(line, this.bundles) })) } }, methods: { unit: physicalUnit, active: activeQuantity } }
</script>
<style scoped>.muted{font-size:12px;color:#758397;line-height:1.7}.platform-oid{font-variant-numeric:tabular-nums;overflow-wrap:anywhere}</style>
