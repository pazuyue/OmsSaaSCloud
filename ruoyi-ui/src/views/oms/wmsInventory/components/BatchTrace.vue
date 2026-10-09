<template>
  <section v-loading="loading" class="batch-trace">
    <div class="trace-toolbar"><el-button type="text" icon="el-icon-arrow-left" @click="$emit('back')">返回批次明细</el-button><el-button size="small" icon="el-icon-refresh" :loading="loading" @click="loadDetail">刷新批次</el-button></div>
    <el-alert v-if="error" title="批次详情加载失败或不属于当前仓库，请刷新重试" type="error" :closable="false" show-icon />
    <template v-else-if="detail">
      <h3>批次 {{ detail.batchCode }}</h3><p class="muted">{{ detail.skuSn }} · {{ detail.storeCode }} · 批次成本 {{ detail.transactionPrice == null ? '—' : detail.transactionPrice }} · 更新 {{ detail.modifyTime || '—' }}</p>
      <div class="trace-balances"><div class="quantity-row muted"><span>库存类型</span><span>实际</span><span>可用</span><span>锁定</span></div><div v-for="type in qualityTypes" :key="type.key" class="quantity-row"><span>{{ type.label }}</span><strong>{{ detail[type.key + 'ActualNumber'] }}</strong><strong class="available">{{ detail[type.key + 'AvailableNumber'] }}</strong><strong>{{ detail[type.key + 'LockNumber'] }}</strong></div></div>
      <el-alert v-if="summary.inconsistent" title="来源余额与批次锁定数量不一致，请按业务单据核对。" type="warning" :closable="false" show-icon />
      <p class="muted">本批次可追溯分货锁库 {{ summary.trackedLocked }}，其中订单占用 {{ summary.occupiedQuantity }}，未占用余额 {{ summary.releasableQuantity }}。其他或待核对的正品锁定 {{ summary.otherLocked }}<span v-if="Number(summary.unknownSources)">；{{ summary.unknownSources }} 条来源待核对</span>。</p>
      <el-tabs v-model="tab" @tab-click="changeTab"><el-tab-pane label="本批次流水" name="history" /><el-tab-pane label="锁库去向" name="sources" /></el-tabs>
      <div v-if="tab === 'history'" class="trace-toolbar"><el-select v-model="operation" clearable size="small" placeholder="全部流水类型" @change="filter"><el-option v-for="(label, key) in operationNames" :key="key" :label="label" :value="key" /></el-select><span class="muted">仅展示明确关联本批次的记录，历史缺少批次关联的流水不推算归属。</span></div>
      <p v-else class="muted">以下数量仅属于当前批次。订单占用已包含在剩余锁定中，释放请进入原分货单处理。</p>
      <div v-if="rowsError" class="load-error">记录加载失败 <el-button type="text" @click="loadRows">重新加载</el-button></div>
      <el-table v-if="tab === 'history'" key="history" v-loading="rowsLoading" :data="rows" empty-text="暂无明确关联本批次的流水">
        <el-table-column label="时间" prop="operationTime" width="165" /><el-table-column label="类型" width="95"><template slot-scope="s">{{ operationNames[s.row.operationType] || s.row.operationType }}</template></el-table-column>
        <el-table-column label="来源 / 订单行" min-width="175"><template slot-scope="s"><el-button v-if="ruleId(s.row.relationSn)" v-hasPermi="['ruleStock:info:list']" type="text" @click="goRule(ruleId(s.row.relationSn))">{{ s.row.relationSn }}</el-button><span v-else>{{ s.row.relationSn || '—' }}</span><div class="muted">{{ s.row.orderLine || '' }}</div></template></el-table-column>
        <el-table-column label="本次数量" width="95" align="right"><template slot-scope="s">{{ Math.abs(s.row.changeQuantity) }}</template></el-table-column>
        <el-table-column label="批次变化" min-width="230"><template slot-scope="s"><div>实际 {{ sum(s.row, 'old', 'ActualNumber') }} → {{ sum(s.row, 'new', 'ActualNumber') }}</div><div class="muted">可用 {{ sum(s.row, 'old', 'AvailableNumber') }} → {{ sum(s.row, 'new', 'AvailableNumber') }} · 锁定 {{ sum(s.row, 'old', 'LockNumber') }} → {{ sum(s.row, 'new', 'LockNumber') }}</div></template></el-table-column>
        <el-table-column label="原因" prop="changeReason" min-width="150" /><el-table-column label="操作人" prop="operatorName" width="100" />
      </el-table>
      <el-table v-else key="sources" v-loading="rowsLoading" :data="rows" empty-text="暂无可追溯的分货锁库来源">
        <el-table-column label="分货单 / 渠道" min-width="200"><template slot-scope="s"><div>{{ s.row.ruleName || '原分货单待核对' }}</div><div class="muted">{{ s.row.ruleCode || s.row.ruleId }}</div><div>{{ s.row.channelName }}</div></template></el-table-column>
        <el-table-column v-for="c in sourceColumns" :key="c.key" :label="c.label" width="100" align="right"><template slot-scope="s">{{ s.row.tracked ? s.row[c.key] : '待核对' }}</template></el-table-column>
        <el-table-column label="操作" width="125" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button type="text" :disabled="!s.row.tracked" @click="openOrders(s.row)">查看订单</el-button><br><el-button v-if="s.row.ruleCode" v-hasPermi="['ruleStock:info:list']" type="text" @click="goRule(s.row.ruleId)">查看分货单</el-button></template></el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" :page-sizes="[10,20,50,100]" @pagination="pageSources" />
      <div v-if="tab === 'sources' && selectedSource" class="source-orders">
        <div class="trace-toolbar"><h4>{{ selectedSource.channelName }} · 订单占用明细</h4><el-button type="text" @click="closeOrders">收起</el-button></div>
        <p class="muted">{{ selectedSource.ruleName }} · 只展示本渠道在当前批次的订单数量。</p>
        <el-checkbox v-model="orderQuery.onlyOccupied" @change="filterOrders">仅看仍占用</el-checkbox>
        <div v-if="ordersError" class="load-error">订单明细加载失败 <el-button type="text" @click="loadOrders">重新加载</el-button></div>
        <el-table v-loading="ordersLoading" :data="orders" empty-text="暂无符合条件的订单占用记录"><el-table-column label="订单行编号" prop="orderLine" min-width="210" /><el-table-column label="原订单占用" prop="originalQuantity" width="115" align="right" /><el-table-column label="当前占用" prop="occupiedQuantity" width="100" align="right" /><el-table-column label="已出库" prop="consumedQuantity" width="100" align="right" /><el-table-column label="已取消" prop="cancelledQuantity" width="100" align="right" /></el-table>
        <pagination v-show="orderTotal > 0" :total="orderTotal" :page.sync="orderQuery.pageNum" :limit.sync="orderQuery.pageSize" :page-sizes="[10,20,50,100]" @pagination="loadOrders" />
      </div>
    </template>
  </section>
