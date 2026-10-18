<template>
  <div v-if="embedded" class="log-center-link"><el-alert title="仓库交互日志已统一到日志中心" description="查看本单全部下发、查询、取消及回传记录，并核对当前处理进度。" type="info" :closable="false" show-icon /><el-button v-hasPermi="['warehouse:wms:log']" type="primary" plain size="small" @click="open(ticketSn)">查看本单仓库交互日志</el-button></div>
  <span v-else />
</template>
<script>
export default {
  props: { embedded: Boolean, ticketSn: { type: String, default: '' } },
  methods: { open(sn = '') { this.$router.push({ path: '/oms-supplychain/wmsLogs', query: sn ? { numberType: 'DOCUMENT', number: sn, allTime: 'true' } : {} }) } }
}
</script>
<style scoped>.log-center-link .el-button{margin-top:16px}</style>
