<template>
  <div class="warehouse-status">
    <el-button type="text" class="state" @click="$emit('open')">{{ warehouseText(row) }}</el-button>
    <div v-if="row.pending_count" class="warning">回传已接收，实收待归属</div>
    <template v-if="log.id"><div><span :class="resultTone(log.result)">{{ actions[log.action] || log.action }}：{{ results[log.result] || log.result }}</span></div><div class="muted">{{ log.create_time }}</div><div v-if="log.error_info" class="danger">{{ log.error_info }}</div></template>
    <div v-else class="muted">尚无仓库交互记录</div>
    <el-button v-if="log.id" type="text" size="mini" @click="$emit('open')">查看交互记录</el-button>
  </div>
</template>
<script>
import { actions, results, resultTone } from '../warehouse/wmsLogLabels'
import { warehouseText } from './returnDocumentPresentation'
export default { props: { row: { type: Object, required: true } }, data: () => ({ actions, results }), computed: { log() { return this.row.latest_interaction || {} } }, methods: { warehouseText, resultTone } }
</script>
<style scoped>.state{white-space:normal;text-align:left;font-weight:600}.warehouse-status{font-size:12px;line-height:1.8;overflow-wrap:anywhere}.muted{color:#7a899d}.danger{color:#c74545}.warning{color:#b77d19}.success{color:#278558}</style>
