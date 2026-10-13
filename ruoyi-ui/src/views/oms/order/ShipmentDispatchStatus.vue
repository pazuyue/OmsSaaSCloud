<template>
  <div :class="{ compact }">
    <template v-if="virtual"><div class="hint">虚拟出库</div><el-tag v-if="row.virtual_outbound_state && row.virtual_outbound_state !== 'NONE'" size="small" :type="row.virtual_outbound_state === 'COMPLETED' ? 'success' : row.virtual_outbound_state === 'FAILED' ? 'danger' : 'warning'">{{ virtualLabel(row.virtual_outbound_state) }}</el-tag><span v-else class="hint">未提交出库</span><div v-if="row.virtual_outbound_quantity" class="hint">最近任务 {{ row.virtual_outbound_quantity }} 件</div><div v-if="row.virtual_outbound_error" class="message">{{ row.virtual_outbound_error }}</div></template>
    <template v-else><div v-if="Number(row.outbound_mode) === 1" class="hint">仓库下发</div><el-tag v-if="row.wms_dispatch_state && row.wms_dispatch_state !== 'NONE'" size="small" :type="row.wms_dispatch_state === 'ACCEPTED' ? 'success' : row.wms_dispatch_state === 'FAILED' ? 'danger' : 'warning'">{{ label(row.wms_dispatch_state) }}</el-tag><span v-else class="hint">未提交下发</span><div v-if="row.wms_external_order" class="hint warehouse-order" :title="row.wms_external_order">仓库单号：{{ row.wms_external_order }}</div><div v-if="row.wms_error" class="message">{{ row.wms_error }}</div><div v-if="!compact && (row.wms_progress_message || row.wms_query_message)" :class="Number(row.shipped_quantity) > 0 ? 'hint' : 'message'">{{ row.wms_progress_message || row.wms_query_message }}</div></template>
    <div v-if="row.outbound_mode_error" class="message">{{ row.outbound_mode_error }}</div>
  </div>
</template>
<script>
import { dispatchLabel, virtualOutboundLabel } from './shipmentLabels'
export default { props: { row: { type: Object, required: true }, compact: Boolean }, computed: { virtual() { return (this.row.virtual_outbound_state && this.row.virtual_outbound_state !== 'NONE') || (Number(this.row.outbound_mode) === 2 && (!this.row.wms_dispatch_state || this.row.wms_dispatch_state === 'NONE')) } }, methods: { label: dispatchLabel, virtualLabel: virtualOutboundLabel } }
</script>
<style scoped>.hint{font-size:13px;color:#8490a2;margin:6px 0}.message{font-size:13px;color:#c27b12;line-height:1.5;margin:6px 0;overflow-wrap:anywhere}.compact .warehouse-order{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}</style>
