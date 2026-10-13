<template>
  <el-drawer title="补拉任务结果" :visible.sync="visible" size="min(1180px, 96vw)" append-to-body @close="stop">
    <div class="task-body">
      <div class="toolbar"><el-select v-model="taskId" size="small" @change="changeTask"><el-option v-for="id in taskIds" :key="id" :value="id" :label="'任务 ' + id" /></el-select><el-button size="small" :loading="loading" @click="load">刷新</el-button></div>
      <el-alert v-if="error" title="任务结果加载失败，请刷新重试" type="error" :closable="false" />
      <template v-if="task">
        <el-descriptions border :column="2" size="small"><el-descriptions-item label="店铺">{{ task.channel_name }}</el-descriptions-item><el-descriptions-item label="状态"><el-tag size="small" :type="task.status === 'SUCCESS' ? 'success' : ['PARTIAL','FAILED'].includes(task.status) ? 'warning' : ''">{{ taskStates[task.status] || task.status }}</el-tag></el-descriptions-item><el-descriptions-item label="补拉方式">{{ task.pull_mode === 'TID' ? '按平台单号 · ' + requestedCount + ' 个' : '按时间 · 后台按天分页' }}</el-descriptions-item><el-descriptions-item label="操作人">{{ task.operator_name }}</el-descriptions-item><el-descriptions-item v-if="task.pull_mode !== 'TID'" label="实际范围" :span="2">{{ task.start_time }} 至 {{ task.end_time }}（不含）<div class="muted">已读取至 {{ task.page_time }}</div></el-descriptions-item></el-descriptions>
        <el-alert v-if="task.error_message" :title="task.error_message" type="warning" show-icon :closable="false" />
        <p class="muted">补拉结果表示源库查询和平台数据保存结果；转单状态单独展示。源库未找到不代表平台订单不存在。</p>
        <div class="toolbar"><el-select v-model="result" clearable size="small" placeholder="全部补拉结果" @change="search"><el-option v-for="(label, code) in results" :key="code" :label="label + '（' + (counts[code] || 0) + '）'" :value="code" /></el-select><el-button v-if="counts.FAILED" v-hasPermi="['order:pull:execute']" size="small" type="primary" :loading="retrying" :disabled="['PENDING','RUNNING'].includes(task.status) || (!selected.length && counts.FAILED > 100)" @click="retryFailures">{{ selected.length ? '重试选中失败项（' + selected.length + '）' : '重试失败项（' + counts.FAILED + '）' }}</el-button><el-button v-if="task.status === 'FAILED' && task.pull_mode !== 'TID'" v-hasPermi="['order:pull:execute']" size="small" :loading="retrying" @click="resume">继续未完成范围</el-button><el-button v-if="rows.some(r => r.result === 'FAILED')" size="small" @click="copyFailures">复制当前页失败单号</el-button></div>
        <p v-if="counts.FAILED > 100" class="muted">每次最多重试100条，请筛选失败结果后勾选要重试的明细。</p>
        <el-table ref="itemsTable" v-loading="loading" :data="rows" row-key="id" @selection-change="selected=$event" :empty-text="['SUCCESS','PARTIAL','FAILED'].includes(task.status) ? '暂无逐单记录' : '暂未生成逐单结果，任务会在后台继续执行'">
          <el-table-column type="selection" width="45" :selectable="row => row.result === 'FAILED'" />
          <el-table-column label="平台单号 TID" prop="platform_tid" min-width="200" />
          <el-table-column label="补拉结果" min-width="165"><template slot-scope="s"><el-tag size="small" :type="s.row.result === 'FAILED' ? 'danger' : s.row.result === 'NOT_FOUND' || s.row.result === 'CHANGE_PENDING' ? 'warning' : s.row.result === 'PENDING' ? 'info' : 'success'">{{ results[s.row.result] || s.row.result }}</el-tag></template></el-table-column>
          <el-table-column label="说明" prop="message" min-width="250" show-overflow-tooltip />
          <el-table-column label="当前转单状态" min-width="150"><template slot-scope="s">{{ s.row.change_pending ? '待处理平台变更' : conversion[s.row.conversion_status] || '暂无平台订单' }}<div v-if="s.row.conversion_error" class="muted">{{ s.row.conversion_error }}</div></template></el-table-column>
          <el-table-column label="操作" width="95"><template slot-scope="s"><el-button v-if="s.row.platform_order_id" v-hasPermi="['order:order:query']" type="text" @click="openOrder(s.row)">订单详情</el-button></template></el-table-column>
        </el-table>
        <pagination v-show="total" :total="total" :page.sync="page" :limit.sync="size" @pagination="load" />
      </template>
    </div>
  </el-drawer>
