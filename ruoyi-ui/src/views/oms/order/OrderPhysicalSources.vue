<template>
  <div class="physical-sources">
    <order-line-identity :line="line" compact @open-shipment="$emit('open-shipment', $event)"/>
    <h4>仓库批次与库存来源</h4>
    <el-table v-if="allocations.length" :data="allocations" size="small"><el-table-column label="发货仓库" min-width="170"><template slot-scope="s">{{ warehouse(s.row.store_code) }}<div class="muted">{{ s.row.store_code }}</div></template></el-table-column><el-table-column label="库存来源" min-width="140"><template slot-scope="s">{{ source(s.row.source_type) }}</template></el-table-column><el-table-column label="批次" prop="batch_code" min-width="180"/><el-table-column label="分仓数量" prop="quantity" width="95"/><el-table-column label="已出库" prop="outbound_quantity" width="95"/></el-table>
    <el-table v-else :data="line.stock_sources || []" size="small" empty-text="尚未确定库存来源"><el-table-column label="来源仓库" prop="store_code" min-width="170"/><el-table-column label="库存来源" min-width="140"><template slot-scope="s">{{ source(s.row.source_type) }}</template></el-table-column><el-table-column label="累计预占" prop="quantity" width="110"/><el-table-column label="已出库" prop="outbound_quantity" width="110"/></el-table>
    <h4>关联发货单与物流包裹</h4>
    <p v-if="!(line.shipments || []).length" class="muted">尚未生成发货单。</p>
    <div v-for="ship in line.shipments || []" :key="ship.shipment_line_id" class="source-shipment"><el-button type="text" @click="$emit('open-shipment', ship.shipment_id)">{{ ship.shipment_sn }}</el-button><span>已出库 {{ ship.shipped_quantity }} / {{ ship.quantity }} {{ unit }}</span><el-button size="mini" @click="$emit('open-shipment', ship.shipment_id)">查看发货与包裹</el-button></div>
  </div>
</template>
<script>
import OrderLineIdentity from './OrderLineIdentity'
import { physicalSources, warehouseName, physicalUnit } from './orderSalesPresentation'
export default { name: 'OrderPhysicalSources', components: { OrderLineIdentity }, props: { line: { type: Object, required: true }, order: { type: Object, default: () => ({}) } }, computed: { allocations() { return physicalSources(this.order, this.line) }, unit() { return physicalUnit(this.line) } }, methods: { warehouse(code) { return warehouseName(this.order, code) }, source(type) { return { LUCKY: '福袋组件保障', LOCK: '渠道锁库', PUBLIC_EXTRA: '公共库存补占' }[type] || type } } }
</script>
<style scoped>.physical-sources{padding:6px 12px;overflow-wrap:anywhere}.physical-sources h4{margin:18px 0 10px;font-size:13px}.muted{font-size:12px;color:#7a8798;line-height:1.7}.source-shipment{display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:13px;margin:6px 0}</style>
