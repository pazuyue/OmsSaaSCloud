<template>
 <section class="platform-status" :data-status="progress.code">
  <div class="heading"><strong>平台处理</strong><el-tag size="small" :type="progress.tone">{{ progress.title }}</el-tag><el-tag v-if="exchange.capability && exchange.capability.simulation" size="mini" type="warning">模拟平台</el-tag></div>
  <p>{{ progress.description }}</p>
  <div v-if="exchange.source_kind === 'PLATFORM'" class="metrics"><span>平台换货单号：{{ exchange.dispute_id || '未返回' }}</span><span>换出已出库 {{ exchange.platform_shipped_quantity || 0 }} / {{ exchange.requested_quantity }} 件</span><span>已关联包裹 {{ exchange.platform_packaged_quantity || 0 }} 件</span><span>运单 {{ exchange.platform_tracking_count || 0 }} 组</span></div>
  <p v-if="exchange.source_kind === 'PLATFORM' && !exchange.capability" class="environment-note">以上为该业务单最新同步状态；真实平台或模拟环境请以调用记录中的环境标识为准。</p>
  <div class="actions"><slot /><platform-log-link v-if="showLogs" :number="exchange.exchange_sn" number-type="EXCHANGE" category="EXCHANGE" /></div>
 </section>
</template>
<script>
import { platformProgress } from './exchangePlatformProgress'
import PlatformLogLink from '@/views/oms/channel/PlatformLogLink'
export default { name: 'ExchangePlatformStatus', components: { PlatformLogLink }, props: { exchange: { type: Object, required: true }, showLogs: { type: Boolean, default: true } }, computed: { progress() { return platformProgress(this.exchange) } } }
</script>
<style scoped>
.platform-status{border:1px solid #dce6f2;border-radius:9px;padding:16px 20px;background:#f8faff;margin:16px 0}.heading{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.heading strong{font-size:15px;color:#354258}.platform-status p{font-size:13px;color:#607088;line-height:1.8;margin:10px 0}.platform-status .environment-note{font-size:12px;color:#7a899d}.metrics{display:flex;gap:10px 24px;flex-wrap:wrap;color:#7a899d;font-size:12px;line-height:1.8}.actions{display:flex;align-items:center;gap:16px;flex-wrap:wrap}.actions:empty{display:none}.heading .el-tag{white-space:normal;height:auto;line-height:1.7}
</style>