</template>

<script>
import { batchTraceDetail, batchTraceRows, batchSourceOrders } from '@/api/wmsInventoryBatch/trace'
export default {
  name: 'BatchTrace',
  props: { batchId: { type: Number, required: true }, inventory: { type: Object, required: true }, initialTab: { type: String, default: 'history' }},
  data() {
    return {
      detail: null, loading: false, error: false, tab: this.initialTab === 'sources' ? 'sources' : 'history', operation: '',
      rows: [], total: 0, rowsLoading: false, rowsError: false, query: { pageNum: 1, pageSize: 20 },
      selectedSource: null, orders: [], orderTotal: 0, ordersLoading: false, ordersError: false, orderQuery: { pageNum: 1, pageSize: 20, onlyOccupied: true },
      operationNames: { RECEIVE: '入库', ADJUST: '库存调整', LOCK: '锁库', UNLOCK: '释放', CONSUME: '出库' },
      qualityTypes: [{ key: 'zp', label: '正品' }, { key: 'cp', label: '次品' }],
      sourceColumns: [{ key: 'originalQuantity', label: '原锁库' }, { key: 'remainingQuantity', label: '剩余锁定' }, { key: 'occupiedQuantity', label: '其中订单占用' }, { key: 'consumedQuantity', label: '已出库' }, { key: 'releasedQuantity', label: '已释放' }, { key: 'releasableQuantity', label: '可释放' }]
    }
  },
  computed: { mobile() { return this.$store.state.app.device === 'mobile' }, summary() { return this.detail ? this.detail.reservationSummary : {} } },
  created() { this.detailSeq = 0; this.rowsSeq = 0; this.ordersSeq = 0; this.loadDetail() },
  beforeDestroy() { this.detailSeq++; this.rowsSeq++; this.ordersSeq++ },
  methods: {
    sum(row, prefix, field) { return Number(row[prefix + 'Zp' + field] || 0) + Number(row[prefix + 'Cp' + field] || 0) },
    ruleId(value) { const m = /^RULE-(\d+)$/.exec(value || ''); return m ? Number(m[1]) : null },
    goRule(ruleId) { this.$emit('rule', { ruleId, tab: this.tab }) },
    async loadDetail() { const seq = ++this.detailSeq; this.rowsSeq++; this.ordersSeq++; this.loading = true; this.error = false; this.closeOrders(); try { const r = await batchTraceDetail(this.batchId); if (seq !== this.detailSeq) return; if (!r.data || r.data.skuSn !== this.inventory.skuSn || r.data.storeCode !== this.inventory.storeCode) throw new Error('Batch does not belong to warehouse'); this.detail = r.data; this.loadRows() } catch (_) { if (seq === this.detailSeq) { this.error = true; this.detail = null } } finally { if (seq === this.detailSeq) this.loading = false } },
    changeTab() { this.closeOrders(); this.rows = []; this.total = 0; this.query.pageNum = 1; this.loadRows() },
    filter() { this.query.pageNum = 1; this.loadRows() },
    pageSources() { this.closeOrders(); this.loadRows() },
    async loadRows() { if (!this.detail || this.error) return; const seq = ++this.rowsSeq; this.rowsLoading = true; this.rowsError = false; try { const r = await batchTraceRows(this.batchId, this.tab, { ...this.query, operation: this.tab === 'history' ? this.operation : undefined }); if (seq !== this.rowsSeq) return; this.rows = r.rows; this.total = r.total } catch (_) { if (seq === this.rowsSeq) { this.rows = []; this.total = 0; this.rowsError = true } } finally { if (seq === this.rowsSeq) this.rowsLoading = false } },
    openOrders(source) { this.selectedSource = source; this.orders = []; this.orderTotal = 0; this.orderQuery = { pageNum: 1, pageSize: 20, onlyOccupied: true }; this.loadOrders() },
    closeOrders() { this.ordersSeq++; this.selectedSource = null; this.orders = []; this.orderTotal = 0 },
    filterOrders() { this.orderQuery.pageNum = 1; this.loadOrders() },
    async loadOrders() { if (!this.selectedSource) return; const seq = ++this.ordersSeq; this.ordersLoading = true; this.ordersError = false; try { const r = await batchSourceOrders(this.batchId, this.selectedSource.id, this.orderQuery); if (seq !== this.ordersSeq) return; this.orders = r.rows; this.orderTotal = r.total } catch (_) { if (seq === this.ordersSeq) { this.orders = []; this.orderTotal = 0; this.ordersError = true } } finally { if (seq === this.ordersSeq) this.ordersLoading = false } }
  }
}
</script>

<style scoped>
.trace-toolbar { display:flex; justify-content:space-between; align-items:center; gap:16px; margin:6px 0 14px; }.trace-toolbar .el-select { width:180px; flex-shrink:0; }.batch-trace h3 { margin:10px 0; font-size:22px; font-weight:600; }.muted { color:#7d8797; font-size:12px; line-height:1.8; overflow-wrap:anywhere; }.trace-balances { padding:12px 18px; margin:20px 0; border-radius:12px; background:#f6f8fb; }.quantity-row { display:grid; grid-template-columns:1.3fr 1fr 1fr 1fr; gap:12px; padding:8px 0; font-size:13px; font-variant-numeric:tabular-nums; }.quantity-row > :not(:first-child) { text-align:right; }.available { color:#246bd6; }.source-orders { border-top:1px solid #e9ebef; margin-top:28px; padding-top:14px; }.source-orders h4 { font-size:16px; margin:0; }.load-error { color:#b44b40; padding:12px 0; }
@media(max-width:767px) { .trace-toolbar { flex-wrap:wrap; align-items:flex-start; }.trace-balances { padding:10px; }.quantity-row { gap:8px; } }
</style>
