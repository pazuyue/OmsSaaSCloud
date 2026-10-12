<template>
  <div class="order-shipments">
    <el-descriptions border :column="2" class="summary">
      <el-descriptions-item label="已分仓数量">{{ order.status === 'VOIDED' ? 0 : order.allocation_quantity || 0 }} 件</el-descriptions-item>
      <el-descriptions-item label="已发货数量">{{ order.shipped_quantity || 0 }} 件</el-descriptions-item>
      <el-descriptions-item label="待分仓数量">{{ order.pending_allocation_quantity || 0 }} 件</el-descriptions-item>
      <el-descriptions-item label="关联发货单">{{ order.shipment_count || 0 }} 张</el-descriptions-item>
      <el-descriptions-item label="待匹配快递">{{ waitingCarriers.length }} 张</el-descriptions-item>
      <el-descriptions-item label="已确认快递">{{ confirmedCarriers }} 张</el-descriptions-item><el-descriptions-item label="待下发发货单">{{ shipments.filter(s => s.status === 'WAIT_DISPATCH').length }} 张</el-descriptions-item><el-descriptions-item label="待虚拟出库">{{ virtualOutboundable.length }} 张</el-descriptions-item><el-descriptions-item label="仓库已接收">{{ order.warehouse_accepted_quantity || 0 }} 件</el-descriptions-item>
    </el-descriptions>
    <div class="toolbar"><p class="hint">各发货单按实体仓独立匹配快递；补仓生成新单，不改变已有单据。已分仓不代表已发货。</p><div class="actions"><el-button v-if="shipments.length" v-hasPermi="['order:shipment:carrier']" size="small" type="primary" :disabled="!matchableCarriers.length" @click="$refs.carrier.open(matchableCarriers)">匹配全部待匹配发货单（{{ matchableCarriers.length }}）</el-button><el-button v-if="shipments.length" v-hasPermi="['order:shipment:dispatch']" size="small" :disabled="!dispatchable.length" @click="$refs.dispatch.open(dispatchable)">下发全部待下发发货单（{{ dispatchable.length }}）</el-button><el-button v-if="shipments.some(s => Number(s.outbound_mode) === 2)" v-hasPermi="['order:shipment:virtualOutbound']" size="small" type="primary" :disabled="!virtualOutboundable.length" @click="$refs.virtualOutbound.open(virtualOutboundable)">虚拟出库全部待出库发货单（{{ virtualOutboundable.length }}）</el-button><el-button v-if="order.can_generate_shipments" v-hasPermi="['order:order:allocate']" size="small" :loading="generating" @click="generate">补建发货单</el-button></div></div>
    <el-table :data="order.shipments || []" empty-text="尚无发货单，确认分仓后自动生成">
      <el-table-column label="发货单号 / 生成时间" min-width="280"><template slot-scope="s"><el-button v-hasPermi="['order:order:query']" type="text" @click="$refs.detail.open(s.row.id)">{{ s.row.shipment_sn }}</el-button><div class="hint">{{ s.row.create_time }}</div></template></el-table-column>
      <el-table-column label="实体仓" min-width="160"><template slot-scope="s">{{ s.row.real_store_name || s.row.real_store_code }}<div class="hint">{{ s.row.real_store_code }} · 第 {{ s.row.generation_no }} 批</div></template></el-table-column>
      <el-table-column label="状态" width="130"><template slot-scope="s"><el-tag size="small" :type="tone(s.row.status)">{{ label(s.row.status, s.row.outbound_mode) }}</el-tag></template></el-table-column>
      <el-table-column label="快递 / 匹配结果" min-width="230"><template slot-scope="s">{{ s.row.carrier_name || (s.row.carrier_match_status === 'FAILED' ? '匹配失败' : '尚未匹配') }}<div v-if="s.row.carrier_code" class="hint">{{ s.row.carrier_code }} · {{ s.row.carrier_match_mode === 'MANUAL' ? '手工确认' : '自动匹配' }}</div><div v-if="s.row.carrier_match_status === 'FAILED'" class="failure">{{ s.row.carrier_match_reason }}</div><div v-if="s.row.fulfillment_block" class="failure">{{ s.row.fulfillment_block }}</div></template></el-table-column>
      <el-table-column label="出库处理结果" min-width="210"><template slot-scope="s"><shipment-dispatch-status :row="s.row" /></template></el-table-column><el-table-column label="数量" width="110"><template slot-scope="s">单据 {{ s.row.quantity }} 件<div class="hint">已发货 {{ s.row.shipped_quantity || 0 }} 件</div></template></el-table-column>
      <el-table-column label="操作" width="210" fixed="right"><template slot-scope="s"><template v-if="canMatch(s.row)"><el-button v-if="s.row.status === 'WAIT_CARRIER'" v-hasPermi="['order:shipment:carrier']" type="text" @click="$refs.carrier.open([s.row])">匹配快递</el-button><el-button v-hasPermi="['order:shipment:carrier']" type="text" @click="$refs.carrier.open([s.row], 'MANUAL')">{{ s.row.carrier_code ? '调整快递' : '手工选择' }}</el-button></template><el-button v-if="s.row.can_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" type="text" @click="$refs.virtualOutbound.open([s.row])">虚拟出库</el-button><el-button v-if="s.row.can_retry_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" type="text" @click="$refs.virtualOutbound.open([s.row], 'RETRY')">重试虚拟出库</el-button><el-button v-if="s.row.can_query_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" type="text" @click="$refs.virtualOutbound.open([s.row], 'QUERY')">核对出库</el-button><el-button v-if="s.row.can_dispatch" v-hasPermi="['order:shipment:dispatch']" type="text" @click="$refs.dispatch.open([s.row])">{{ s.row.wms_dispatch_state === 'FAILED' ? '重试下发' : '下发仓库' }}</el-button><el-button v-if="s.row.can_query_warehouse" v-hasPermi="['order:shipment:queryWarehouse']" type="text" @click="$refs.dispatch.open([s.row], 'QUERY')">查询仓库</el-button></template></el-table-column>
    </el-table>
    <shipment-detail ref="detail" @open-order="$emit('open-order', $event)" @changed="$emit('changed')" />
    <shipment-carrier-dialog ref="carrier" @changed="$emit('changed')" /><shipment-dispatch-dialog ref="dispatch" @changed="$emit('changed')" /><shipment-virtual-outbound-dialog ref="virtualOutbound" @changed="$emit('changed')" />
  </div>
