<template>
  <el-dialog title="订单截单与撤回" custom-class="order-cutoff-dialog" :visible.sync="visible" width="min(1060px, 96vw)" top="5vh" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" @closed="stop">
    <el-alert v-if="refreshError && !order" :title="refreshError" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="cutoff-body" v-if="order">
      <div class="identity"><strong>{{ order.order_sn }}</strong><span>平台单号：{{ order.platform_tid }}</span><el-tag size="small" :type="order.cutoff_state === 'PROCESSING' ? 'warning' : 'info'">{{ order.cutoff_state === 'PROCESSING' ? '截单处理中' : order.cutoff_state === 'REPLAN_READY' ? '撤回完成，待人工重新分仓' : '原单与出库记录保留' }}</el-tag></div>
      <el-alert v-if="notice" class="notice" :title="notice.text" :type="notice.type" :closable="false" show-icon role="status" />
      <el-alert v-if="refreshError" class="notice" :title="refreshError" type="error" :closable="false" show-icon />
      <section v-if="activeOperation && order.cutoff_state === 'PROCESSING'" class="cutoff-progress" aria-label="截单处理进度">
        <strong>{{ progressText }}</strong>
        <p v-if="activeOperation.last_checked_at" class="hint">最近完成核对：{{ activeOperation.last_checked_at }}<span v-if="pendingCheck">；本次核对仍在处理中</span></p>
        <p v-else class="hint">暂无已记录的核对完成时间。</p>
        <div v-for="shipment in reviewShipments" :key="shipment.id" class="review-action">
          <span>{{ shipment.shipment_sn }}：{{ Number(shipment.receipt_review_pending_count) > 0 ? '存在待人工核对的出库回传，请先处理回传异常。' : '回传核对状态读取失败，请进入详情核对。' }}</span>
          <el-button v-hasPermi="['order:order:query']" type="primary" size="small" :disabled="saving" @click="openReview(shipment)">处理回传异常</el-button>
        </div>
        <p v-if="reviewShipments.length" class="hint">重新核对截单会检查原申请；回传异常未处理时，待确认部分继续保留库存占用。</p>
      </section>
      <template v-if="canRequest">
        <h4>1. 本次截单目的</h4>
        <el-radio-group v-model="mode" class="mode-options"><el-radio label="TERMINATE" border><strong>终止剩余履约</strong><p>成功截停的未发数量不再发货，释放对应逻辑预占和仓库占用。</p></el-radio><el-radio label="REPLAN" border><strong>撤回重新发货</strong><p>保留销售需求与逻辑预占，撤销原分仓占用，人工确认后生成新的 XS。</p></el-radio></el-radio-group>
        <h4>2. 处理范围</h4>
        <el-radio-group v-model="scope"><el-radio label="ALL">整单未发部分（含未分仓需求）</el-radio><el-radio label="SELECTED" :disabled="!candidates.length">指定发货单的未发部分</el-radio></el-radio-group>
      </template>
      <el-table :data="shipments" size="small" max-height="280" class="shipment-table"><el-table-column v-if="canRequest && scope === 'SELECTED'" label="选择" width="60"><template slot-scope="s"><el-checkbox v-model="selected" :label="s.row.id" :disabled="remaining(s.row) <= 0 || s.row.status === 'VOIDED'" :aria-label="'选择发货单 ' + s.row.shipment_sn"><span /></el-checkbox></template></el-table-column><el-table-column label="XS 发货单 / 虚拟仓" min-width="250"><template slot-scope="s">{{ s.row.shipment_sn }}<div class="hint">{{ s.row.store_code }} · {{ s.row.real_store_name }}</div></template></el-table-column><el-table-column label="原单数量" prop="quantity" width="90" /><el-table-column label="实际已发" width="90"><template slot-scope="s">{{ s.row.shipped_quantity || 0 }}</template></el-table-column><el-table-column label="已截停" width="90"><template slot-scope="s">{{ s.row.cancelled_quantity || 0 }}</template></el-table-column><el-table-column label="剩余未发" width="100"><template slot-scope="s">{{ remaining(s.row) }}</template></el-table-column><el-table-column label="仓库处理" min-width="180"><template slot-scope="s">{{ remaining(s.row) <= 0 ? (s.row.cancelled_quantity ? '已截停原发货单' : '已出库，无待截余量') : s.row.wms_dispatch_state === 'NONE' ? '核实后本地取消' : '需要仓库明确取消' }}<div v-if="s.row.receipt_review_pending_count" class="warning">出库回传待人工核对</div></template></el-table-column></el-table>
      <template v-if="canRequest"><h4>3. 截单原因</h4><el-input v-model.trim="reason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="填写本次截单原因（必填）" /><el-alert class="notice" type="warning" :closable="false" show-icon :title="mode === 'REPLAN' ? '撤回完成后不会立即自动分仓或下发。换仓仍需满足原库存来源与允许仓规则；原人工挂起保持。' : '已出库数量不会返还库存；平台交易、退款及物流拦截需要分别处理。'" /></template>
      <section v-for="op in operations" :key="op.id" class="operation"><div class="operation-heading"><strong>{{ op.mode === 'REPLAN' ? '撤回重新发货' : '终止剩余履约' }}</strong><el-tag size="small" :type="op.state === 'COMPLETED' ? 'success' : 'warning'">{{ op.state === 'COMPLETED' ? '全部处理完成' : op.state === 'PARTIAL' ? '部分成功，余下待核对' : '处理中 / 待确认' }}</el-tag><span class="hint">{{ op.create_time }} · {{ op.operator_name }}</span></div><p class="hint">原因：{{ op.reason }} · {{ op.scope_all ? '整单未发部分' : '指定发货单' }}</p><el-table :data="op.items" size="small"><el-table-column label="发货单" prop="shipment_sn" min-width="270" /><el-table-column label="仓库截停" width="140"><template slot-scope="s"><el-tag size="mini" :type="s.row.warehouse_state === 'CONFIRMED' ? 'success' : 'warning'">{{ s.row.warehouse_state === 'CONFIRMED' ? '已确认截停' : s.row.warehouse_state === 'OUTBOUND' ? '已出库 / 无待截余量' : '尚未确认' }}</el-tag></template></el-table-column><el-table-column label="库存处理" width="150"><template slot-scope="s">{{ s.row.state === 'COMPLETED' ? '已处理并核对' : s.row.warehouse_state === 'CONFIRMED' ? '等待库存回执确认' : '保留原占用' }}</template></el-table-column><el-table-column label="处理说明" min-width="240"><template slot-scope="s">{{ s.row.last_error || (s.row.state === 'COMPLETED' ? '原单据保留，可追溯本次处理' : '只核对原申请，不重复释放') }}</template></el-table-column></el-table><p v-if="op.last_error" class="warning">{{ op.last_error }}</p><p v-if="!op.items.length" class="hint">本次处理尚未分仓的销售需求，无需取消仓库发货单。</p></section>
      <p class="hint">提交后先停止新增履约。只有仓库截停及库存处理均确认，才显示完成；待确认部分保留占用。</p>
    </div>
    <div slot="footer" class="cutoff-footer">
      <p v-if="order && order.cutoff_state === 'PROCESSING'" class="hint">刷新结果只读取进度；重新核对会继续处理原申请，已完成部分不会重复释放库存。</p>
      <el-button :disabled="saving" @click="visible=false">关闭</el-button>
      <el-button :loading="loading" title="只读取最新处理进度，不提交重试" @click="refresh">刷新结果</el-button>
      <el-button v-if="order && order.cutoff_state === 'PROCESSING'" v-hasPermi="['order:order:cutoff']" :type="reviewShipments.length ? 'default' : 'primary'" :loading="saving || !!pendingCheck" :disabled="loading" @click="retry">{{ saving ? '正在提交…' : pendingCheck ? '正在核对…' : '重新核对截单' }}</el-button>
      <el-button v-if="reviewShipments.length" v-hasPermi="['order:order:query']" type="primary" :disabled="saving" @click="openReview(reviewShipments[0])">处理回传异常{{ reviewShipments.length > 1 ? '（' + reviewShipments.length + ' 张）' : '' }}</el-button>
      <el-button key="cutoff-new" v-if="eligible && !creating" v-hasPermi="['order:order:cutoff']" @click="creating=true">发起新截单</el-button>
      <el-button key="cutoff-submit" v-if="canRequest" v-hasPermi="['order:order:cutoff']" type="danger" :disabled="!reason || (scope === 'SELECTED' && !selected.length)" :loading="saving" @click="submit">{{ mode === 'REPLAN' ? '申请撤回' : '申请终止履约' }}</el-button>
    </div>
  </el-dialog>
