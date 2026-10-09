<template>
  <div class="app-container inventory-workspace">
    <el-button v-if="productReturn" type="text" icon="el-icon-arrow-left" @click="$router.push(productReturn)">返回商品库存</el-button>
    <filter-panel :model="query" :primary-fields="['storeCode', 'skuSn']">
      <el-form :inline="true" size="small" @submit.native.prevent="search">
        <el-form-item label="虚仓" prop="storeCode">
          <el-select v-model="query.storeCode" filterable remote allow-create clearable default-first-option :remote-method="searchStores" placeholder="名称或仓库编码" :loading="storeSearching">
            <el-option v-for="store in storeOptions" :key="store.wmsSimulationCode" :label="`${store.wmsSimulationName} · ${store.wmsSimulationCode}`" :value="store.wmsSimulationCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品" prop="skuSn">
          <el-select v-model="query.skuSn" filterable remote allow-create clearable default-first-option :remote-method="searchGoods" placeholder="商品名称 / SKU / 条码" :loading="goodsSearching">
            <el-option v-for="goods in goodsOptions" :key="goods.skuSn" :label="`${goods.goodsName || '未命名商品'} · ${goods.skuSn}`" :value="goods.skuSn" />
          </el-select>
        </el-form-item>
        <el-form-item><el-checkbox v-model="query.onlyStock">仅有库存</el-checkbox></el-form-item>
        <el-form-item><el-checkbox v-model="query.abnormal">仅看差异</el-checkbox></el-form-item>
        <el-form-item><el-button type="primary" icon="el-icon-search" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </filter-panel>
    <div class="inventory-toolbar">
      <span>共 <strong>{{ total }}</strong> 条库存 <span class="muted">· 实际 = 可用 + 预占</span></span>
      <div class="toolbar-actions">
        <el-button v-hasPermi="['wmsInventory:inventory:export']" size="small" icon="el-icon-download" @click="exportPage">导出当前页</el-button>
        <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="loadList">刷新</el-button>
        <el-checkbox v-model="showDefective">次品列</el-checkbox>
      </div>
    </div>
    <el-alert v-if="listError" title="库存加载失败" type="error" show-icon :closable="false" description="请点击刷新重试，原有查询条件已保留。" />
    <el-table v-loading="loading" :data="rows" row-key="id" :empty-text="listError ? '加载失败，请重试' : '没有符合条件的库存'">
      <el-table-column label="商品 / SKU" min-width="190" :fixed="mobile ? false : 'left'"><template slot-scope="s"><div class="cell-title">{{ goodsName(s.row.skuSn) }}</div><span class="muted code">{{ s.row.skuSn }}</span></template></el-table-column>
      <el-table-column label="虚仓" min-width="170"><template slot-scope="s"><div>{{ storeName(s.row.storeCode) }}</div><span class="muted code">{{ s.row.storeCode }}</span></template></el-table-column>
      <el-table-column label="正品" align="center">
        <el-table-column label="实际" prop="zpActualNumber" min-width="85" align="right" />
        <el-table-column label="可用" min-width="85" align="right"><template slot-scope="s"><strong class="available">{{ s.row.zpAvailableNumber }}</strong></template></el-table-column>
        <el-table-column label="预占" prop="zpLockNumber" min-width="85" align="right" />
      </el-table-column>
      <el-table-column v-if="showDefective" label="次品" align="center">
        <el-table-column label="实际" prop="cpActualNumber" min-width="85" align="right" />
        <el-table-column label="可用" prop="cpAvailableNumber" min-width="85" align="right" />
        <el-table-column label="预占" prop="cpLockNumber" min-width="85" align="right" />
      </el-table-column>
      <el-table-column label="批次核对" width="125"><template slot-scope="s"><el-tag :type="s.row.consistent ? 'info' : 'warning'" size="small">{{ s.row.consistent ? `${s.row.batchCount} 个批次` : '存在差异' }}</el-tag></template></el-table-column>
      <el-table-column label="更新时间" prop="modifyTime" width="165" />
      <el-table-column label="操作" width="95" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button type="text" @click="openDetail(s.row)">查看详情</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" :page-sizes="[10, 20, 50, 100]" @pagination="loadList" />

    <el-drawer title="库存详情" :visible.sync="drawer" :size="mobile ? '100%' : 'min(1080px, 86vw)'" append-to-body custom-class="inventory-drawer" @closed="closeDetail">
      <div v-if="detail" v-loading="detailLoading" class="inventory-detail">
        <batch-trace v-if="selectedBatch" :key="selectedBatch.id" :batch-id="Number(selectedBatch.id)" :inventory="detail" :initial-tab="batchTraceTab" @back="selectedBatch = null" @rule="goBatchRule" />
        <template v-else>
          <el-button v-if="productReturn" type="text" icon="el-icon-arrow-left" @click="$router.push(productReturn)">返回商品库存</el-button>
          <div class="detail-heading"><div><h3>{{ goodsName(detail.skuSn) }}</h3><p>{{ detail.skuSn }} <span class="muted">· {{ storeName(detail.storeCode) }} / {{ detail.storeCode }}</span></p></div><el-button size="small" icon="el-icon-refresh" @click="refreshDetail">刷新</el-button></div>
          <el-alert v-if="detailError" title="详情刷新失败，请重试" type="error" :closable="false" show-icon />
          <el-alert v-else-if="!detail.consistent" title="汇总与批次库存不一致" type="warning" show-icon :closable="false" description="下表为当前虚仓全部批次的核对结果。请按来源单据核实差异；处理前暂不允许调整库存。" />
          <div class="balance-grid">
            <div class="balance-head"><span>数量口径</span><span>仓库汇总</span><span>批次合计</span><span>差额</span></div>
            <div v-for="field in quantityFields" :key="field.key" class="balance-row"><span>{{ field.label }}</span><strong>{{ detail[field.key] }}</strong><span>{{ detail.batchTotals[field.key] }}</span><span :class="{ difference: difference(field.key) !== 0 }">{{ difference(field.key) }}</span></div>
          </div>
          <el-tabs v-model="activeTab" @tab-click="loadTab">
            <el-tab-pane label="批次明细" name="batches">
              <div class="detail-toolbar"><el-input v-model="batchQuery.batchCode" clearable placeholder="搜索批次编码" size="small" @keyup.enter.native="searchBatches" @clear="searchBatches" /><el-button size="small" @click="searchBatches">查询</el-button><el-button v-hasPermi="['wmsInventoryBatch:batch:export']" size="small" @click="exportBatches">导出当前页</el-button></div>
              <div v-if="batchError" class="load-error">批次加载失败。<el-button type="text" @click="loadBatches">重新加载</el-button></div>
              <el-table v-loading="batchLoading" :data="batches" :empty-text="batchError ? '加载失败' : detail.consistent ? '该虚仓暂无批次记录' : '未找到匹配批次，请核对仓库编码及入库来源'">
                <el-table-column label="批次编码" min-width="150"><template slot-scope="s"><div>{{ s.row.batchCode }}</div><el-button v-hasPermi="['wmsInventoryBatch:batch:query']" type="text" @click="openBatch(s.row)">查看详情</el-button></template></el-table-column>
                <el-table-column v-for="field in quantityFields" :key="field.key" :label="field.label" :prop="field.key" width="95" align="right" />
                <el-table-column label="批次成本" prop="transactionPrice" width="95" align="right" />
                <el-table-column label="操作" width="95" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button v-hasPermi="['wmsInventory:inventory:edit']" type="text" :disabled="!detail.consistent || detailError" @click="openAdjustment(s.row)">库存调整</el-button></template></el-table-column>
              </el-table>
              <pagination v-show="batchTotal > 0" :total="batchTotal" :page.sync="batchQuery.pageNum" :limit.sync="batchQuery.pageSize" :page-sizes="[10, 20, 50, 100]" @pagination="loadBatches" />
            </el-tab-pane>
            <el-tab-pane label="预占记录" name="reservations" />
            <el-tab-pane label="库存流水" name="history" />
          </el-tabs>
          <div v-if="activeTab !== 'batches'">
            <p class="muted">{{ activeTab === 'reservations' ? '按来源单据记录预占与释放；历史导入库存未补造明细。' : '记录本次升级后的入库、调整、预占和释放。' }}</p>
            <div v-if="historyError" class="load-error">记录加载失败。<el-button type="text" @click="loadHistory">重新加载</el-button></div>
            <el-table v-loading="historyLoading" :data="history" :empty-text="historyError ? '加载失败' : '暂无可追溯记录'">
              <el-table-column label="时间" prop="operationTime" width="165" />
              <el-table-column label="类型" width="90"><template slot-scope="s">{{ operationName(s.row.operationType) }}</template></el-table-column>
              <el-table-column label="批次" prop="batchCode" min-width="130" show-overflow-tooltip />
              <el-table-column label="来源单号" prop="relationSn" min-width="150" show-overflow-tooltip />
              <el-table-column label="数量变化" width="100" align="right"><template slot-scope="s">{{ s.row.changeQuantity > 0 ? '+' : '' }}{{ s.row.changeQuantity }}</template></el-table-column>
              <el-table-column label="批次数量变化" min-width="190"><template slot-scope="s"><div>{{ s.row.inventoryType === 'CP' ? '次品' : '正品' }}可用 {{ s.row.inventoryType === 'CP' ? s.row.oldCpAvailableNumber : s.row.oldZpAvailableNumber }} → {{ s.row.inventoryType === 'CP' ? s.row.newCpAvailableNumber : s.row.newZpAvailableNumber }}</div><span class="muted">正品预占 {{ s.row.oldZpLockNumber }} → {{ s.row.newZpLockNumber }}</span></template></el-table-column>
              <el-table-column label="原因" prop="changeReason" min-width="150" show-overflow-tooltip />
              <el-table-column label="操作人" prop="operatorName" width="95" />
            </el-table>
            <pagination v-show="historyTotal > 0" :total="historyTotal" :page.sync="historyQuery.pageNum" :limit.sync="historyQuery.pageSize" :page-sizes="[10, 20, 50, 100]" @pagination="loadHistory" />
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog title="库存调整" :visible.sync="adjustOpen" width="540px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" custom-class="inventory-adjustment">
      <template v-if="adjustBatch">
        <p class="muted">{{ detail.storeCode }} · {{ detail.skuSn }} · 批次 {{ adjustBatch.batchCode }}</p>
        <el-form ref="adjustForm" :model="adjustment" :rules="adjustRules" label-position="top">
          <el-form-item label="库存类型"><el-radio-group v-model="adjustment.inventoryType" :disabled="saving"><el-radio-button label="ZP">正品</el-radio-button><el-radio-button label="CP">次品</el-radio-button></el-radio-group></el-form-item>
          <el-form-item label="调整数量（正数增加，负数减少）" prop="quantity"><el-input-number v-model="adjustment.quantity" :precision="0" :step="1" :min="-100000000" :max="100000000" :disabled="saving" /></el-form-item>
          <div class="adjust-preview"><span>实际 {{ adjustmentBefore.actual }} → <strong>{{ adjustmentBefore.actual + (adjustment.quantity || 0) }}</strong></span><span>可用 {{ adjustmentBefore.available }} → <strong>{{ adjustmentBefore.available + (adjustment.quantity || 0) }}</strong></span><span>预占 {{ adjustmentBefore.locked }}（不变）</span></div>
          <el-form-item label="调整原因" prop="reason"><el-input v-model="adjustment.reason" type="textarea" :rows="3" maxlength="255" show-word-limit placeholder="填写盘点差异或调整依据" :disabled="saving" /></el-form-item>
        </el-form>
      </template>
      <span slot="footer"><el-button :disabled="saving" @click="adjustOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitAdjustment">确认调整并记录</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { inventoryList, inventoryDetail, inventoryHistory, adjustInventory, lookupGoods, lookupStores } from '@/api/wmsInventory/workspace'
