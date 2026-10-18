<template>
  <div class="order-identity">
    <div v-if="order.business_type === 'EXCHANGE'" class="identity-line"><el-tag size="mini" type="info">换货换出</el-tag><router-link v-if="order.exchange_id" :to="{ path: '/oms-order/exchanges', query: { exchangeId: order.exchange_id } }">查看换货处理</router-link></div>
    <div v-if="order.order_sn" class="identity-line">
      <span class="identity-label">OMS 单号</span>
      <el-button v-if="link" type="text" class="identity-number primary" @click="$emit('open')">{{ order.order_sn }}</el-button>
      <strong v-else class="identity-number">{{ order.order_sn }}</strong>
      <el-button v-clipboard:copy="order.order_sn" v-clipboard:success="copied" v-clipboard:error="copyFailed" type="text" icon="el-icon-document-copy" title="复制 OMS 单号" aria-label="复制 OMS 单号" />
    </div>
    <div class="identity-line">
      <span class="identity-label">平台单号</span>
      <el-button v-if="link && !order.order_sn && order.platform_tid" type="text" class="identity-number primary" @click="$emit('open')">{{ order.platform_tid }}</el-button>
      <span v-else class="identity-number">{{ order.platform_tid || '—' }}</span>
      <el-button v-if="order.platform_tid" v-clipboard:copy="order.platform_tid" v-clipboard:success="copied" v-clipboard:error="copyFailed" type="text" icon="el-icon-document-copy" title="复制平台单号" aria-label="复制平台单号" />
    </div>
  </div>
</template>

<script>
export default {
  name: 'OrderIdentity',
  props: {
    order: { type: Object, required: true },
    link: { type: Boolean, default: false }
  },
  methods: {
    copied() { this.$message.success('单号已复制') },
    copyFailed() { this.$message.error('复制失败，请选中单号手动复制') }
  }
}
</script>

<style scoped>
.identity-line { display: flex; align-items: baseline; gap: 8px; line-height: 1.6; }
.identity-line + .identity-line { margin-top: 6px; }
.identity-label { flex: 0 0 62px; color: #68778b; font-size: 12px; }
.identity-number { min-width: 0; font-size: 13px; word-break: break-all; white-space: normal; text-align: left; font-variant-numeric: tabular-nums; }
.primary { font-weight: 600; }
.identity-line .el-button { padding: 3px 0; margin: 0; }
</style>