</template>
<script>
import { generateShipments } from '@/api/order'
import { shipmentLabel, shipmentTone } from './shipmentLabels'
import ShipmentDetail from './ShipmentDetail'
import ShipmentCarrierDialog from './ShipmentCarrierDialog'
import ShipmentDispatchDialog from './ShipmentDispatchDialog'
import ShipmentDispatchStatus from './ShipmentDispatchStatus'
import ShipmentVirtualOutboundDialog from './ShipmentVirtualOutboundDialog'
export default {
  props: { order: { type: Object, required: true } }, components: { ShipmentDetail, ShipmentCarrierDialog, ShipmentDispatchDialog, ShipmentDispatchStatus, ShipmentVirtualOutboundDialog },
  data() { return { generating: false } },
  computed: {
    shipments() { return this.order.shipments || [] },
    waitingCarriers() { return this.shipments.filter(s => s.status === 'WAIT_CARRIER') },
    matchableCarriers() { return this.waitingCarriers.filter(this.canMatch) },
    virtualOutboundable() { return this.shipments.filter(s => s.can_virtual_outbound) },
    dispatchable() { return this.shipments.filter(s => s.can_dispatch) },
    confirmedCarriers() { return this.shipments.filter(s => s.carrier_match_status === 'MATCHED' && !['VOIDING', 'VOIDED'].includes(s.status)).length }
  },
  methods: {
    label: shipmentLabel, tone: shipmentTone,
    canMatch(row) { return !!row.can_match_carrier && ['WAIT_CARRIER', 'WAIT_DISPATCH'].includes(row.status) && !Number(row.shipped_quantity || 0) },
    async generate() { if (this.generating) return; this.generating = true; try { const r = await generateShipments(this.order.id, { control_version: this.order.control_version }); this.$message.success(r.data.length ? `已生成 ${r.data.length} 张发货单` : '发货单已完整，无需重复生成'); this.$emit('changed') } finally { this.generating = false } }
  }
}
</script>
<style scoped>.summary{margin:16px 0}.hint{font-size:13px;color:#8490a2;margin:6px 0}.failure{font-size:13px;color:#c27b12;line-height:1.5;margin:6px 0;overflow-wrap:anywhere}.toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;margin:16px 0;flex-wrap:wrap}.actions{display:flex;gap:8px;flex-wrap:wrap}.actions .el-button+.el-button{margin-left:0}</style>
