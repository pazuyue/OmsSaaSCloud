<template>
  <div v-if="row.business_type === 'EXCHANGE'" class="platform-shipping-status"><router-link :to="{ path: '/oms-order/exchanges', query: { exchangeId: row.exchange_id } }">由换货单回传物流</router-link><div class="caption">{{ row.business_owner_sn }}</div></div>
  <div v-else class="platform-shipping-status"><el-button :class="'state-' + row.platform_shipping_state" type="text" @click="$emit('open', row.id)">{{ labels[row.platform_shipping_state] || '未回传' }}</el-button><el-tag v-if="row.platform_shipping_mode === 'SIMULATION'" size="mini" type="info" effect="plain">模拟</el-tag><div class="caption">已模拟回传 {{ row.platform_shipping_success_quantity || 0 }} / 已关联 {{ row.packaged_quantity || 0 }} 件</div><div v-if="row.platform_shipping_wait_config" class="caption">{{row.platform_shipping_wait_config}} 项映射或物流差异待处理</div><div v-if="row.unpacked_quantity" class="caption">另有 {{ row.unpacked_quantity }} 件待关联运单</div></div>
</template>
<script>
export default { props: { row: { type: Object, required: true } }, data() { return { labels: { NONE: '未回传', WAIT_CONFIG: '物流对照待核对', PENDING: '待回传', PROCESSING: '回传中', SUCCESS: '模拟回传成功', PART_SUCCESS: '部分包裹模拟成功', FAILED: '回传失败', UNKNOWN: '结果待确认' } } } }
</script>
<style scoped>.platform-shipping-status{line-height:1.6}.platform-shipping-status .el-button{padding:2px 0;margin-right:6px}.state-SUCCESS,.state-PART_SUCCESS{color:#278653}.state-FAILED{color:#c04b40}.state-UNKNOWN{color:#b87a1b}.caption{font-size:12px;color:#8490a2}</style>
