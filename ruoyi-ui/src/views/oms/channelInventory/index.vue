<template>
  <div class="app-container channel-inventory">
    <filter-panel :model="query" :primary-fields="['channelId', 'skuSn']">
      <el-form :inline="true" size="small" @submit.native.prevent="search">
        <el-form-item label="渠道" prop="channelId"><el-select v-model="query.channelId" filterable remote clearable :remote-method="searchChannels" :loading="channelsSearching" placeholder="输入渠道名称"><el-option v-for="c in channelOptions" :key="c.channelId" :value="c.channelId" :label="c.channelName" /></el-select></el-form-item>
        <el-form-item label="商品" prop="skuSn"><el-select v-model="query.skuSn" filterable remote allow-create default-first-option clearable :remote-method="searchGoods" :loading="goodsSearching" placeholder="商品名称 / SKU / 条码"><el-option v-for="g in goodsOptions" :key="g.skuSn" :value="g.skuSn" :label="`${g.goodsName || '未命名商品'} · ${g.skuSn}`" /></el-select></el-form-item>
        <el-form-item><el-checkbox v-model="query.onlyStock">仅有库存</el-checkbox></el-form-item>
        <el-form-item><el-button type="primary" icon="el-icon-search" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </filter-panel>
    <div class="inventory-toolbar"><span>共 <strong>{{ total }}</strong> 条渠道商品库存</span><div><el-button size="small" icon="el-icon-refresh" :loading="loading" @click="loadList">刷新</el-button><el-button size="small" icon="el-icon-download" :disabled="loading || !rows.length" @click="exportPage">导出当前页</el-button></div></div>
    <el-alert title="普通可售配额不预留仓库库存；锁库量是实际预留的余额，两者分别展示。预占、冻结字段按原记录展示，锁库订单占用请查看详情。" type="info" :closable="false" show-icon />
    <el-alert v-if="listError" title="渠道库存加载失败，请刷新重试" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" row-key="id" :empty-text="listError ? '加载失败' : '暂无符合条件的渠道库存'">
      <el-table-column label="渠道" min-width="170" :fixed="mobile ? false : 'left'"><template slot-scope="s"><strong>{{ channelName(s.row.channelId) }}</strong><div class="muted">渠道 ID {{ s.row.channelId }}</div></template></el-table-column>
      <el-table-column label="商品 / SKU" min-width="220"><template slot-scope="s"><el-button type="text" @click="open(s.row)">{{ goodsName(s.row.skuSn) }}</el-button><div class="muted">{{ s.row.skuSn }}</div></template></el-table-column>
      <el-table-column v-for="c in quantities" :key="c.key" :label="c.label" :prop="c.key" width="125" align="right" />
      <el-table-column label="记录更新时间" prop="modifyTime" width="165" />
      <el-table-column label="操作" width="100" :fixed="mobile ? false : 'right'"><template slot-scope="s"><el-button type="text" @click="open(s.row)">查看详情</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" :page-sizes="[10,20,50,100]" @pagination="searchPage" />
    <p class="muted">普通配额可能由多个渠道共享同一仓库库存，渠道配额合计不等同于实物库存。</p>

    <el-drawer title="渠道库存详情" :visible.sync="drawer" :size="mobile ? '100%' : 'min(1240px, 92vw)'" append-to-body custom-class="channel-inventory-drawer" @close="closeDetail">
      <div v-loading="detailLoading" class="inventory-detail">
        <div v-if="detail" class="inventory-toolbar"><div><h2>{{ channelName(detail.channelId) }}</h2><span>{{ goodsName(detail.skuSn) }} <span class="muted">· {{ detail.skuSn }}</span></span></div><el-button size="small" icon="el-icon-refresh" @click="refreshDetail">刷新详情</el-button></div>
        <el-alert v-if="detailError" title="详情加载失败或无权访问，请刷新重试" type="error" :closable="false" show-icon />
        <template v-else-if="detail && detail.reservationSummary">
          <div class="quantity-cards"><div v-for="c in quantities" :key="c.key"><span class="muted">{{ c.label }}</span><strong>{{ detail[c.key] }}</strong></div></div>
          <el-alert :title="detail.checkMessage" :type="detail.checkStatus === 'CONSISTENT' ? 'success' : 'warning'" :closable="false" show-icon />
          <p class="muted">可追溯剩余锁库 {{ detail.reservationSummary.trackedLocked }}，其中订单占用 {{ detail.reservationSummary.occupiedQuantity }}，未占用余额 {{ detail.reservationSummary.releasableQuantity }}。渠道锁库 − 可追溯余额：{{ detail.reservationSummary.difference }}<span v-if="Number(detail.reservationSummary.unknownSources)">；{{ detail.reservationSummary.unknownSources }} 条来源数据异常</span>。订单占用已包含在剩余锁库中。</p>
          <el-tabs v-model="tab" @tab-click="changeTab"><el-tab-pane label="锁库来源 / 释放" name="sources" /><el-tab-pane label="分货执行记录" name="executions" /><el-tab-pane label="订单批次明细" name="orders" /><el-tab-pane label="订单操作记录" name="events" /></el-tabs>
          <el-checkbox v-if="tab === 'orders'" v-model="onlyOccupied" @change="filterOrders">仅看仍占用</el-checkbox>
          <p class="muted">{{ tabNotes[tab] }}</p>
          <div v-if="tabError" class="load-error">记录加载失败 <el-button type="text" @click="loadTab">重新加载</el-button></div>
          <el-table v-if="tab === 'sources'" key="sources" v-loading="tabLoading" :data="tabRows" empty-text="暂无可追溯锁库来源">
            <el-table-column label="分货单" min-width="180"><template slot-scope="s"><div>{{ s.row.ruleName || '原分货单数据异常' }}</div><span class="muted">{{ s.row.ruleCode || s.row.ruleId }}</span></template></el-table-column>
            <el-table-column label="仓库 / 批次" min-width="165"><template slot-scope="s"><div>{{ s.row.storeCode || '仓库数据异常' }}</div><span class="muted">{{ s.row.batchCode || `批次 ID ${s.row.batchId}` }}</span></template></el-table-column>
            <el-table-column v-for="c in sourceQuantities" :key="c.key" :label="c.label" width="100" align="right"><template slot-scope="s">{{ s.row.tracked ? s.row[c.key] : '数据异常' }}</template></el-table-column>
            <el-table-column label="释放状态" width="110"><template slot-scope="s">{{ releaseNames[s.row.releaseStatus] || '数据异常' }}</template></el-table-column>
            <el-table-column label="操作" width="120"><template slot-scope="s"><el-button v-if="s.row.ruleCode" v-hasPermi="['ruleStock:info:list']" type="text" @click="goRule(s.row.ruleId)">查看分货单</el-button><br><el-button v-if="s.row.inventoryId && s.row.matchedBatchId" v-hasPermi="['wmsInventory:inventory:query']" type="text" @click="goBatch(s.row)">查看批次</el-button></template></el-table-column>
          </el-table>
          <el-table v-else-if="tab === 'executions'" key="executions" v-loading="tabLoading" :data="tabRows" empty-text="暂无明确关联当前渠道的执行快照">
            <el-table-column label="记录更新时间" prop="recordTime" width="165" /><el-table-column label="分货单" min-width="180"><template slot-scope="s"><div>{{ s.row.ruleName }}</div><span class="muted">{{ s.row.ruleCode }} · {{ s.row.executionType === 'DAILY' ? `日常轮次 ${s.row.runId}` : '一次性分货' }}</span></template></el-table-column>
            <el-table-column label="数量口径" width="100"><template slot-scope="s">{{ Number(s.row.allocationType) === 2 ? '锁库量' : '可售配额' }}</template></el-table-column>
            <el-table-column label="目标配额" width="105" align="right"><template slot-scope="s">{{ s.row.channel.target }}</template></el-table-column>
            <el-table-column label="执行前 → 后" min-width="135"><template slot-scope="s">{{ s.row.channel.before }} → {{ s.row.channel.after }}</template></el-table-column>
            <el-table-column label="变化" width="95" align="right"><template slot-scope="s">{{ s.row.channel.change > 0 ? '+' : '' }}{{ s.row.channel.change }}</template></el-table-column>
            <el-table-column label="说明 / 操作人" min-width="200"><template slot-scope="s"><div>{{ s.row.channel.warning || '—' }}</div><span class="muted">{{ s.row.operatorName }}</span></template></el-table-column>
            <el-table-column label="操作" width="105"><template slot-scope="s"><el-button v-hasPermi="['ruleStock:info:list']" type="text" @click="goRule(s.row.ruleId)">查看分货单</el-button></template></el-table-column>
          </el-table>
          <el-table v-else-if="tab === 'orders'" key="orders" v-loading="tabLoading" :data="tabRows" empty-text="暂无订单批次记录">
            <el-table-column label="订单行" prop="orderLine" min-width="200" /><el-table-column label="分货单" prop="ruleCode" min-width="160" /><el-table-column label="仓库 / 批次" min-width="160"><template slot-scope="s">{{ s.row.storeCode || '数据异常' }}<div class="muted">{{ s.row.batchCode || s.row.batchId }}</div></template></el-table-column>
            <el-table-column v-for="c in orderQuantities" :key="c.key" :label="c.label" :prop="c.key" width="100" align="right" />
            <el-table-column label="操作" width="105"><template slot-scope="s"><el-button v-if="s.row.ruleCode" v-hasPermi="['ruleStock:info:list']" type="text" @click="goRule(s.row.ruleId)">查看分货单</el-button></template></el-table-column>
          </el-table>
          <el-table v-else key="events" v-loading="tabLoading" :data="tabRows" empty-text="暂无订单操作记录">
            <el-table-column label="时间" prop="createTime" width="165" /><el-table-column label="订单行" prop="orderLine" min-width="180" /><el-table-column label="操作" width="110"><template slot-scope="s">{{ actionNames[s.row.action] || s.row.action }}</template></el-table-column><el-table-column label="本次数量" prop="quantity" width="105" align="right" /><el-table-column label="分货单" prop="ruleCode" min-width="160" /><el-table-column label="请求编号" prop="requestId" min-width="200" /><el-table-column label="操作人" prop="operatorName" width="110" />
          </el-table>
          <pagination v-show="tabTotal > 0" :total="tabTotal" :page.sync="tabQuery.pageNum" :limit.sync="tabQuery.pageSize" :page-sizes="[10,20,50,100]" @pagination="loadTab" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { saveAs } from 'file-saver'
