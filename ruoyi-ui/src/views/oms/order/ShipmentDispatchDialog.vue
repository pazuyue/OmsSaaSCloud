<template>
  <el-dialog :title="querying ? '核对仓库下发结果' : hasSubmitted ? '仓库下发结果' : '下发发货单至仓库'" :visible.sync="visible" width="min(1120px, 96vw)" append-to-body :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" class="shipment-dispatch-dialog" @closed="stopPolling">
    <div v-loading="loading" class="dispatch-body">
      <el-alert :title="hasSubmitted ? '核对结果包含仓库受理与实际出库进度；实际出库数量及运单以已处理的出库回传为准。' : querying ? '核对原下发任务的仓库受理结果，商品明细取原下发快照。' : '仅下发校验通过的发货单；商品数量取本次发货单，支持分仓和部分发货。'" type="info" :closable="false" show-icon />
      <div v-if="!hasSubmitted" class="metrics">
        <div><span>已选发货单</span><strong>{{ items.length }} <small>张</small></strong></div>
        <div><span>{{ querying ? '可核对' : '可下发' }}</span><strong class="success">{{ eligible.length }} <small>张 / {{ eligibleQuantity }} 件</small></strong></div>
        <div><span>{{ loading ? '校验中' : querying ? '不可核对' : '不可下发' }}</span><strong :class="blocked.length ? 'failure' : ''">{{ blocked.length }} <small>张</small></strong></div>
        <div><span>涉及实体仓</span><strong>{{ warehouseCount }} <small>个</small></strong></div>
      </div>
      <div v-else class="metrics results-metrics"><div v-for="metric in resultMetrics" :key="metric.label"><span>{{ metric.label }}</span><strong :class="metric.tone">{{ metric.count }} <small>张</small></strong></div></div>
      <div v-if="hasSubmitted" class="result-heading"><span class="hint">{{ polling ? '正在自动刷新处理进度' : '自动刷新已停止，可手动刷新进度' }}{{ refreshedAt ? ' · 最近刷新 ' + refreshedAt : '' }}<span v-if="pollError" class="failure"> · 部分进度读取失败，请稍后刷新</span></span><el-button size="mini" :loading="refreshing" :disabled="saving || !!busyId" @click="refreshResults">刷新进度</el-button></div>
      <el-tabs v-if="!hasSubmitted && !querying" v-model="activeGroup"><el-tab-pane :label="`可下发（${eligible.length}）`" name="eligible" /><el-tab-pane :label="`不可下发（${blocked.length}）`" name="blocked" /></el-tabs>
      <el-empty v-if="!loading && !displayRows.length" :description="activeGroup === 'blocked' ? '所选发货单均可下发' : '没有可下发的发货单，请查看不可下发原因'" :image-size="65" />
      <section v-for="group in warehouseGroups" :key="group.key" class="warehouse-group">
        <div class="warehouse-heading"><strong>{{ group.name }}</strong><span>{{ group.rows.length }} 张 / {{ group.quantity }} 件</span><span class="hint">{{ group.owner ? '货主 ' + group.owner : '' }}</span></div>
        <el-collapse v-model="expanded"><el-collapse-item v-for="row in group.rows" :key="row.id" :name="String(row.id)">
          <template slot="title"><div class="shipment-heading"><span class="shipment-number">{{ row.shipment_sn || original(row.id).shipment_sn || '发货单 ' + row.id }}</span><span>{{ row.lines && row.lines.length ? skuCount(row.lines) + ' 种 SKU / ' : '' }}{{ row.quantity || 0 }} 件</span><el-tag size="small" :type="rowTone(row)">{{ rowLabel(row) }}</el-tag><el-tag v-if="row.route" size="small" :type="row.route.environment === 'PRODUCTION' ? 'warning' : 'info'" effect="plain">{{ row.route.environment === 'PRODUCTION' ? '正式环境' : '测试环境' }}</el-tag><span v-if="(!hasSubmitted && !row.success) || ['FAILED','UNKNOWN','BLOCKED','NOT_SUBMITTED'].includes(row.state)" class="header-message" :title="row.message">{{ row.message }}</span></div></template>
          <div class="shipment-context"><span>OMS：{{ row.order_sn || '—' }}</span><span>平台：{{ row.platform_tid || '—' }}</span><span>店铺：{{ row.channel_name || '—' }}</span><span>快递：{{ row.carrier_name || '尚未匹配' }}</span></div>
          <div v-if="row.route" class="connection-info"><span>仓库连接：{{ row.route.connection_name }}</span><el-popover placement="bottom" width="310" trigger="click"><div class="connection-detail">协议：{{ row.route.provider === 'QIMEN' ? '奇门' : '京东虎符' }}<br>外部仓库：{{ row.route.external_warehouse }}<br>外部货主：{{ row.route.external_owner }}<br>仓库快递编码：{{ row.warehouse_carrier_code || '—' }}</div><el-button slot="reference" type="text" size="small">连接详情</el-button></el-popover></div>
          <p :class="['row-message', rowTone(row) === 'danger' ? 'failure' : '']">{{ row.message || '—' }}</p><p v-if="row.wms_external_order" class="warehouse-number">仓库单号：{{ row.wms_external_order }}</p>
          <p v-if="hasSubmitted || querying" class="fulfillment-summary">实际出库 {{ row.shipped_quantity || 0 }} / {{ row.quantity || 0 }} 件 · 剩余 {{ Math.max(0, Number(row.quantity || 0) - Number(row.shipped_quantity || 0)) }} 件 · 已记录 {{ row.package_count || 0 }} 个包裹</p>
          <shipment-dispatch-lines :lines="row.lines || []" :frozen="row.snapshot_scope === 'FROZEN'" :show-actual="hasSubmitted || querying" />
          <div class="row-actions">
            <el-button v-if="canMatch(row) && (!hasSubmitted || row.state === 'BLOCKED')" v-hasPermi="['order:shipment:carrier']" size="mini" @click="$refs.carrier.open([original(row.id)])">匹配 / 调整快递</el-button>
            <el-button v-if="configurationIssue(row) && (!hasSubmitted || row.state === 'BLOCKED')" v-hasPermi="['warehouse:wms:config']" size="mini" @click="$refs.connections.open()">查看仓库配置</el-button>
            <el-button v-if="canQuery(row)" v-hasPermi="['order:shipment:queryWarehouse']" size="mini" :loading="busyId === row.id" :disabled="saving || refreshing || !!busyId" @click="queryOne(row)">核对仓库结果</el-button>
            <el-button v-if="canRetry(row)" v-hasPermi="['order:shipment:dispatch']" size="mini" :disabled="saving || refreshing || !!busyId" @click="retryOne(row)">重试下发</el-button>
            <el-button v-if="row.order_id" v-hasPermi="['order:order:query']" size="mini" :disabled="saving || !!busyId" @click="openOrder(row)">查看来源订单</el-button><el-button v-hasPermi="['order:order:query']" size="mini" :disabled="saving || !!busyId" @click="openDetail(row)">查看发货单</el-button>
            <el-button v-if="hasSubmitted && row.state !== 'BLOCKED' && row.state !== 'NOT_SUBMITTED'" v-hasPermi="['warehouse:wms:log']" size="mini" @click="$refs.logs.open(row.shipment_sn)">仓库交互日志</el-button>
          </div>
        </el-collapse-item></el-collapse>
      </section><p v-if="saving" class="hint">正在提交本次操作，请等待逐单结果。</p>
    </div>
    <span slot="footer"><el-button :disabled="saving || !!busyId" @click="visible=false">{{ hasSubmitted ? '关闭' : '取消' }}</el-button><el-button v-if="!hasSubmitted && !querying" :disabled="loading || saving" @click="reloadPreview">重新校验</el-button><el-button v-if="!hasSubmitted" type="primary" :disabled="loading || !eligible.length" :loading="saving" @click="submit">{{ querying ? `核对仓库结果（${eligible.length} 张）` : `确认下发 ${eligible.length} 张 / ${eligibleQuantity} 件` }}</el-button></span>
    <shipment-carrier-dialog ref="carrier" @changed="reloadPreview" /><wms-connections ref="connections" @changed="reloadPreview" /><wms-logs ref="logs" />
  </el-dialog>