import { listInventoryBatch } from '@/api/wmsInventoryBatch/wmsInventoryBatch'
import { saveAs } from 'file-saver'
import BatchTrace from './components/BatchTrace'

const defaults = () => ({ pageNum: 1, pageSize: 20, storeCode: '', skuSn: '', onlyStock: false, abnormal: false })
export default {
  name: 'Inventory',
  components: { BatchTrace },
  data() {
    return {
      query: defaults(), rows: [], total: 0, loading: false, listError: false, showDefective: true,
      goodsOptions: [], storeOptions: [], goodsMap: {}, storeMap: {}, goodsSearching: false, storeSearching: false,
      drawer: false, detail: null, detailLoading: false, detailError: false, activeTab: 'batches', selectedBatch: null, batchTraceTab: 'history',
      batches: [], batchTotal: 0, batchLoading: false, batchError: false, batchQuery: { pageNum: 1, pageSize: 20, batchCode: '' },
      history: [], historyTotal: 0, historyLoading: false, historyError: false, historyQuery: { pageNum: 1, pageSize: 20 },
      adjustOpen: false, adjustBatch: null, saving: false, adjustment: {},
      quantityFields: [{ key: 'zpActualNumber', label: '正品实际' }, { key: 'zpAvailableNumber', label: '正品可用' }, { key: 'zpLockNumber', label: '正品预占' }, { key: 'cpActualNumber', label: '次品实际' }, { key: 'cpAvailableNumber', label: '次品可用' }, { key: 'cpLockNumber', label: '次品预占' }],
      adjustRules: { reason: [{ required: true, min: 2, max: 255, message: '请填写至少 2 字的调整原因', trigger: 'blur' }], quantity: [{ validator: (rule, value, callback) => Number.isInteger(value) && value !== 0 ? callback() : callback(new Error('请输入非零整数')), trigger: 'change' }] }
    }
  },
  computed: {
    productReturn() { const value = this.$route.query.productReturn; return typeof value === 'string' && /^\/oms-inventory\/productInventory(?:\?|$)/.test(value) ? value : '' },
    mobile() { return this.$store.state.app.device === 'mobile' },
    adjustmentBefore() {
      const prefix = this.adjustment.inventoryType === 'CP' ? 'cp' : 'zp'
      const batch = this.adjustBatch || {}
      return { actual: batch[prefix + 'ActualNumber'] || 0, available: batch[prefix + 'AvailableNumber'] || 0, locked: batch[prefix + 'LockNumber'] || 0 }
    }
  },
  watch: { '$route.query.inventoryId'() { if (this.$route.path === '/oms-inventory/wmsInventory') this.openLinkedInventory() } },
  created() { this.listSeq = 0; this.detailSeq = 0; this.batchSeq = 0; this.historySeq = 0; this.goodsSeq = 0; this.storeSeq = 0; const q = this.$route.query; if (q.skuSn) this.query.skuSn = String(q.skuSn); if (q.storeCode) this.query.storeCode = String(q.storeCode); this.query.pageNum = Math.max(1, Math.min(100000, Number(q.pageNum) || 1)); this.query.pageSize = [10, 20, 50, 100].includes(Number(q.pageSize)) ? Number(q.pageSize) : 20; this.query.onlyStock = q.onlyStock === 'true'; this.query.abnormal = q.abnormal === 'true'; this.loadList(); this.searchStores(''); this.openLinkedInventory() },
  beforeDestroy() { clearTimeout(this.goodsTimer); clearTimeout(this.storeTimer); this.listSeq++; this.detailSeq++; this.batchSeq++; this.historySeq++ },
  methods: {
    async openLinkedInventory() { const id = this.$route.query.inventoryId; if (!/^\d+$/.test(id || '')) return; try { const r = await inventoryDetail(id); if (String(this.$route.query.inventoryId) === String(id) && this.$route.path === '/oms-inventory/wmsInventory') { this.loadNames([r.data]); this.openDetail(r.data); const q = this.$route.query; this.batchQuery.batchCode = String(q.batchCode || ''); this.batchQuery.pageNum = Math.max(1, Math.min(100000, Number(q.batchPage) || 1)); if (/^\d+$/.test(q.batchId || '')) { this.batchTraceTab = q.batchTab === 'sources' ? 'sources' : 'history'; this.selectedBatch = { id: Number(q.batchId) } } } } catch (_) { /* Request layer displays missing or unauthorized records. */ } },
    openBatch(row) { this.batchTraceTab = 'history'; this.selectedBatch = row },
    goBatchRule({ ruleId, tab }) { const query = { ...this.query, inventoryId: this.detail.id, batchId: this.selectedBatch.id, batchTab: tab, batchCode: this.batchQuery.batchCode, batchPage: this.batchQuery.pageNum }; if (this.productReturn) query.productReturn = this.productReturn; const inventoryReturn = this.$router.resolve({ path: '/oms-inventory/wmsInventory', query }).route.fullPath; this.$router.push({ path: '/oms-inventory/ruleStock', query: { ruleId, inventoryReturn }}) },
    goodsName(sku) { return this.goodsMap[sku] || '商品资料未匹配' },
    storeName(code) { return this.storeMap[code] || '仓库资料未匹配' },
    operationName(type) { return { ADJUST: '库存调整', RECEIVE: '入库', LOCK: '预占', UNLOCK: '释放', CONSUME: '出库' }[type] || type },
    difference(key) { return Number(this.detail[key]) - Number(this.detail.batchTotals[key]) },
    search() { this.query.pageNum = 1; this.loadList() },
    reset() { this.query = defaults(); this.loadList() },
    async loadList() {
      const seq = ++this.listSeq
      this.loading = true; this.listError = false
      try {
        const result = await inventoryList(this.query)
        if (seq !== this.listSeq) return
        this.rows = result.rows; this.total = result.total
        if (this.rows.length) this.loadNames(this.rows)
      } catch (e) { if (seq === this.listSeq) { this.rows = []; this.total = 0; this.listError = true } } finally { if (seq === this.listSeq) this.loading = false }
    },
    loadNames(rows) {
      const skus = [...new Set(rows.map(row => row.skuSn))].filter(sku => !this.goodsMap[sku])
      const codes = [...new Set(rows.map(row => row.storeCode))].filter(code => !this.storeMap[code])
      if (skus.length) lookupGoods({ skus: skus.join(',') }).then(r => r.data.forEach(g => this.$set(this.goodsMap, g.skuSn, g.goodsName))).catch(() => {})
      if (codes.length) lookupStores({ codes: codes.join(',') }).then(r => r.data.forEach(s => this.$set(this.storeMap, s.wmsSimulationCode, s.wmsSimulationName))).catch(() => {})
    },
    searchGoods(keyword) {
      clearTimeout(this.goodsTimer); const seq = ++this.goodsSeq
      this.goodsTimer = setTimeout(async() => {
        this.goodsSearching = true
        try { const r = await lookupGoods({ keyword }); if (seq === this.goodsSeq) this.goodsOptions = r.data } catch (e) { if (seq === this.goodsSeq) this.goodsOptions = [] } finally { if (seq === this.goodsSeq) this.goodsSearching = false }
      }, 300)
    },
    searchStores(keyword) {
      clearTimeout(this.storeTimer); const seq = ++this.storeSeq
      this.storeTimer = setTimeout(async() => {
        this.storeSearching = true
        try { const r = await lookupStores({ keyword }); if (seq === this.storeSeq) this.storeOptions = r.data } catch (e) { if (seq === this.storeSeq) this.storeOptions = [] } finally { if (seq === this.storeSeq) this.storeSearching = false }
      }, 300)
    },
    openDetail(row) {
      this.selectedBatch = null
      this.detailSeq++; this.batchSeq++; this.historySeq++
      this.detail = row; this.drawer = true; this.detailError = false; this.activeTab = 'batches'
      this.batches = []; this.batchTotal = 0; this.history = []; this.historyTotal = 0
      this.batchQuery = { pageNum: 1, pageSize: 20, batchCode: '' }; this.historyQuery = { pageNum: 1, pageSize: 20 }
      this.refreshDetail()
    },
    closeDetail() { this.detailSeq++; this.batchSeq++; this.historySeq++; this.selectedBatch = null },
    async refreshDetail() {
      const seq = ++this.detailSeq; this.detailLoading = true; this.detailError = false
      try { const r = await inventoryDetail(this.detail.id); if (seq !== this.detailSeq) return; this.detail = r.data; this.loadTab() } catch (e) { if (seq === this.detailSeq) this.detailError = true } finally { if (seq === this.detailSeq) this.detailLoading = false }
    },
    loadTab() { if (this.activeTab === 'batches') this.loadBatches(); else { this.historyQuery.pageNum = 1; this.loadHistory() } },
    searchBatches() { this.batchQuery.pageNum = 1; this.loadBatches() },
    async loadBatches() {
      const seq = ++this.batchSeq; this.batchLoading = true; this.batchError = false
      try {
        const r = await listInventoryBatch({ ...this.batchQuery, storeCode: this.detail.storeCode, skuSn: this.detail.skuSn })
        if (seq !== this.batchSeq) return
        this.batches = r.rows; this.batchTotal = r.total
      } catch (e) { if (seq === this.batchSeq) { this.batchError = true; this.batches = []; this.batchTotal = 0 } } finally { if (seq === this.batchSeq) this.batchLoading = false }
    },
    async loadHistory() {
      const seq = ++this.historySeq; this.historyLoading = true; this.historyError = false
      try {
        const r = await inventoryHistory(this.detail.id, { ...this.historyQuery, operation: this.activeTab === 'reservations' ? 'LOCK' : '' })
        if (seq !== this.historySeq) return
        this.history = r.rows; this.historyTotal = r.total
      } catch (e) { if (seq === this.historySeq) { this.historyError = true; this.history = []; this.historyTotal = 0 } } finally { if (seq === this.historySeq) this.historyLoading = false }
    },
    openAdjustment(batch) {
      this.adjustBatch = batch
      this.adjustment = { batchId: batch.id, version: batch.version, inventoryType: 'ZP', quantity: 0, reason: '', requestId: window.crypto.randomUUID().replace(/-/g, '') }
      this.adjustOpen = true; this.$nextTick(() => this.$refs.adjustForm.clearValidate())
    },
    submitAdjustment() {
      this.$refs.adjustForm.validate(async valid => {
        if (!valid || this.saving) return
        if (this.adjustmentBefore.available + this.adjustment.quantity < 0) { this.$modal.msgError('调整后可用库存不能为负数'); return }
        this.saving = true
        try { await adjustInventory(this.adjustment); this.adjustOpen = false; this.$modal.msgSuccess('库存已调整，流水已记录'); this.loadList(); this.refreshDetail() } catch (e) { /* Preserve the request ID after an uncertain response, so a retry cannot double-adjust. */ } finally { this.saving = false }
      })
    },
    csv(rows, fields, name) {
      const escape = value => { let text = String(value == null ? '' : value); if (/^[=+@\-\t\r]/.test(text)) text = "'" + text; return '"' + text.replace(/"/g, '""') + '"' }
      const lines = [fields.map(f => escape(f.label)).join(','), ...rows.map(row => fields.map(f => escape(row[f.key])).join(','))]
      saveAs(new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }), name + '.csv')
    },
    exportPage() { this.csv(this.rows, [{ key: 'storeCode', label: '虚仓编码' }, { key: 'skuSn', label: 'SKU' }, ...this.quantityFields, { key: 'modifyTime', label: '更新时间' }], '仓库库存-当前页') },
    exportBatches() { this.csv(this.batches, [{ key: 'batchCode', label: '批次编码' }, ...this.quantityFields, { key: 'transactionPrice', label: '批次成本' }], '批次库存-当前页') }
  }
}
</script>

