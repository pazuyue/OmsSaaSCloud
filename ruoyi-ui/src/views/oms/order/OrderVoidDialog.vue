<template>
  <el-dialog title="作废业务订单" :visible.sync="visible" width="min(920px, 94vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @closed="stop">
    <template v-if="!submitted">
      <el-alert title="仅终止 OMS 履约，不关闭平台订单、不退款。平台仍可能存在发货义务。" type="warning" :closable="false" show-icon />
      <p>本次选择 {{ orders.length }} 张订单。作废将取消销售需求，释放逻辑预占和已有仓库占用，不支持反作废；临时暂停请使用挂起。</p>
      <el-input v-model.trim="reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请填写作废原因（必填）；重试时保留原作废原因" />
      <el-checkbox v-model="confirmed" style="margin-top:18px;white-space:normal">我已确认平台订单的后续处理，知悉仅作废 OMS 订单且无法恢复</el-checkbox>
    </template>
    <template v-else>
      <el-alert title="已提交不代表作废完成。库存释放确认后才显示已作废；失败订单可在列表或详情重试作废。" type="info" :closable="false" />
      <el-table :data="results" max-height="440"><el-table-column label="业务订单" min-width="250"><template slot-scope="s">{{ s.row.order_sn || s.row.id }}</template></el-table-column><el-table-column label="结果" width="140"><template slot-scope="s"><el-tag :type="s.row.status === 'VOIDED' ? 'success' : 'warning'">{{ resultLabel(s.row) }}</el-tag></template></el-table-column><el-table-column label="说明" min-width="300"><template slot-scope="s">{{ s.row.submitted ? (s.row.void_error || s.row.stock_message || s.row.message) : s.row.message }}</template></el-table-column></el-table>
      <p v-if="polls >= 12" class="muted">自动刷新已结束，可手动刷新，或稍后在业务订单查看处理结果。</p>
    </template>
    <span slot="footer"><el-button v-if="submitted" :loading="loading" @click="refresh">刷新结果</el-button><el-button :disabled="saving" @click="visible=false">{{ submitted ? '关闭' : '取消' }}</el-button><el-button v-if="!submitted" type="danger" :disabled="!confirmed || !reason" :loading="saving" @click="submit">确认作废</el-button></span>
  </el-dialog>
</template>
<script>
import { voidOrders, voidResults } from '@/api/order'
export default {
  data() { return { visible: false, saving: false, submitted: false, confirmed: false, loading: false, orders: [], reason: '', results: [], polls: 0, timer: null } },
  beforeDestroy() { this.stop() },
  methods: {
    stop() { clearTimeout(this.timer) },
    open(rows) { if (!rows.length || rows.length > 100) return this.$message.warning('请选择1至100张订单'); this.stop(); this.orders = rows.map(r => ({ id: r.id, control_version: r.control_version })); this.reason = rows.length === 1 ? (rows[0].void_reason || '') : ''; this.results = []; this.submitted = false; this.confirmed = false; this.polls = 0; this.visible = true },
    resultLabel(row) { return !row.submitted ? '未提交' : row.status === 'VOIDED' ? '已作废' : row.void_error ? '释放未确认' : '作废处理中' },
    async submit() { if (this.saving || !this.confirmed || !this.reason) return; this.saving = true; try { this.results = (await voidOrders({ orders: this.orders, reason: this.reason, confirm_local_void: 1 })).data; this.submitted = true; this.$emit('changed'); await this.refresh() } finally { this.saving = false } },
    async refresh() { this.stop(); const ids = this.results.filter(r => r.submitted).map(r => r.id); if (!ids.length || this.loading) return; this.loading = true; try { const fresh = (await voidResults(ids)).data; const byId = new Map(fresh.map(r => [String(r.id), r])); this.results = this.results.map(r => ({ ...r, ...(byId.get(String(r.id)) || {}) })); this.polls++; const pending = this.results.some(r => r.submitted && r.status === 'VOIDING' && !r.void_error); if (pending && this.visible && this.polls < 12) this.timer = setTimeout(() => this.refresh().catch(() => {}), 5000); if (!pending) this.$emit('changed') } finally { this.loading = false } }
  }
}
</script>
<style scoped>.muted{color:#8490a2}</style>
