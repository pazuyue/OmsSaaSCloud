<template>
  <section class="daily-runs">
    <div class="run-toolbar"><div><h3>{{ selected ? '第 ' + selected.id + ' 轮 · 商品结果' : '日常分货执行记录' }}</h3><p>后台按有效期和间隔执行，关闭页面仍会继续。每轮重新计算普通配额，失败项在后续轮次重新参与计算。</p></div><el-button :loading="loading" @click="load">刷新记录</el-button></div>
    <template v-if="selected"><el-button type="text" icon="el-icon-arrow-left" @click="back">返回执行记录</el-button><el-switch v-model="failedOnly" active-text="仅看失败" @change="filter" /><p class="hint">{{ labels[selected.status] }} · 未处理 {{ selected.pending }} 件商品。停用或到期后，已提交结果保留，未处理商品不再执行。</p></template>
    <el-alert v-if="error" title="加载失败，请刷新重试" type="error" :closable="false" />
    <el-table v-if="!selected" key="runs" v-loading="loading" :data="rows" empty-text="暂无执行轮次，启用并进入有效期后自动执行">
      <el-table-column prop="id" label="轮次" width="85" /><el-table-column label="状态" width="110"><template slot-scope="s">{{ labels[s.row.status] || s.row.status }}</template></el-table-column>
      <el-table-column prop="createTime" label="开始时间" min-width="170" /><el-table-column prop="finishTime" label="结束时间" min-width="170" />
      <el-table-column v-for="c in columns" :key="c.key" :prop="c.key" :label="c.label" width="90" />
      <el-table-column prop="operatorName" label="触发人" min-width="110" /><el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" @click="open(s.row)">查看结果</el-button></template></el-table-column>
    </el-table>
    <el-table v-else key="items" v-loading="loading" :data="rows" empty-text="暂无符合条件的商品">
      <el-table-column type="expand"><template slot-scope="s"><allocation-lines :rows="s.row.detail ? s.row.detail.channels : []" :locking="false" /></template></el-table-column>
      <el-table-column prop="skuSn" label="SKU" min-width="170" /><el-table-column label="结果" width="100"><template slot-scope="s">{{ itemLabels[s.row.status] }}</template></el-table-column>
      <el-table-column label="可分库存" width="100"><template slot-scope="s">{{ s.row.detail ? s.row.detail.available : '—' }}</template></el-table-column>
      <el-table-column prop="allocatedQuantity" label="本轮配额合计" width="130" /><el-table-column label="说明" min-width="250"><template slot-scope="s">{{ s.row.errorMessage || (s.row.status === 'SUCCESS' ? '展开查看渠道变化和规则优先级处理结果' : '尚未处理') }}</template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" :page-sizes="[10,20,50,100]" @pagination="load" />
  </section>
</template>
<script>
import { dailyRuns, dailyItems, dailyRun } from '@/api/ruleStock/workspace'
import AllocationLines from './AllocationLines'
export default {
  name: 'DailyRuns', components: { AllocationLines }, props: { ruleId: { type: Number, required: true }},
  data() { return { rows: [], total: 0, loading: false, error: false, selected: null, failedOnly: false, query: { pageNum: 1, pageSize: 20 }, labels: { PREPARING: '准备商品', RUNNING: '执行中', SUCCESS: '已完成', PARTIAL: '部分成功', FAILED: '执行失败', STOPPED: '已停用', EXPIRED: '已到期' }, itemLabels: { PENDING: '未处理', SUCCESS: '成功', FAILED: '失败' }, columns: [{ key: 'total', label: '商品数' }, { key: 'success', label: '成功' }, { key: 'failed', label: '失败' }, { key: 'pending', label: '未处理' }] } },
  created() { this.sequence = 0; this.load() }, beforeDestroy() { this.sequence++ },
  methods: {
    open(row) { this.selected = row; this.failedOnly = false; this.query.pageNum = 1; this.rows = []; this.load() },
    back() { this.selected = null; this.query.pageNum = 1; this.rows = []; this.load() },
    filter() { this.query.pageNum = 1; this.load() },
    async load() { const seq = ++this.sequence; this.loading = true; this.error = false; try { let r; if (this.selected) { const values = await Promise.all([dailyItems(this.ruleId, this.selected.id, { ...this.query, status: this.failedOnly ? 'FAILED' : '' }), dailyRun(this.ruleId, this.selected.id)]); if (seq !== this.sequence) return; r = values[0]; this.selected = values[1].data } else { r = await dailyRuns(this.ruleId, this.query) } if (seq !== this.sequence) return; this.rows = r.rows; this.total = r.total } catch (_) { if (seq === this.sequence) { this.error = true; this.rows = []; this.total = 0 } } finally { if (seq === this.sequence) this.loading = false } }
  }
}
</script>
<style scoped>
.run-toolbar { display:flex; justify-content:space-between; align-items:center; gap:16px; }.run-toolbar h3 { font-size:17px; }.run-toolbar p,.hint { color:#7a7e87; font-size:13px; line-height:1.8; }.run-toolbar .el-button { flex-shrink:0; }.el-switch { margin-left:20px; } @media(max-width:768px) { .run-toolbar { flex-wrap:wrap; } }
</style>
