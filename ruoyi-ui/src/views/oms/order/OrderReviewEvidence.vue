<template>
  <section class="review-evidence">
    <div class="heading"><strong>审核依据</strong><el-tag size="small" :type="order.review_status === 'APPROVED' ? 'success' : order.review_decision === 'BLOCKED' ? 'danger' : 'warning'">{{ decisionLabel(order.review_decision) }}</el-tag><span class="muted" v-if="assessment">规则 v{{ assessment.rule_revision }} · {{ order.review_evaluated_at || '—' }}</span></div>
    <p>{{ order.review_reason || '等待审核' }}</p><el-alert v-if="order.review_decision === 'HUMAN_APPROVED' && assessment" title="已由人工确认通过；下方保留当时自动判定命中的条件，后续自动处理不会覆盖人工结论。" type="info" :closable="false" />
    <el-alert v-if="order.review_assessment_error" :title="order.review_assessment_error" type="error" :closable="false" />
    <el-table v-if="assessment" :data="assessment.checks || []" size="mini"><el-table-column label="检查项" prop="title" width="190"/><el-table-column label="结果" width="120"><template slot-scope="s"><el-tag size="mini" :type="s.row.result === 'PASS' ? 'success' : ['HIT', 'BLOCKED'].includes(s.row.result) ? 'warning' : 'info'">{{ checkLabel(s.row) }}</el-tag></template></el-table-column><el-table-column label="判定依据" min-width="300"><template slot-scope="s">{{ s.row.result === 'DISABLED' ? '该条件未启用，不参与判定' : s.row.detail }}</template></el-table-column></el-table>
    <div class="actions"><el-button v-hasPermi="['order:order:query']" size="mini" :loading="previewing" @click="preview">按当前规则试算</el-button><el-button v-if="pending" v-hasPermi="['order:order:review']" size="mini" :loading="saving" @click="recheck">重新判定</el-button><el-button v-if="order.can_review" v-hasPermi="['order:order:review']" size="mini" type="primary" plain :disabled="saving" @click="$emit('approve')">人工审核通过</el-button><el-button v-hasPermi="['order:pull:config']" size="mini" @click="settings">审单规则</el-button></div>
    <el-collapse v-if="order.review_events && order.review_events.length"><el-collapse-item title="查看审核判定与人工处理记录" name="history"><el-table :data="order.review_events" size="mini"><el-table-column label="时间" prop="create_time" width="165"/><el-table-column label="结论" width="160"><template slot-scope="s">{{ decisionLabel(s.row.decision) }}</template></el-table-column><el-table-column label="说明" min-width="250"><template slot-scope="s">{{ s.row.assessment.summary || s.row.assessment.note || '—' }}</template></el-table-column><el-table-column label="操作人" prop="operator_name" width="150"/><el-table-column label="依据" width="110"><template slot-scope="s"><el-button type="text" size="mini" @click="showEvent(s.row)">查看当时依据</el-button></template></el-table-column></el-table></el-collapse-item></el-collapse>
    <el-dialog :title="reportTitle" :visible.sync="reportVisible" width="min(950px, 95vw)" append-to-body><el-alert :title="reportTitle === '当前规则试算' ? '只读试算，不改变审核结果、不占库存、不触发分仓。重新判定会使用执行时的当前规则。' : '这里展示当时保留的审核依据。'" type="info" :closable="false"/><p v-if="report">{{ report.summary || report.note }} · 规则 v{{ report.rule_revision || 0 }}</p><el-table v-if="report" :data="report.checks || []"><el-table-column label="检查项" prop="title" width="190"/><el-table-column label="结果" width="120"><template slot-scope="s">{{ checkLabel(s.row) }}</template></el-table-column><el-table-column label="判定依据" min-width="300"><template slot-scope="s">{{ s.row.result === 'DISABLED' ? '该条件未启用，不参与判定' : s.row.detail }}</template></el-table-column></el-table><span slot="footer"><el-button @click="reportVisible=false">关闭</el-button></span></el-dialog>
  </section>
</template>
<script>
import { previewReview, recheckReview } from '@/api/order'
export default {
  props: { order: { type: Object, required: true } },
  data() { return { saving: false, previewing: false, reportVisible: false, report: null, reportTitle: '当前规则试算' } },
  computed: { assessment() { return this.order.review_assessment }, pending() { return this.order.review_status === 'PENDING' && this.order.status === 'WAIT_ALLOCATION' } },
  methods: {
    decisionLabel(value) { return ({ AUTO_APPROVED: '自动审核通过', HUMAN_APPROVED: '人工审核通过', MANUAL_REQUIRED: '待人工审核', WAIT_STOCK: '待库存满足', BLOCKED: '审核受阻' })[value] || (this.order.review_status === 'APPROVED' ? '已审核' : '待审核') },
    checkLabel(row) { if (row.result === 'PASS') return ['MANUAL_MATCH', 'AMOUNT', 'QUANTITY', 'SKU'].includes(row.code) ? '未命中' : '通过'; return ({ PASS: '通过', HIT: '命中，转人工', WAIT: '等待库存满足', BLOCKED: '阻断', MANUAL: '人工审核', DISABLED: '未启用' })[row.result] || row.result },
    async preview() { if (this.previewing) return; this.previewing = true; try { this.report = (await previewReview(this.order.id)).data; this.reportTitle = '当前规则试算'; this.reportVisible = true } finally { this.previewing = false } },
    async recheck() { if (this.saving) return; this.saving = true; try { await recheckReview(this.order.id, { control_version: this.order.control_version }); this.$message.success('已按当前规则重新判定'); this.$emit('changed') } finally { this.saving = false } },
    showEvent(event) { this.report = event.assessment.original_assessment || event.assessment; this.reportTitle = '当时审核依据'; this.reportVisible = true },
    settings() { this.$router.push({ path: '/oms-order/settings', query: { tab: 'review', channelId: this.order.channel_id } }) }
  }
}
</script>
<style scoped>
.review-evidence{margin:18px 0;padding:16px;border:1px solid #e4eaf2;border-radius:8px}.heading{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.muted{color:#8490a2;font-size:12px}.review-evidence p{font-size:13px;line-height:1.7;color:#526079}.actions{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0}.actions .el-button{margin-left:0}
</style>