<style scoped>
.inventory-toolbar,.toolbar-actions,.detail-heading,.detail-toolbar { display:flex; align-items:center; justify-content:space-between; gap:14px; }
.inventory-toolbar { margin:8px 0 18px; font-size:13px; color:#465166; }
.toolbar-actions { justify-content:flex-end; flex-wrap:wrap; }
.muted { color:#7d8797; font-size:12px; }
.code { font-variant-numeric:tabular-nums; }
.cell-title { color:#202939; font-weight:500; }
.available { color:#246bd6; font-variant-numeric:tabular-nums; }
.inventory-workspace ::v-deep .el-select { width:100%; }
.inventory-detail { padding:0 26px 28px; }
.detail-heading { margin-bottom:20px; }
.detail-heading h3 { margin:0; font-size:20px; font-weight:600; }
.detail-heading p { margin:8px 0 0; font-size:13px; }
.balance-grid { margin:20px 0; padding:12px 16px; background:#f6f8fb; border-radius:12px; font-size:13px; font-variant-numeric:tabular-nums; }
.balance-head,.balance-row { display:grid; grid-template-columns:1.2fr 1fr 1fr 1fr; padding:7px 0; gap:8px; }
.balance-head { color:#7d8797; font-size:12px; }
.balance-grid span:not(:first-child),.balance-grid strong { text-align:right; }
.difference { color:#b86a12; font-weight:600; }
.detail-toolbar { justify-content:flex-start; margin:6px 0 16px; }
.detail-toolbar .el-input { width:230px; }
.load-error { padding:12px 0; color:#b44b40; }
.adjust-preview { display:flex; flex-wrap:wrap; gap:12px; padding:14px; margin:0 0 20px; background:#f6f8fb; border-radius:10px; font-size:13px; }
@media(max-width:767px) { .inventory-toolbar { align-items:flex-start; flex-direction:column; } .inventory-detail { padding:0 16px 20px; } .detail-toolbar { flex-wrap:wrap; } .detail-heading { align-items:flex-start; } }
</style>
<style>
.inventory-drawer .el-drawer__body { overflow-y:auto; }
.inventory-drawer .el-drawer__header { margin-bottom:20px; color:#687385; }
@media(max-width:600px) { .inventory-adjustment { width:calc(100vw - 24px) !important; } }
</style>