import { channelInventoryList, channelInventoryDetail, channelInventoryRows, channelInventoryOptions } from '@/api/wmsInventory/channel'
import { lookupGoods } from '@/api/wmsInventory/workspace'
const defaults = () => ({ channelId: undefined, skuSn: '', onlyStock: false, pageNum: 1, pageSize: 20 })
export default {
  name: 'ChannelInventory',
  data() {
    return {
      query: defaults(), rows: [], total: 0, loading: false, listError: false, channelOptions: [], goodsOptions: [], channelMap: {}, goodsMap: {}, channelsSearching: false, goodsSearching: false,
      drawer: false, detail: null, detailLoading: false, detailError: false, tab: 'sources', tabRows: [], tabTotal: 0, tabLoading: false, tabError: false, tabQuery: { pageNum: 1, pageSize: 20 }, onlyOccupied: false,
      quantities: [{ key: 'availableStock', label: '普通可售配额' }, { key: 'allocatedStock', label: '剩余锁库量' }, { key: 'reservedStock', label: '预占（原字段）' }, { key: 'frozenStock', label: '冻结（原字段）' }],
      sourceQuantities: [{ key: 'originalQuantity', label: '原锁库' }, { key: 'remainingQuantity', label: '剩余锁库' }, { key: 'occupiedQuantity', label: '其中订单占用' }, { key: 'releasableQuantity', label: '未占用余额' }, { key: 'consumedQuantity', label: '已出库' }, { key: 'releasedQuantity', label: '已释放' }],
      orderQuantities: [{ key: 'originalQuantity', label: '累计占用' }, { key: 'occupiedQuantity', label: '仍占用' }, { key: 'consumedQuantity', label: '已出库' }, { key: 'cancelledQuantity', label: '已取消' }],
      releaseNames: { NONE: '未释放', PENDING: '释放中', PARTIAL: '部分释放', RELEASED: '释放完成', FAILED: '释放失败' }, actionNames: { OCCUPY: '订单占用', CANCEL: '取消占用', CONSUME: '订单出库' },
      tabNotes: { sources: '仅展示当前渠道、当前商品的批次锁库来源。释放请进入原分货单。来源余额须与锁库数量一致。', executions: '展示明确包含当前渠道的执行快照，包含日常分货轮次。快照是当次执行结果，不能当作当前库存或完整库存流水。', orders: '同一订单行可能分布在多个批次，每行仅表示该批次上的数量。', events: '展示当前渠道的订单占用、取消和出库操作；释放记录请查看锁库来源。' }
    }
  },
  computed: { mobile() { return this.$store.state.app.device === 'mobile' } },
  created() { this.listSeq = 0; this.detailSeq = 0; this.tabSeq = 0; this.goodsSeq = 0; this.channelSeq = 0; this.restoreRoute(); this.searchChannels('') },
  watch: { '$route'(to, from) { if (to.path === '/oms-inventory/channelInventory' && from.path !== to.path) this.restoreRoute() } },
  beforeDestroy() { clearTimeout(this.goodsTimer); clearTimeout(this.channelTimer); this.listSeq++; this.detailSeq++; this.tabSeq++; this.goodsSeq++; this.channelSeq++ },
  methods: {
    restoreRoute() { const q = this.$route.query; this.query = { channelId: /^\d+$/.test(q.channelId || '') && Number(q.channelId) > 0 ? Number(q.channelId) : undefined, skuSn: String(q.skuSn || '').slice(0, 128), onlyStock: q.onlyStock === 'true', pageNum: Math.max(1, Math.min(100000, Number(q.pageNum) || 1)), pageSize: [10,20,50,100].includes(Number(q.pageSize)) ? Number(q.pageSize) : 20 }; this.loadList(); if (/^\d+$/.test(q.inventoryId || '')) { this.tab = Object.keys(this.tabNotes).includes(q.tab) ? q.tab : 'sources'; this.open({ id: Number(q.inventoryId) }, true) } else { this.drawer = false; this.detailSeq++; this.tabSeq++ } },
    channelName(id) { return this.channelMap[id] || `渠道 ${id}（资料未匹配）` }, goodsName(sku) { return this.goodsMap[sku] || '商品资料未匹配' },
    syncRoute() { if (this.$route.path !== '/oms-inventory/channelInventory') return; const query = { ...this.query }; if (this.drawer && this.detail) { query.inventoryId = this.detail.id; query.tab = this.tab } this.$router.replace({ path: this.$route.path, query }).catch(() => {}) },
    search() { this.query.pageNum = 1; this.searchPage() }, searchPage() { this.syncRoute(); this.loadList() }, reset() { this.query = defaults(); this.searchPage() },
    searchChannels(keyword) { clearTimeout(this.channelTimer); const seq = ++this.channelSeq; this.channelTimer = setTimeout(async() => { this.channelsSearching = true; try { const r = await channelInventoryOptions({ keyword }); if (seq === this.channelSeq) { this.channelOptions = r.data; r.data.forEach(c => this.$set(this.channelMap, c.channelId, c.channelName)) } } catch (_) { if (seq === this.channelSeq) this.channelOptions = [] } finally { if (seq === this.channelSeq) this.channelsSearching = false } }, 300) },
    searchGoods(keyword) { clearTimeout(this.goodsTimer); const seq = ++this.goodsSeq; this.goodsTimer = setTimeout(async() => { this.goodsSearching = true; try { const r = await lookupGoods({ keyword }); if (seq === this.goodsSeq) this.goodsOptions = r.data } catch (_) { if (seq === this.goodsSeq) this.goodsOptions = [] } finally { if (seq === this.goodsSeq) this.goodsSearching = false } }, 300) },
    async loadNames(rows) { const skus = [...new Set(rows.map(r => r.skuSn).filter(s => s && !this.goodsMap[s]))]; const ids = [...new Set(rows.map(r => r.channelId).filter(id => id && !this.channelMap[id]))]; await Promise.all([skus.length ? lookupGoods({ skus: skus.join(',') }).then(r => r.data.forEach(g => this.$set(this.goodsMap, g.skuSn, g.goodsName))).catch(() => {}) : null, ids.length ? channelInventoryOptions({ ids: ids.join(',') }).then(r => { r.data.forEach(c => this.$set(this.channelMap, c.channelId, c.channelName)); if (this.query.channelId && !this.channelOptions.some(c => c.channelId === this.query.channelId)) this.channelOptions = this.channelOptions.concat(r.data.filter(c => c.channelId === this.query.channelId)) }).catch(() => {}) : null]) },
    async loadList() { const seq = ++this.listSeq; this.loading = true; this.listError = false; try { const r = await channelInventoryList(this.query); if (seq !== this.listSeq) return; this.rows = r.rows; this.total = r.total; this.loadNames(r.rows) } catch (_) { if (seq === this.listSeq) { this.rows = []; this.total = 0; this.listError = true } } finally { if (seq === this.listSeq) this.loading = false } },
    open(row, restore = false) { this.detailSeq++; this.tabSeq++; this.detail = row; this.drawer = true; this.detailError = true; if (!restore) this.tab = 'sources'; this.tabQuery = { pageNum: 1, pageSize: 20 }; this.onlyOccupied = false; this.tabRows = []; this.tabTotal = 0; this.syncRoute(); this.refreshDetail() },
    closeDetail() { this.detailSeq++; this.tabSeq++; this.drawer = false; this.syncRoute() },
    async refreshDetail() { if (!this.detail) return; const seq = ++this.detailSeq; this.detailLoading = true; this.detailError = false; this.tabSeq++; this.tabRows = []; this.tabTotal = 0; try { const r = await channelInventoryDetail(this.detail.id); if (seq !== this.detailSeq) return; this.detail = r.data; this.loadNames([r.data]); this.loadTab() } catch (_) { if (seq === this.detailSeq) this.detailError = true } finally { if (seq === this.detailSeq) this.detailLoading = false } },
    changeTab() { this.tabSeq++; this.tabRows = []; this.tabTotal = 0; this.tabQuery.pageNum = 1; this.syncRoute(); this.loadTab() }, filterOrders() { this.tabQuery.pageNum = 1; this.loadTab() },
    async loadTab() { if (!this.detail || this.detailError) return; const seq = ++this.tabSeq; this.tabLoading = true; this.tabError = false; try { const r = await channelInventoryRows(this.detail.id, this.tab, { ...this.tabQuery, onlyOccupied: this.tab === 'orders' ? this.onlyOccupied : undefined }); if (seq !== this.tabSeq) return; this.tabRows = r.rows; this.tabTotal = r.total } catch (_) { if (seq === this.tabSeq) { this.tabRows = []; this.tabTotal = 0; this.tabError = true } } finally { if (seq === this.tabSeq) this.tabLoading = false } },
    goRule(ruleId) { this.$router.push({ path: '/oms-inventory/ruleStock', query: { ruleId, channelReturn: this.$route.fullPath }}) },
    goBatch(row) { this.$router.push({ path: '/oms-inventory/wmsInventory', query: { inventoryId: row.inventoryId, batchId: row.batchId, batchTab: 'sources', storeCode: row.storeCode, skuSn: this.detail.skuSn, channelReturn: this.$route.fullPath }}) },
    exportPage() { const escape = value => { let text = String(value == null ? '' : value); if (/^[=+@\-\t\r\n]/.test(text)) text = "'" + text; return '"' + text.replace(/"/g, '""') + '"' }; const fields = [{ key: 'channelId', label: '渠道ID' }, { key: 'channelName', label: '渠道' }, { key: 'skuSn', label: 'SKU' }, { key: 'goodsName', label: '商品' }, ...this.quantities, { key: 'modifyTime', label: '记录更新时间' }]; const lines = [fields.map(f => escape(f.label)).join(','), ...this.rows.map(row => fields.map(f => escape(f.key === 'channelName' ? this.channelName(row.channelId) : f.key === 'goodsName' ? this.goodsName(row.skuSn) : row[f.key])).join(','))]; saveAs(new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }), '渠道库存-当前页.csv') }
  }
}
</script>

<style scoped>
.channel-inventory { color:#1d1d1f; }.inventory-toolbar { display:flex; align-items:center; justify-content:space-between; gap:16px; margin:8px 0 18px; font-size:13px; }.inventory-toolbar h2 { margin:0 0 8px; font-size:23px; }.muted { color:#7d8797; font-size:12px; line-height:1.7; overflow-wrap:anywhere; }.inventory-detail { padding:0 26px 28px; }.quantity-cards { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin:20px 0; }.quantity-cards > div { border-radius:12px; background:#f6f8fb; padding:16px; }.quantity-cards strong { display:block; margin-top:10px; font-size:24px; font-variant-numeric:tabular-nums; }.load-error { padding:12px 0; color:#b44b40; }.channel-inventory ::v-deep .el-alert { margin-bottom:12px; }
@media(max-width:767px) { .inventory-toolbar { flex-wrap:wrap; }.inventory-detail { padding:0 14px 24px; }.quantity-cards { grid-template-columns:repeat(2,1fr); }.quantity-cards strong { font-size:20px; }.channel-inventory ::v-deep .el-select { width:100%; } }
</style>
<style>
.channel-inventory-drawer .el-drawer__body { overflow-y:auto; }.channel-inventory-drawer .el-drawer__header { margin-bottom:20px; color:#687385; }
</style>