</template>
<script>
import { orderDetail, cutoffReport, requestCutoff, retryCutoff } from '@/api/order'
export default {
  data() { return { visible: false, loading: false, saving: false, creating: false, order: null, id: null, mode: 'TERMINATE', scope: 'ALL', selected: [], reason: '', operations: [], polls: 0, timer: null, generation: 0, refreshPromise: null, pendingCheck: null, notice: null, refreshError: '' } },
  computed: {
    shipments() { return this.order ? this.order.shipments || [] : [] },
    candidates() { return this.shipments.filter(s => this.remaining(s) > 0 && s.status !== 'VOIDED') },
    eligible() { return this.order && this.order.cutoff_state !== 'PROCESSING' && !['VOIDING', 'VOIDED', 'CLOSED'].includes(this.order.status) && Number(this.order.demand_quantity) > Number(this.order.shipped_quantity || 0) + Number(this.order.cancelled_quantity || 0) },
    canRequest() { return this.creating && this.eligible },
    activeOperation() { return this.order && this.operations.find(op => Number(op.id) === Number(this.order.cutoff_id)) },
    activeShipments() { return this.activeOperation ? this.shipments.filter(s => this.activeOperation.items.some(item => Number(item.shipment_id) === Number(s.id))) : [] },
    reviewShipments() { return this.order && this.order.cutoff_state === 'PROCESSING' ? this.activeShipments.filter(s => this.activeOperation.items.some(item => Number(item.shipment_id) === Number(s.id) && item.state !== 'COMPLETED') && (Number(s.receipt_review_pending_count) > 0 || s.receipt_review_lookup_failed)) : [] },
    progressText() {
      if (!this.activeShipments.length) return '正在核对剩余销售需求及库存处理。'
      const stopped = this.activeShipments.reduce((sum, s) => sum + Number(s.cancelled_quantity || 0), 0)
      const remaining = this.activeShipments.reduce((sum, s) => sum + this.remaining(s), 0)
      return `已截停 ${stopped} 件，剩余 ${remaining} 件待处理；待确认部分继续保留库存占用。`
    }
  },
  beforeDestroy() { this.generation++; this.stop() },
  methods: {
    remaining(row) { return Math.max(0, Number(row.quantity) - Number(row.shipped_quantity || 0) - Number(row.cancelled_quantity || 0)) },
    async open(order, shipment) { this.stop(); this.generation++; this.loading = false; this.saving = false; this.refreshPromise = null; this.pendingCheck = null; this.notice = null; this.refreshError = ''; this.id = typeof order === 'object' ? order.id : order; this.order = null; this.creating = false; this.operations = []; this.mode = 'TERMINATE'; this.scope = shipment ? 'SELECTED' : 'ALL'; this.selected = shipment ? [shipment.id] : []; this.reason = ''; this.polls = 0; this.visible = true; await this.refresh(); this.creating = !this.operations.length && this.eligible },
    async openWithdrawal(order) { await this.open(order); this.mode = 'REPLAN'; this.creating = !!this.eligible },
    stop() { clearTimeout(this.timer); this.timer = null },
    openReview(shipment) { this.stop(); this.visible = false; this.$emit('open-review', shipment.id) },
    refresh() {
      if (!this.id) return Promise.resolve()
      if (this.loading) return this.refreshPromise
      this.stop()
      this.refreshPromise = this.loadReport(this.generation, this.id)
      return this.refreshPromise
    },
    async loadReport(generation, id) {
      this.loading = true
      try {
        const [order, report] = await Promise.all([orderDetail('business', id), cutoffReport(id)])
        if (generation !== this.generation) return
        this.order = order.data; this.operations = report.data.operations; this.refreshError = ''
        this.checkResult(); this.$emit('changed')
      } catch (error) {
        if (generation === this.generation) this.refreshError = '处理进度读取失败，请刷新结果。已提交的申请会保留，请勿重复申请。'
      } finally {
        if (generation === this.generation) {
          this.loading = false; this.polls++
          if (this.visible && this.order && this.order.cutoff_state === 'PROCESSING' && this.polls < 40) this.timer = setTimeout(() => this.refresh(), 3000)
          else if (this.pendingCheck) this.notice = { type: 'info', text: '本次核对尚未确认完成，原申请已保留。请稍后点击“刷新结果”继续查看。' }
        }
      }
    },
    checkResult() {
      if (!this.pendingCheck) return
      const op = this.operations.find(row => Number(row.id) === Number(this.pendingCheck.cutoff_id))
      if (!op || Number(op.checked_version || 0) < Number(this.pendingCheck.check_version)) return
      this.pendingCheck = null
      if (op.state === 'COMPLETED') this.notice = { type: 'success', text: op.mode === 'REPLAN' ? '本次核对已完成撤回，请人工确认重新分仓。' : '本次核对已完成，截单及库存处理结果均已确认。' }
      else {
        const errors = [...new Set(op.items.filter(item => item.state !== 'COMPLETED' && item.last_error).map(item => item.last_error))]
        if (op.last_error) errors.push(op.last_error)
        this.notice = { type: 'warning', text: '本次核对后仍未完成：' + (errors.join('；') || '仍有仓库或库存处理结果待确认') + '。待确认部分继续保留库存占用。' }
      }
      this.$message({ type: this.notice.type, message: this.notice.text, duration: 6000 })
    },
    async submit() {
      if (this.saving || !this.reason || !this.canRequest) return
      const generation = this.generation, id = this.id
      this.saving = true
      try {
        const body = { mode: this.mode, reason: this.reason, control_version: this.order.control_version }
        if (this.scope === 'SELECTED') body.shipment_ids = this.selected
        const response = await requestCutoff(id, body)
        if (generation !== this.generation) return
        const op = response.data.operations[0]
        this.pendingCheck = { cutoff_id: op.id, check_version: op.check_version }
        this.creating = false; this.polls = 0; this.notice = { type: 'info', text: '截单申请已提交，正在核对仓库及库存处理结果。' }
        this.$message.info(this.notice.text)
        await this.refresh()
      } catch (error) {
        if (generation === this.generation) this.notice = { type: 'warning', text: '申请提交结果未确认，请刷新结果核对原申请后再操作。' }
      } finally { if (generation === this.generation) this.saving = false }
    },
    async retry() {
      if (this.saving || this.pendingCheck || this.loading) return
      const generation = this.generation, id = this.id
      this.saving = true; this.stop()
      try {
        const response = await retryCutoff(id, { control_version: this.order.control_version })
        if (generation !== this.generation) return
        this.pendingCheck = response.data; this.polls = 0
        this.notice = { type: 'info', text: '已提交截单核对，正在检查未完成的发货单。' }
        this.$message.info(this.notice.text)
        await this.refresh()
      } catch (error) {
        if (generation === this.generation) this.notice = { type: 'warning', text: '重新核对提交结果未确认，请刷新结果核对原申请后再操作。' }
      } finally { if (generation === this.generation) this.saving = false }
    }
  }
}
</script>
<style scoped>
.cutoff-body{max-height:calc(100vh - 235px);overflow:auto;padding-right:8px;color:#344256}.identity,.operation-heading{display:flex;gap:16px;align-items:center;flex-wrap:wrap}.identity{background:#f3f7fd;padding:16px;border-radius:8px;font-size:13px}.identity strong{overflow-wrap:anywhere}.mode-options{display:grid;grid-template-columns:1fr 1fr;gap:12px;width:100%}.mode-options .el-radio{height:auto;margin:0!important;white-space:normal;padding:16px;display:flex;align-items:flex-start}.mode-options ::v-deep .el-radio__label{line-height:1.7;min-width:0}.mode-options p{font-size:12px;color:#78869b;margin:8px 0 0}.hint{font-size:12px;color:#78869b;line-height:1.7}.warning{font-size:12px;color:#b77b13;line-height:1.7}.shipment-table,.notice{margin-top:16px}.operation{margin-top:20px;border:1px solid #e4eaf3;border-radius:8px;padding:14px}.operation-heading strong{font-size:14px}h4{margin:20px 0 12px;font-size:14px}@media(max-width:720px){.mode-options{grid-template-columns:1fr}}
</style>
<style>
.order-cutoff-dialog{display:flex;flex-direction:column;max-height:90vh}.order-cutoff-dialog>.el-dialog__body{min-height:0;overflow-y:auto}.order-cutoff-dialog>.el-dialog__header,.order-cutoff-dialog>.el-dialog__footer{flex-shrink:0}.order-cutoff-dialog .cutoff-body{max-height:none;overflow:visible}
</style>
<style scoped>
.cutoff-progress{margin-top:16px;padding:14px 16px;border:1px solid #edd9b5;border-radius:8px;background:#fffaf1;font-size:13px;line-height:1.7}.review-action{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:10px}.review-action span{overflow-wrap:anywhere;min-width:0}.cutoff-footer{text-align:right}.cutoff-footer .hint{margin:0 0 10px}@media(max-width:720px){.review-action{align-items:flex-start;flex-direction:column}.cutoff-footer .el-button{margin:6px 0 0 8px}}
</style>