</template>
<script>
import { previewShipmentDispatch, dispatchShipments, queryShipmentWarehouse, shipmentDetail } from '@/api/order'
import { dispatchLabel, warehouseProgress } from './shipmentLabels'
import ShipmentDispatchLines from './ShipmentDispatchLines'
import ShipmentCarrierDialog from './ShipmentCarrierDialog'
import WmsConnections from '@/views/oms/warehouse/WmsConnections'
import WmsLogs from '@/views/oms/warehouse/WmsLogs'
const pendingStates = ['SUBMITTED', 'PENDING', 'PROCESSING', 'UNKNOWN']
export default {
  components: { ShipmentDispatchLines, ShipmentCarrierDialog, WmsConnections, WmsLogs },
  data() { return { visible: false, loading: false, saving: false, querying: false, hasSubmitted: false, items: [], preview: [], results: [], current: {}, requestId: 0, activeGroup: 'eligible', expanded: [], timer: null, polling: false, refreshing: false, refreshedAt: '', pollError: false, pollCount: 0, busyId: null } },
  computed: {
    eligible() { return this.preview.filter(row => row.success) }, blocked() { return this.preview.filter(row => !row.success) },
    eligibleQuantity() { return this.eligible.reduce((sum, row) => sum + Number(row.quantity || 0), 0) },
    warehouseCount() { return new Set(this.preview.filter(row => row.real_store_code).map(row => row.real_store_code)).size },
    displayRows() { return this.hasSubmitted ? this.results : this.querying ? this.preview : this.activeGroup === 'blocked' ? this.blocked : this.eligible },
    warehouseGroups() { const groups = new Map(); this.displayRows.forEach(row => { const key = JSON.stringify([row.real_store_code || '', row.owner_code || '']); if (!groups.has(key)) groups.set(key, { key, name: row.real_store_name || row.real_store_code || '仓库未确认', owner: row.owner_code, quantity: 0, rows: [] }); const group = groups.get(key); group.rows.push(row); group.quantity += Number(row.quantity || 0) }); return [...groups.values()] },
    resultMetrics() { return [{ label: '全部出库', count: this.results.filter(r => warehouseProgress(r).state === 'SHIPPED').length, tone: 'success' }, { label: '部分出库', count: this.results.filter(r => warehouseProgress(r).state === 'PART_SHIPPED').length, tone: 'warning' }, { label: '仓库已接收', count: this.results.filter(r => r.state === 'ACCEPTED' && !Number(r.shipped_quantity)).length, tone: 'success' }, { label: '等待处理', count: this.results.filter(r => ['SUBMITTED', 'PENDING', 'PROCESSING'].includes(r.state)).length }, { label: '结果待确认', count: this.results.filter(r => r.state === 'UNKNOWN').length, tone: 'warning' }, { label: '下发失败', count: this.results.filter(r => r.state === 'FAILED').length, tone: 'failure' }, { label: '未下发', count: this.results.filter(r => ['BLOCKED', 'NOT_SUBMITTED', 'CANCELED'].includes(r.state)).length }] }
  },
  watch: { visible(value) { if (!value) { this.requestId++; this.stopPolling() } } },
  beforeDestroy() { this.requestId++; this.stopPolling() },
  methods: {
    progressLines(detail) { return (detail.dispatch_lines || []).map(line => { const actual = (detail.lines || []).find(value => String(value.id) === String(line.id)); return { ...line, shipped_quantity: actual ? actual.shipped_quantity : 0, packaged_quantity: actual ? actual.packaged_quantity : 0 } }) },
    original(id) { return this.current[id] || this.items.find(row => row.id === id) || {} }, skuCount(lines = []) { return new Set(lines.map(line => line.sku_sn)).size },
    rowLabel(row) { if (this.hasSubmitted || this.querying) { const progress = warehouseProgress(row); if (progress.label) return progress.label } return this.hasSubmitted ? ({ SUBMITTED: '已提交，等待处理', BLOCKED: '校验未通过', NOT_SUBMITTED: '未提交' })[row.state] || dispatchLabel(row.state) : this.querying ? dispatchLabel(row.wms_dispatch_state) : row.success ? row.retry ? '可重试下发' : '可下发' : '不可下发' },
    rowTone(row) { if (Number(row.shipped_quantity) > 0) return Number(row.shipped_quantity) >= Number(row.quantity) ? 'success' : 'warning'; return this.hasSubmitted ? row.state === 'ACCEPTED' ? 'success' : ['FAILED', 'BLOCKED'].includes(row.state) ? 'danger' : ['NOT_SUBMITTED', 'CANCELED'].includes(row.state) ? 'info' : 'warning' : row.success ? 'success' : 'danger' },
    canMatch(row) { return !row.success && !!this.original(row.id).can_match_carrier }, configurationIssue(row) { return !row.success && /连接|配置|外部仓库|货主|虚仓|出库方式/.test(row.message || '') },
    canQuery(row) { return !!this.original(row.id).can_query_warehouse && (this.hasSubmitted ? !['BLOCKED', 'NOT_SUBMITTED', 'CANCELED'].includes(row.state) : !row.success) },
    canRetry(row) { return this.hasSubmitted && row.state === 'FAILED' && !!this.original(row.id).can_dispatch },
    stopPolling() { clearTimeout(this.timer); this.timer = null; this.polling = false },
    async open(rows, action = 'SUBMIT') {
      if (this.saving || !rows.length) return
      this.stopPolling(); const id = ++this.requestId; this.visible = true; this.items = rows.map(row => ({ ...row })); this.current = {}; this.querying = action === 'QUERY'; this.hasSubmitted = false; this.results = []; this.preview = []; this.expanded = rows.length === 1 ? [String(rows[0].id)] : []; this.activeGroup = 'eligible'; this.loading = true; this.refreshing = false; this.busyId = null; this.pollError = false; this.refreshedAt = ''; this.pollCount = 0
      try {
        if (this.querying) {
          for (const row of this.items) { try { const detail = (await shipmentDetail(row.id)).data; if (id !== this.requestId) return; this.$set(this.current, row.id, detail); this.preview.push({ ...detail, lines: this.progressLines(detail), quantity: detail.dispatch_quantity || detail.quantity, route: detail.warehouse_target, snapshot_scope: 'FROZEN', success: !!detail.can_query_warehouse, message: warehouseProgress(detail).message }) } catch (_) { if (id !== this.requestId) return; this.preview.push({ ...row, success: false, message: '商品快照与仓库任务暂未加载，请刷新重试' }) } }
        } else {
          for (let offset = 0; offset < this.items.length; offset += 20) { const chunk = this.items.slice(offset, offset + 20); try { const r = await previewShipmentDispatch({ items: chunk.map(row => ({ id: row.id, control_version: row.control_version })) }); if (id !== this.requestId) return; this.preview.push(...r.data.map(row => ({ ...this.original(row.id), ...row }))) } catch (_) { if (id !== this.requestId) return; this.preview.push(...chunk.map(row => ({ ...row, success: false, message: '下发校验暂未完成，请重新校验；本批未提交' }))) } }
        }
        if (!this.eligible.length) this.activeGroup = 'blocked'
      } finally { if (id === this.requestId) this.loading = false }
    },
    async reloadPreview() {
      if (this.saving || this.loading) return
      const id = this.requestId; this.loading = true; const rows = []
      try { for (const row of this.items) { const detail = (await shipmentDetail(row.id)).data; if (id !== this.requestId) return; rows.push(detail) } } catch (_) { this.$message.error('发货单最新状态读取失败，请稍后重新校验'); return } finally { if (id === this.requestId) this.loading = false }
      if (id === this.requestId) { this.$emit('changed'); this.open(rows, this.querying ? 'QUERY' : 'SUBMIT') }
    },
    async submit() {
      if (this.saving || this.loading || this.hasSubmitted || !this.eligible.length) return
      const id = this.requestId; const rows = [...this.eligible]; this.saving = true; this.hasSubmitted = true; this.results = this.blocked.map(row => ({ ...row, state: 'BLOCKED', attempted: false }))
      try {
        if (this.querying) {
          for (const row of rows) { try { const data = (await queryShipmentWarehouse(row.id)).data; if (id !== this.requestId) return; this.results.push({ ...row, state: data.dispatch_state || 'UNKNOWN', attempted: true, message: data.query_message || data.message || data.last_error || '结果暂未确认，请稍后核对' }) } catch (_) { if (id !== this.requestId) return; this.results.push({ ...row, state: 'UNKNOWN', attempted: true, message: '仓库查询未确认，请稍后核对' }) } }
        } else {
          for (let offset = 0; offset < rows.length; offset += 20) {
            const chunk = rows.slice(offset, offset + 20)
            try { const r = await dispatchShipments({ items: chunk.map(row => ({ id: row.id, control_version: row.control_version })) }); if (id !== this.requestId) return; this.results.push(...chunk.map(row => { const result = r.data.find(value => value.id === row.id); return { ...row, attempted: true, state: !result || result.pending ? 'UNKNOWN' : result.success ? 'SUBMITTED' : 'NOT_SUBMITTED', message: result ? result.message : '提交结果未返回，请核对仓库结果' } })) }
            catch (_) { if (id !== this.requestId) return; this.results.push(...chunk.map(row => ({ ...row, attempted: true, state: 'UNKNOWN', message: '提交结果未确认，请核对仓库结果' })), ...rows.slice(offset + 20).map(row => ({ ...row, attempted: false, state: 'NOT_SUBMITTED', message: '尚未提交，请重新选择校验' }))); break }
          }
        }
      } finally { if (id === this.requestId) { this.saving = false; this.$emit('changed'); await this.refreshResults() } }
    },
    async refreshResults() {
      if (!this.visible || !this.hasSubmitted || this.refreshing) return
      this.stopPolling(); const id = this.requestId; this.refreshing = true; this.pollError = false
      try {
        for (const row of this.results.filter(value => value.attempted)) {
          try { const detail = (await shipmentDetail(row.id)).data; if (id !== this.requestId) return; this.$set(this.current, row.id, detail); const state = detail.wms_dispatch_state
            if (state && state !== 'NONE') { row.state = state; row.message = warehouseProgress(detail).message } else if (row.state === 'UNKNOWN') row.message = '本地下发任务暂未确认，请刷新进度核对'
            for (const field of ['status', 'shipped_quantity', 'packaged_quantity', 'package_count', 'wms_warehouse_status', 'wms_progress_message', 'wms_fulfillment_state']) this.$set(row, field, detail[field]); this.$set(row, 'wms_external_order', detail.wms_external_order); row.route = detail.warehouse_target || row.route
            if (detail.dispatch_lines) { row.lines = this.progressLines(detail); row.quantity = detail.dispatch_quantity; row.snapshot_scope = 'FROZEN' }
          } catch (_) { if (id === this.requestId) this.pollError = true }
        }
        if (id === this.requestId) this.refreshedAt = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      } finally { if (id === this.requestId) { this.refreshing = false; this.pollCount++; if (this.visible && this.pollCount < 40 && this.results.some(row => pendingStates.includes(row.state))) { this.polling = true; this.timer = setTimeout(() => this.refreshResults(), 3000) } } }
    },
    async queryOne(row) {
      if (this.busyId || this.refreshing || this.saving) return
      if (!this.hasSubmitted) { this.open([this.original(row.id)], 'QUERY'); return }
      const id = this.requestId; this.stopPolling(); this.busyId = row.id
      try { const data = (await queryShipmentWarehouse(row.id)).data; if (id !== this.requestId) return; row.state = data.dispatch_state || 'UNKNOWN'; row.message = data.query_message || data.message || data.last_error || '受理结果待确认'; this.$emit('changed') } catch (_) { if (id === this.requestId) row.message = '仓库查询未确认，请稍后核对' } finally { if (id === this.requestId) { this.busyId = null; await this.refreshResults() } }
    },
    retryOne(row) { if (this.canRetry(row)) this.open([this.original(row.id)]) },
    openOrder(row) { if (this.saving || this.busyId) return; this.visible = false; this.$router.push({ path: '/oms-order/business', query: { view: 'business', orderId: String(row.order_id) } }) },
    openDetail(row) { if (this.saving || this.busyId) return; this.visible = false; this.$emit('open-detail', row.id) }
  }
}
</script>
<style scoped>
.dispatch-body{max-height:calc(100vh - 255px);min-height:230px;overflow:auto;padding-right:8px}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:18px 0}.metrics>div{border:1px solid #e5eaf1;border-radius:8px;padding:14px 16px;background:#f8fafc}.metrics span{display:block;font-size:13px;color:#68778d;margin-bottom:8px}.metrics strong{font-size:24px;color:#27374c}.metrics small{font-size:13px;font-weight:400}.metrics strong.success{color:#27824c}.metrics strong.failure,.failure{color:#c04b40}.metrics strong.warning{color:#a46a10}.results-metrics{grid-template-columns:repeat(auto-fit,minmax(125px,1fr))}.warehouse-group{margin:14px 0 20px;border:1px solid #e4e9f0;border-radius:8px;overflow:hidden}.warehouse-heading{display:flex;flex-wrap:wrap;align-items:center;gap:16px;padding:12px 16px;background:#f4f7fb;color:#36485f}.warehouse-group /deep/ .el-collapse{border:0}.warehouse-group /deep/ .el-collapse-item__header{height:auto;min-height:52px;padding:8px 16px;line-height:1.6}.warehouse-group /deep/ .el-collapse-item__content{padding:0 18px 18px}.shipment-heading{display:flex;flex-wrap:wrap;align-items:center;gap:12px;min-width:0;font-size:13px}.shipment-number{color:#245f9c;font-weight:600;overflow-wrap:anywhere}.header-message{color:#a45c32;font-size:12px;flex-basis:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:850px}.shipment-context{display:flex;flex-wrap:wrap;gap:8px 24px;color:#637188;font-size:13px;margin:8px 0;overflow-wrap:anywhere}.connection-info{display:flex;gap:16px;align-items:center;font-size:13px;color:#52647c}.connection-detail{line-height:1.9;overflow-wrap:anywhere}.row-message{font-size:13px;line-height:1.6;color:#52647c;margin:10px 0}.row-message.failure{color:#c04b40}.fulfillment-summary{font-size:13px;color:#36485f;margin:10px 0}.warehouse-number{font-size:13px;color:#245f9c}.row-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}.row-actions .el-button+.el-button{margin-left:0}.hint{font-size:13px;color:#7b899d;line-height:1.6}.result-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.shipment-dispatch-dialog /deep/ .el-dialog__body{padding-top:12px}.shipment-dispatch-dialog /deep/ .el-dialog{margin-top:5vh!important;margin-bottom:20px}@media(max-width:650px){.metrics,.results-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.metrics>div{padding:10px}.metrics strong{font-size:20px}.dispatch-body{max-height:calc(100vh - 235px)}.shipment-dispatch-dialog /deep/ .el-dialog__body{padding:12px}.shipment-dispatch-dialog /deep/ .el-dialog__footer{padding:10px}.shipment-heading{gap:8px}.header-message{white-space:normal}}
</style>
