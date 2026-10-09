<template>
  <div class="app-container product-inventory">
    <filter-panel :model="query" :primary-fields="['skuSn']">
      <el-form :inline="true" size="small" @submit.native.prevent="search">
        <el-form-item label="商品" prop="skuSn"><el-select v-model="query.skuSn" filterable remote allow-create default-first-option clearable :remote-method="searchGoods" :loading="goodsSearching" placeholder="商品名称 / SKU / 条码"><el-option v-for="g in options" :key="g.skuSn" :value="g.skuSn" :label="`${g.goodsName || '未命名商品'} · ${g.skuSn}`" /></el-select></el-form-item>
        <el-form-item><el-checkbox v-model="query.onlyStock">仅有库存</el-checkbox></el-form-item>
        <el-form-item><el-button type="primary" icon="el-icon-search" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </filter-panel>
    <div class="product-toolbar"><div>共 <strong>{{ total }}</strong> 个商品 <span class="muted">· 总库存含正次品，正品可用不等同于渠道可售</span></div><div class="product-actions"><el-checkbox v-model="showDefective">显示次品</el-checkbox><el-button size="small" icon="el-icon-refresh" :loading="loading" @click="loadList">刷新</el-button></div></div>
    <el-alert v-if="listError" title="商品库存加载失败，请刷新重试" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" row-key="id" :empty-text="listError ? '加载失败' : '暂无符合条件的商品库存'">
      <el-table-column label="商品 / SKU" min-width="210" :fixed="mobile ? false : 'left'"><template slot-scope="s"><el-button type="text" class="product-name" @click="open(s.row)">{{ goodsName(s.row.skuSn) }}</el-button><div class="muted">{{ s.row.skuSn }}</div></template></el-table-column>
      <el-table-column label="总库存" prop="totalStock" width="105" align="right" />
      <el-table-column label="正品 · 仓库合计" align="center"><el-table-column v-for="c in stockColumns" :key="c.key" :label="c.label" width="100" align="right"><template slot-scope="s"><strong :class="{ available: c.key === 'AvailableNumber' }">{{ s.row.warehouseTotals['zp' + c.key] }}</strong></template></el-table-column></el-table-column>
      <el-table-column v-if="showDefective" label="次品 · 仓库合计" align="center"><el-table-column v-for="c in stockColumns" :key="c.key" :label="c.label" width="100" align="right"><template slot-scope="s">{{ s.row.warehouseTotals['cp' + c.key] }}</template></el-table-column></el-table-column>
      <el-table-column label="核对状态" min-width="160"><template slot-scope="s"><el-tooltip :content="s.row.checkMessage" placement="top"><el-tag size="small" :type="s.row.checkStatus === 'CONSISTENT' ? 'success' : 'warning'">{{ checkNames[s.row.checkStatus] }}</el-tag></el-tooltip><div class="muted">{{ s.row.warehouseTotals.warehouseCount || 0 }} 个仓库</div></template></el-table-column>
      <el-table-column label="操作" width="105" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button type="text" @click="open(s.row)">查看详情</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" :page-sizes="[10,20,50,100]" @pagination="searchPage" />
    <p class="scope-note">按当前公司已记账的虚仓库存核对，包含停用仓的现存库存；不叠加实体仓数据。存在差异时，请查看仓库和批次来源。</p>

    <el-drawer title="商品库存详情" :visible.sync="drawer" :size="mobile ? '100%' : 'min(1180px, 90vw)'" append-to-body custom-class="product-inventory-drawer" @close="closeDetail">
      <div v-if="detail" v-loading="detailLoading" class="product-detail">
        <div class="product-heading"><div><h2>{{ goodsName(detail.skuSn) }}</h2><span class="muted">{{ detail.skuSn }}</span></div><el-button size="small" icon="el-icon-refresh" @click="refreshDetail">刷新详情</el-button></div>
        <el-alert v-if="detailError" title="详情刷新失败，请重试" type="error" :closable="false" show-icon />
        <template v-else-if="detail.warehouseTotals">
          <el-alert v-if="detail.checkStatus !== 'CONSISTENT'" :title="detail.checkMessage" type="warning" :closable="false" show-icon />
          <div class="product-balance">
            <div class="balance-row muted"><span>数量口径</span><span>商品汇总</span><span>仓库合计</span><span>批次合计</span><span>仓库 − 商品</span></div>
            <div v-for="c in summaryColumns" :key="c.key" class="balance-row"><span>{{ c.label }}</span><strong>{{ detail[c.key] }}</strong><span>{{ detail.warehouseTotals[c.key] }}</span><span>{{ detail.batchTotals[c.key] }}</span><strong :class="{ difference: delta(c.key) !== 0 }">{{ delta(c.key) > 0 ? '+' : '' }}{{ delta(c.key) }}</strong></div>
          </div>
          <p v-if="detail.reservationSummary" class="scope-note">可追溯分货锁库 {{ detail.reservationSummary.trackedLocked }}，其中订单占用 {{ detail.reservationSummary.occupiedQuantity }}。其他或待核对的正品锁定 {{ detail.reservationSummary.otherLocked }}。订单占用已包含在锁定数量中。</p>
          <el-tabs v-model="tab" @tab-click="changeTab">
            <el-tab-pane label="仓库分布" name="warehouses" />
            <el-tab-pane label="锁库来源" name="reservations" />
            <el-tab-pane label="库存流水" name="history" />
          </el-tabs>
          <div v-if="tab === 'history'" class="product-toolbar"><el-select v-model="historyOperation" clearable size="small" placeholder="全部流水类型" @change="filterHistory"><el-option v-for="(label, key) in operationNames" :key="key" :label="label" :value="key" /></el-select><span class="muted">历史导入未补造流水；出库记录可查看订单行。</span></div>
          <el-alert v-if="tab === 'reservations'" title="仅展示有执行记录的锁库分货单；来源不完整的记录显示待核对。释放请进入对应分货单。" type="info" :closable="false" />
          <div v-if="tabError" class="load-error">记录加载失败 <el-button type="text" @click="loadTab">重新加载</el-button></div>
          <el-table v-if="tab === 'warehouses'" key="warehouses" v-loading="tabLoading" :data="tabRows" empty-text="暂无仓库明细">
            <el-table-column label="仓库" min-width="165"><template slot-scope="s"><div>{{ storeMap[s.row.storeCode] || '仓库资料未匹配' }}</div><span class="muted">{{ s.row.storeCode }}</span></template></el-table-column>
            <el-table-column label="正品" align="center"><el-table-column v-for="c in stockColumns" :key="c.key" :label="c.label" :prop="'zp' + c.key" width="85" align="right" /></el-table-column>
            <el-table-column label="次品" align="center"><el-table-column v-for="c in stockColumns" :key="c.key" :label="c.label" :prop="'cp' + c.key" width="85" align="right" /></el-table-column>
            <el-table-column label="批次核对" width="115"><template slot-scope="s"><el-tag size="small" :type="s.row.consistent ? 'info' : 'warning'">{{ s.row.consistent ? `${s.row.batchCount} 个批次` : s.row.batchCount ? '存在差异' : '缺少批次' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="100" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button v-hasPermi="['wmsInventory:inventory:query']" type="text" @click="goWarehouse(s.row)">查看批次</el-button></template></el-table-column>
          </el-table>
          <el-table v-else-if="tab === 'reservations'" key="reservations" v-loading="tabLoading" :data="tabRows" empty-text="暂无可追溯的分货锁库记录">
            <el-table-column type="expand"><template slot-scope="s"><el-table :data="s.row.channels" size="small" empty-text="历史渠道来源待核对"><el-table-column label="渠道" prop="channelName" min-width="160" /><el-table-column label="涉及批次" prop="batchCount" width="95" /><el-table-column v-for="c in sourceColumns" :key="c.key" :label="c.label" :prop="c.key === 'allocatedQuantity' ? 'originalQuantity' : c.key" width="100" align="right" /></el-table></template></el-table-column>
            <el-table-column label="分货单" min-width="200"><template slot-scope="s"><div>{{ s.row.ruleName }}</div><span class="muted">{{ s.row.ruleCode }}</span></template></el-table-column>
            <el-table-column v-for="c in sourceColumns" :key="c.key" :label="c.label" width="100" align="right"><template slot-scope="s">{{ c.key === 'allocatedQuantity' || Number(s.row.sourceTracked) === 1 ? s.row[c.key] : '待核对' }}</template></el-table-column>
            <el-table-column label="释放状态" width="110"><template slot-scope="s">{{ releaseNames[s.row.releaseStatus] || '待核对' }}</template></el-table-column>
            <el-table-column label="操作" width="105" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button v-hasPermi="['ruleStock:info:list']" type="text" @click="goRule(s.row.ruleId)">查看分货单</el-button></template></el-table-column>
          </el-table>
          <el-table v-else key="history" v-loading="tabLoading" :data="tabRows" empty-text="暂无可追溯流水">
            <el-table-column label="时间" prop="operationTime" width="165" /><el-table-column label="类型" width="90"><template slot-scope="s">{{ operationNames[s.row.operationType] || s.row.operationType }}</template></el-table-column>
            <el-table-column label="仓库 / 批次" min-width="170"><template slot-scope="s"><div>{{ s.row.storeCode }}</div><span class="muted">{{ s.row.batchCode }}</span></template></el-table-column>
            <el-table-column label="来源 / 订单行" min-width="180"><template slot-scope="s"><el-button v-if="ruleId(s.row.relationSn)" v-hasPermi="['ruleStock:info:list']" type="text" @click="goRule(ruleId(s.row.relationSn))">{{ s.row.relationSn }}</el-button><span v-else>{{ s.row.relationSn || '—' }}</span><div class="muted">{{ s.row.orderLine || '' }}</div></template></el-table-column>
            <el-table-column label="本次数量" width="95" align="right"><template slot-scope="s">{{ Math.abs(s.row.changeQuantity) }}</template></el-table-column>
            <el-table-column label="批次变化" min-width="230"><template slot-scope="s"><div>实际 {{ Number(s.row.oldZpActualNumber) + Number(s.row.oldCpActualNumber) }} → {{ Number(s.row.newZpActualNumber) + Number(s.row.newCpActualNumber) }}</div><div class="muted">可用 {{ Number(s.row.oldZpAvailableNumber) + Number(s.row.oldCpAvailableNumber) }} → {{ Number(s.row.newZpAvailableNumber) + Number(s.row.newCpAvailableNumber) }} · 锁定 {{ Number(s.row.oldZpLockNumber) + Number(s.row.oldCpLockNumber) }} → {{ Number(s.row.newZpLockNumber) + Number(s.row.newCpLockNumber) }}</div></template></el-table-column>
            <el-table-column label="操作人" prop="operatorName" width="110" />
          </el-table>
          <pagination v-show="tabTotal > 0" :total="tabTotal" :page.sync="tabQuery.pageNum" :limit.sync="tabQuery.pageSize" :page-sizes="[10,20,50,100]" @pagination="loadTab" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { productInventoryList, productInventoryDetail, productInventoryRows } from '@/api/wmsInventory/product'
import { lookupGoods, lookupStores } from '@/api/wmsInventory/workspace'
const defaults = () => ({ skuSn: '', onlyStock: false, pageNum: 1, pageSize: 20 })
export default {
  name: 'ProductInventory',
  data() {
    return {
      query: defaults(), rows: [], total: 0, loading: false, listError: false, showDefective: false,
      options: [], goodsMap: {}, storeMap: {}, goodsSearching: false,
      drawer: false, detail: null, detailLoading: false, detailError: false, tab: 'warehouses',
      tabRows: [], tabTotal: 0, tabLoading: false, tabError: false, tabQuery: { pageNum: 1, pageSize: 20 }, historyOperation: '',
      checkNames: { CONSISTENT: '一致', DIFFERENT: '存在差异', INCOMPLETE: '明细不完整' },
      operationNames: { RECEIVE: '入库', ADJUST: '库存调整', LOCK: '锁库', UNLOCK: '释放', CONSUME: '出库' },
      releaseNames: { NONE: '未释放', PENDING: '释放中', PARTIAL: '部分释放', RELEASED: '释放完成', FAILED: '释放失败' },
      stockColumns: [{ key: 'ActualNumber', label: '实际' }, { key: 'AvailableNumber', label: '可用' }, { key: 'LockNumber', label: '锁定' }],
      summaryColumns: [{ key: 'totalStock', label: '总库存' }, { key: 'availableStock', label: '可用（含次品）' }, { key: 'allocatedStock', label: '锁定' }],
      sourceColumns: [{ key: 'allocatedQuantity', label: '原锁库' }, { key: 'occupiedQuantity', label: '其中订单占用' }, { key: 'consumedQuantity', label: '已出库' }, { key: 'releasedQuantity', label: '已释放' }, { key: 'releasableQuantity', label: '可释放' }]
    }
  },
  computed: { mobile() { return this.$store.state.app.device === 'mobile' } },
  created() {
    this.listSeq = 0; this.detailSeq = 0; this.tabSeq = 0; this.goodsSeq = 0
    const q = this.$route.query
    this.query = { skuSn: String(q.skuSn || '').slice(0, 128), onlyStock: q.onlyStock === 'true', pageNum: Math.max(1, Math.min(100000, Number(q.pageNum) || 1)), pageSize: [10, 20, 50, 100].includes(Number(q.pageSize)) ? Number(q.pageSize) : 20 }
    this.loadList()
    if (/^\d+$/.test(q.productId || '')) { this.tab = ['warehouses', 'reservations', 'history'].includes(q.tab) ? q.tab : 'warehouses'; this.open({ id: Number(q.productId) }, true) }
  },
  beforeDestroy() { clearTimeout(this.goodsTimer); this.listSeq++; this.detailSeq++; this.tabSeq++; this.goodsSeq++ },
  methods: {
    goodsName(sku) { return this.goodsMap[sku] || '商品资料未匹配' },
    delta(key) { return Number(this.detail.warehouseTotals[key]) - Number(this.detail[key]) },
    ruleId(value) { const match = /^RULE-(\d+)$/.exec(value || ''); return match ? Number(match[1]) : null },
    syncRoute() { if (this.$route.path !== '/oms-inventory/productInventory') return; const query = { ...this.query }; if (this.drawer && this.detail) { query.productId = this.detail.id; query.tab = this.tab } this.$router.replace({ path: this.$route.path, query }).catch(() => {}) },
    search() { this.query.pageNum = 1; this.searchPage() },
    searchPage() { this.syncRoute(); this.loadList() },
    reset() { this.query = defaults(); this.searchPage() },
    async loadNames(skus) { const missing = [...new Set(skus)].filter(s => s && !this.goodsMap[s]); if (!missing.length) return; try { const r = await lookupGoods({ skus: missing.join(',') }); r.data.forEach(g => this.$set(this.goodsMap, g.skuSn, g.goodsName)) } catch (_) { /* Quantities remain visible when metadata is unavailable. */ } },
    searchGoods(keyword) { clearTimeout(this.goodsTimer); const seq = ++this.goodsSeq; this.goodsTimer = setTimeout(async() => { this.goodsSearching = true; try { const r = await lookupGoods({ keyword }); if (seq === this.goodsSeq) this.options = r.data } catch (_) { if (seq === this.goodsSeq) this.options = [] } finally { if (seq === this.goodsSeq) this.goodsSearching = false } }, 300) },
    async loadList() { const seq = ++this.listSeq; this.loading = true; this.listError = false; try { const r = await productInventoryList(this.query); if (seq !== this.listSeq) return; this.rows = r.rows; this.total = r.total; this.loadNames(r.rows.map(row => row.skuSn)) } catch (_) { if (seq === this.listSeq) { this.rows = []; this.total = 0; this.listError = true } } finally { if (seq === this.listSeq) this.loading = false } },
    open(row, restore = false) { this.detailSeq++; this.tabSeq++; this.detail = row; this.drawer = true; this.detailError = true; if (!restore) this.tab = 'warehouses'; this.tabQuery = { pageNum: 1, pageSize: 20 }; this.historyOperation = ''; this.tabRows = []; this.tabTotal = 0; this.syncRoute(); return this.refreshDetail() },
    closeDetail() { this.detailSeq++; this.tabSeq++; this.drawer = false; this.syncRoute() },
    async refreshDetail() { const seq = ++this.detailSeq; this.detailLoading = true; this.detailError = false; try { const r = await productInventoryDetail(this.detail.id); if (seq !== this.detailSeq) return; this.detail = r.data; this.loadNames([this.detail.skuSn]); this.loadTab() } catch (_) { if (seq === this.detailSeq) this.detailError = true } finally { if (seq === this.detailSeq) this.detailLoading = false } },
    changeTab() { this.tabSeq++; this.tabRows = []; this.tabTotal = 0; this.tabQuery.pageNum = 1; this.syncRoute(); this.loadTab() },
    filterHistory() { this.tabQuery.pageNum = 1; this.loadTab() },
    async loadTab() {
      if (!this.detail || this.detailError) return
      const seq = ++this.tabSeq; const tab = this.tab; this.tabLoading = true; this.tabError = false
      try { const r = await productInventoryRows(this.detail.id, tab, { ...this.tabQuery, operation: tab === 'history' ? this.historyOperation : undefined }); if (seq !== this.tabSeq) return; this.tabRows = r.rows; this.tabTotal = r.total; if (tab === 'warehouses' && r.rows.length) { const names = await lookupStores({ codes: [...new Set(r.rows.map(row => row.storeCode))].join(',') }).catch(() => null); if (seq === this.tabSeq && names) names.data.forEach(s => this.$set(this.storeMap, s.wmsSimulationCode, s.wmsSimulationName)) } } catch (_) { if (seq === this.tabSeq) { this.tabRows = []; this.tabTotal = 0; this.tabError = true } } finally { if (seq === this.tabSeq) this.tabLoading = false }
    },
    goWarehouse(row) { this.$router.push({ path: '/oms-inventory/wmsInventory', query: { inventoryId: row.id, skuSn: row.skuSn, storeCode: row.storeCode, productReturn: this.$route.fullPath }}) },
    goRule(id) { this.$router.push({ path: '/oms-inventory/ruleStock', query: { ruleId: id, productReturn: this.$route.fullPath }}) }
  }
}
</script>

<style scoped>
.product-inventory { color:#1d1d1f; }.product-toolbar,.product-actions,.product-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; }.product-toolbar { margin:8px 0 18px; font-size:13px; }.product-actions { flex-shrink:0; }.product-name { padding:4px 0; font-weight:600; }.muted,.scope-note { color:#7d8797; font-size:12px; line-height:1.7; overflow-wrap:anywhere; }.scope-note { margin:16px 0; }.available { color:#246bd6; }.product-inventory ::v-deep .el-select { width:340px; max-width:100%; }.product-detail { padding:0 26px 28px; }.product-heading { margin-bottom:20px; }.product-heading h2 { font-size:23px; margin:0 0 6px; }.product-balance { background:#f6f8fb; border-radius:12px; padding:14px 16px; margin-top:18px; }.balance-row { display:grid; grid-template-columns:1.3fr 1fr 1fr 1fr 1.2fr; gap:10px; padding:9px 0; font-size:13px; font-variant-numeric:tabular-nums; }.balance-row > :not(:first-child) { text-align:right; }.difference { color:#b86a12; }.load-error { padding:12px 0; color:#b44b40; }
@media(max-width:767px) { .product-toolbar,.product-heading { align-items:flex-start; flex-wrap:wrap; }.product-detail { padding:0 14px 24px; }.product-balance { padding:8px; }.balance-row { gap:6px; font-size:11px; }.product-inventory ::v-deep .el-select { width:100%; } }
</style>
<style>
.product-inventory-drawer .el-drawer__body { overflow-y:auto; }.product-inventory-drawer .el-drawer__header { margin-bottom:20px; color:#687385; }
</style>
