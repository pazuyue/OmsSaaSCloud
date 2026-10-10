<template>
  <div v-loading="loading" class="wms-inbound-panel">
    <el-alert v-if="error" title="仓库执行信息加载失败" type="error" :closable="false"><el-button type="text" @click="load">重试</el-button></el-alert>
    <template v-if="task">
      <div class="progress"><strong>{{ task.provider === 'QIMEN' ? '奇门' : '京东虎符' }} · {{ task.connection_name }}</strong><el-tag>{{ dispatch[task.dispatch_state] }}</el-tag><el-tag type="info">{{ receipt[task.receipt_state] }}</el-tag></div>
      <p>仓库单号：{{ task.external_order || '尚未返回' }}　外部仓库：{{ task.external_warehouse }}　WMS 货主：{{ task.external_owner }}</p>
      <p v-if="task.last_error" class="error">{{ task.last_error }}</p>
      <p v-if="task.last_query_time" :class="task.last_query_result === 'SUCCESS' ? '' : 'error'">最近查询 {{ parseTime(task.last_query_time) }} · {{ task.query_message }}</p>
      <div class="actions"><el-button size="small" :disabled="acting" @click="refresh">刷新进度</el-button><el-button v-if="task.dispatch_state === 'FAILED'" v-hasPermi="['warehouse:tickets:retry']" size="small" type="primary" :loading="acting" @click="perform('retry')">重试下发</el-button><el-button v-if="!['PENDING','SENDING','CANCELED'].includes(task.dispatch_state)" v-hasPermi="['warehouse:tickets:queryWarehouse']" size="small" :loading="acting" @click="perform('query')">查询仓库状态</el-button><el-button v-if="task.receipt_state === 'WAITING' && ['PENDING','ACCEPTED'].includes(task.dispatch_state)" v-hasPermi="['warehouse:tickets:remove']" size="small" :disabled="acting" @click="perform('cancel')">{{ task.dispatch_state === 'PENDING' ? '取消入库单' : '向仓库申请取消' }}</el-button><el-button v-hasPermi="['warehouse:wms:log']" size="small" @click="$refs.logs.open(task.ticket_sn)">交互日志</el-button></div>
      <p class="muted">刷新进度读取本地记录；查询仓库状态会请求对接仓库，实收数量及库存仍以收货回传为准。</p>
      <p v-if="task.receipt_state !== 'WAITING'" class="muted">已收货或收货已结束，不能取消入库单。</p>
    </template>
    <wms-logs ref="logs" />
  </div>
</template>
<script>
import { inboundTask, inboundAction } from '@/api/warehouse/integration'
import WmsLogs from './WmsLogs'
export default {
  components: { WmsLogs }, props: { ticketId: { type: Number, required: true } },
  data() { return { task: null, loading: false, acting: false, error: false, seq: 0, dispatch: { PENDING: '待下发', SENDING: '下发中', ACCEPTED: '仓库已受理', FAILED: '下发失败', UNKNOWN: '结果待确认', CANCEL_PENDING: '取消待确认', CANCELED: '已取消' }, receipt: { WAITING: '待收货', PARTIAL: '部分收货', COMPLETE: '收货结束' } } },
  watch: { ticketId: { immediate: true, handler() { this.load() } } },
  methods: { async load() { const seq = ++this.seq; this.loading = true; this.error = false; try { const r = await inboundTask(this.ticketId); if (seq === this.seq) this.task = r.data } catch (_) { if (seq === this.seq) this.error = true } finally { if (seq === this.seq) this.loading = false } }, async refresh() { await this.load(); this.$emit('changed') },
    async perform(action) {
      if (this.acting) return
      let data = {}
      if (action === 'cancel') {
        try { const r = await this.$prompt(this.task.dispatch_state === 'PENDING' ? '填写取消原因，尚未下发的入库单将直接取消。' : '填写取消原因。申请后须等待仓库确认，期间仍可能收到收货回传。', '取消入库', { inputType: 'textarea', inputValidator: v => !!v && !!v.trim() && v.length <= 500 || '请填写 1 至 500 字原因' }); data = { reason: r.value } } catch (_) { return }
      }
      this.acting = true
      try {
        const r = await inboundAction(this.ticketId, action, data)
        const result = r.data
        if (result.success && !result.needsAttention) this.$modal.msgSuccess(result.message)
        else this.$modal.msgWarning(result.message + '；' + (result.nextAction || '请查看交互日志'))
        await this.load(); this.$emit('changed')
      } catch (_) { /* HTTP errors are shown by the shared request handler. */ } finally { this.acting = false }
    }
  }
}
</script>
<style scoped>.wms-inbound-panel{margin:18px 0;padding:16px;border:1px solid #dce6f2;border-radius:8px;background:#f6f9fd}.progress,.actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.actions .el-button{margin:0}.error{color:#d84b4b}.wms-inbound-panel p{font-size:13px;line-height:1.7}</style>