</template>
<script>
import { pullTaskDetail, pullTaskItems, retryPullItems, retryPullTask } from '@/api/order'
export default {
  data() { return { visible: false, taskIds: [], taskId: null, task: null, rows: [], total: 0, page: 1, size: 20, result: '', loading: false, retrying: false, error: false, selected: [], sequence: 0, timer: null, results: { PENDING: '等待查询', NEW: '新增成功', SKIPPED: '已存在跳过', UPDATED: '平台数据已更新', CHANGE_PENDING: '待处理变更', NOT_FOUND: '源库未找到', FAILED: '补拉失败' }, taskStates: { PENDING: '等待执行', RUNNING: '执行中', SUCCESS: '已完成', PARTIAL: '部分失败', FAILED: '执行失败' }, conversion: { PENDING: '待转单处理', PROCESSING: '转单处理中', SUCCESS: '已转业务订单', WAITING: '等待转单', EXCEPTION: '转单异常' } } },
  computed: { counts() { return Object.fromEntries((this.task && this.task.results || []).map(r => [r.result, Number(r.count)])) }, requestedCount() { try { return JSON.parse(this.task.requested_tids || '[]').length } catch (_) { return 0 } } },
  beforeDestroy() { this.stop() },
  methods: {
    open(ids) { this.stop(); this.taskIds = ids; this.taskId = ids[0]; this.visible = true; this.changeTask() },
    stop() { clearTimeout(this.timer); this.sequence++ },
    changeTask() { this.stop(); this.page = 1; this.result = ''; this.task = null; this.rows = []; this.load() },
    search() { this.page = 1; this.load() },
    async load() { clearTimeout(this.timer); if (!this.visible || !this.taskId) return; const sequence = ++this.sequence; this.loading = true; this.error = false; try { const [task, items] = await Promise.all([pullTaskDetail(this.taskId), pullTaskItems(this.taskId, { result: this.result, pageNum: this.page, pageSize: this.size })]); if (sequence !== this.sequence) return; const selection = new Set(this.selected.map(r => r.id)); this.task = task.data; this.rows = items.rows; this.total = items.total; this.selected = []; this.$nextTick(() => { if (this.$refs.itemsTable) this.rows.filter(r => r.result === 'FAILED' && selection.has(r.id)).forEach(r => this.$refs.itemsTable.toggleRowSelection(r, true)) }); if (['PENDING', 'RUNNING'].includes(this.task.status) || this.rows.some(r => !r.change_pending && ['PENDING', 'PROCESSING'].includes(r.conversion_status))) this.timer = setTimeout(() => this.load(), 5000) } catch (_) { if (sequence === this.sequence) this.error = true } finally { if (sequence === this.sequence) this.loading = false } },
    async retryFailures() { if (this.selected.length > 100) return this.$message.warning('每次最多重试100条'); this.retrying = true; try { const r = await retryPullItems(this.taskId, this.selected.length ? { item_ids: this.selected.map(r => r.id) } : {}); this.taskIds = [...new Set([...this.taskIds, ...r.data.task_ids])]; this.taskId = r.data.task_ids[0]; this.$message.success('失败项已提交为新的单号补拉任务'); this.changeTask() } finally { this.retrying = false } },
    async resume() { this.retrying = true; try { await retryPullTask(this.taskId); this.$message.success('已从未完成范围继续补拉'); this.load() } finally { this.retrying = false } },
    async copyFailures() { const text = this.rows.filter(r => r.result === 'FAILED').map(r => r.platform_tid).join('\n'); try { await navigator.clipboard.writeText(text); this.$message.success('已复制当前页失败单号') } catch (_) { this.$message.error('复制失败，请手工复制单号') } },
    openOrder(row) { this.visible = false; this.$emit('open-order', row.platform_order_id) }
  }
}
</script>
<style scoped>.task-body{padding:0 22px 30px}.toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}.toolbar .el-button{margin:0}.muted{font-size:13px;color:#64748b;line-height:1.7}.task-body .el-alert{margin-top:14px}</style>
