<template>
  <div v-loading="loading" class="wms-inbound-panel">
    <el-alert v-if="error" title="仓库执行信息加载失败" type="error" :closable="false"><el-button type="text" @click="load">重试</el-button></el-alert>
    <template v-if="task">
      <div class="progress"><strong>{{ task.provider === 'QIMEN' ? '奇门' : '京东虎符' }} · {{ task.connection_name }}</strong><el-tag>{{ dispatch[task.dispatch_state] }}</el-tag><el-tag type="info">{{ receipt[task.receipt_state] }}</el-tag></div>
      <p>仓库单号：{{ task.external_order || '尚未返回' }}　外部仓库：{{ task.external_warehouse }}　WMS 货主：{{ task.external_owner }}</p>
      <p v-if="task.last_error" class="error">{{ task.last_error }}</p>
      <div class="actions"><el-button size="small" :disabled="acting" @click="load">刷新进度</el-button><el-button v-if="task.dispatch_state === 'FAILED'" v-hasPermi="['warehouse:tickets:retry']" size="small" type="primary" :loading="acting" @click="perform('retry')">重试下发</el-button><el-button v-if="!['PENDING','SENDING','CANCELED'].includes(task.dispatch_state)" v-hasPermi="['warehouse:tickets:retry']" size="small" :loading="acting" @click="perform('query')">查询仓库结果</el-button><el-button v-if="task.receipt_state === 'WAITING' && ['PENDING','ACCEPTED'].includes(task.dispatch_state)" v-hasPermi="['warehouse:tickets:remove']" size="small" :disabled="acting" @click="perform('cancel')">申请取消入库</el-button><el-button v-hasPermi="['warehouse:wms:log']" size="small" @click="$refs.logs.open(task.ticket_sn)">交互日志</el-button></div>
    </template>
    <wms-logs ref="logs" />
  </div>
</template>
<script>
import { inboundTask, inboundAction } from '@/api/warehouse/integration'
import WmsLogs from './WmsLogs'
export default {
  components: { WmsLogs }, props: { ticketId: { type: Number, required: true } },
  data() { return { task: null, loading: false, acting: false, error: false, seq: 0, dispatch: { PENDING: '待下发', SENDING: '下发中', ACCEPTED: '仓库已受理', FAILED: '下发失败', UNKNOWN: '结果待确认', CANCEL_PENDING: '取消待确认', CANCELED: '已取消' }, receipt: { WAITING: '待收货', PARTIAL: '部分收货', COMPLETE: '收货完成' } } },
  watch: { ticketId: { immediate: true, handler() { this.load() } } },
  methods: { async load() { const seq = ++this.seq; this.loading = true; this.error = false; try { const r = await inboundTask(this.ticketId); if (seq === this.seq) this.task = r.data } catch (_) { if (seq === this.seq) this.error = true } finally { if (seq === this.seq) this.loading = false } }, async perform(action) { if (this.acting) return; if (action === 'cancel') { try { await this.$modal.confirm('申请取消本批入库？已下发的单据须等待仓库确认取消。') } catch (_) { return } } this.acting = true; try { await inboundAction(this.ticketId, action); this.$modal.msgSuccess('操作已处理，请查看最新执行进度'); await this.load(); this.$emit('changed') } finally { this.acting = false } } }
}
</script>
<style scoped>.wms-inbound-panel{margin:18px 0;padding:16px;border:1px solid #dce6f2;border-radius:8px;background:#f6f9fd}.progress,.actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.actions .el-button{margin:0}.error{color:#d84b4b}.wms-inbound-panel p{font-size:13px;line-height:1.7}</style>
