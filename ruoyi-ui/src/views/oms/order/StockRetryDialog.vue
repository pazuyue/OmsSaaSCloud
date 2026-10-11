<template>
  <el-dialog title="重新预占结果" :visible.sync="visible" width="min(1100px, 96vw)" append-to-body @closed="stop">
    <el-alert :title="polls >= 12 ? '仍在排队或处理中，可手动刷新或稍后查看业务订单。' : '已提交的订单由后台处理；最多自动刷新一分钟，提交成功不代表预占完成。'" type="info" :closable="false" />
    <el-table :data="rows" max-height="480"><el-table-column label="业务订单 / 平台单号" min-width="280"><template slot-scope="s">{{ s.row.order_sn || s.row.id }}<div class="muted">{{ s.row.platform_tid }}</div></template></el-table-column><el-table-column label="本次处理结果" min-width="160"><template slot-scope="s">{{ result(s.row) }}</template></el-table-column><el-table-column label="说明" min-width="250"><template slot-scope="s">{{ s.row.submitted ? (s.row.stock_message || s.row.message) : s.row.message }}</template></el-table-column></el-table>
    <span slot="footer"><el-button :loading="loading" @click="refresh">刷新结果</el-button><el-button @click="visible=false">关闭</el-button></span>
  </el-dialog>
</template>
<script>
import { reserveOrders, reserveResults } from '@/api/order'
export default {
  data() { return { visible: false, loading: false, submitting: false, rows: [], timer: null, polls: 0 } },
  beforeDestroy() { this.stop() },
  methods: {
    stop() { clearTimeout(this.timer) },
    result(row) { if (!row.submitted) return '已跳过'; if (['VOIDING', 'VOIDED'].includes(row.status)) return row.status === 'VOIDED' ? '订单已作废' : '作废处理中'; if (row.change_pending) return '已暂停，待订单更新'; if (row.stock_attempt_status === 'QUEUED') return '已提交，等待处理'; if (row.stock_attempt_status === 'PROCESSING') return '处理中'; if (row.stock_attempt_status === 'FAILED') return '预占处理失败'; return ({ RESERVED: '预占完成', PART_RESERVED: '部分商品已预占', WAIT_RESERVE: '仍待预占' })[row.inventory_status] || '等待处理' },
    async open(items) { if (this.submitting) return; if (!items.length || items.length > 100) return this.$message.warning('每次请选择1至100张订单'); this.submitting = true; this.stop(); this.polls = 0; try { this.rows = (await reserveOrders(items.map(r => r.id))).data; this.visible = true; this.$emit('changed'); await this.refresh() } finally { this.submitting = false } },
    async refresh() { this.stop(); const ids = this.rows.filter(r => r.submitted).map(r => r.id); if (!ids.length || this.loading) return; this.loading = true; try { const fresh = (await reserveResults(ids)).data; const byId = new Map(fresh.map(r => [String(r.id), r])); this.rows = this.rows.map(r => ({ ...r, ...(byId.get(String(r.id)) || {}) })); this.polls++; const running = this.rows.some(r => r.submitted && !r.change_pending && ['QUEUED', 'PROCESSING'].includes(r.stock_attempt_status)); if (running && this.visible && this.polls < 12) this.timer = setTimeout(() => this.refresh().catch(() => {}), 5000); if (!running) this.$emit('changed') } finally { this.loading = false } }
  }
}
</script>
<style scoped>.muted{color:#8490a2;font-size:13px}</style>
