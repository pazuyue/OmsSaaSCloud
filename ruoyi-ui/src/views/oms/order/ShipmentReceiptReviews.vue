<template>
  <section class="receipt-reviews">
    <div class="heading"><h3>仓库回传核对 <el-tag v-if="pending" type="warning" size="small">{{ pending }} 条待核对</el-tag></h3><el-button size="small" :loading="loading" @click="load">刷新核对进度</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="!loading && !error && !reviews.length" class="caption">暂无需要人工核对的回传。已接纳回传的记账进度见下方出库记录。</p>
    <article v-for="row in reviews" :key="row.id" class="review-card">
      <div class="review-title"><el-tag :type="row.state === 'WAIT_REVIEW' ? 'warning' : row.state === 'REJECTED' ? 'info' : 'success'">{{ states[row.state] }}</el-tag><el-tag :type="row.stock_state === 'POSTED' ? 'success' : 'warning'">{{ stocks[row.stock_state] }}</el-tag><span>{{ row.create_time }} · 消息 {{ row.message_id }}</span></div>
      <p>{{ row.conflict_message }}</p>
      <el-alert v-if="row.state === 'WAIT_REVIEW'" title="本次回传已拒绝，尚未扣账。核对实际出库事实后，确认处理才会提交原回传记账。" type="warning" :closable="false" show-icon />
      <el-alert v-else-if="row.stock_state === 'UNKNOWN'" title="扣账结果待确认，可能已扣库存。请按原请求核对进度。" type="warning" :closable="false" show-icon />
      <h4>本次回传</h4>
      <el-table :data="row.incoming_packages" size="small"><el-table-column label="包裹编码" prop="package_code" min-width="200" /><el-table-column label="快递 / 运单" min-width="210"><template slot-scope="s">{{ s.row.logistics_code }} / {{ s.row.express_code }}</template></el-table-column><el-table-column label="商品及数量" min-width="190"><template slot-scope="s"><div v-for="(item, index) in s.row.items" :key="index">{{ item.sku }} × {{ item.quantity }} 件</div></template></el-table-column></el-table>
      <h4>已接收的关联记录</h4>
      <el-table :data="row.existing_packages" size="small" empty-text="本次报文内存在多个包裹共用运单，尚无已接收记录"><el-table-column label="发货单 / 包裹" min-width="240"><template slot-scope="s">{{ s.row.shipment_sn }}<div class="caption">{{ s.row.package_code }}</div></template></el-table-column><el-table-column label="快递 / 运单" min-width="210"><template slot-scope="s">{{ s.row.logistics_code }} / {{ s.row.express_code }}</template></el-table-column><el-table-column label="已接收商品及数量" min-width="180"><template slot-scope="s"><div v-for="(line, index) in s.row.lines" :key="index">{{ line.sku_sn }} × {{ line.quantity }} 件</div></template></el-table-column></el-table>
      <template v-if="row.state === 'WAIT_REVIEW'">
        <el-alert v-if="!row.can_confirm_shared" title="该冲突涉及已接收包裹内容变化，不能按共用运单直接接纳或覆盖。可填写核对结论后拒绝本次回传。" type="info" :closable="false" />
        <div v-hasPermi="['order:shipment:receiptReview']" class="decision">
          <el-input v-model="reasons[row.id]" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="填写核对依据和处理原因（必填）" :disabled="!!busy" />
          <el-checkbox v-if="row.can_confirm_shared" v-model="confirmed[row.id]" :disabled="!!busy">已核实为同一业务订单实际出库，多个包裹共用此运单</el-checkbox>
          <div class="actions"><el-button v-if="row.can_confirm_shared" type="primary" size="small" :disabled="!!busy || !confirmed[row.id] || !reason(row.id)" @click="decide(row, 'CONFIRM_SHARED')">确认共用运单并补记出库</el-button><el-button size="small" :disabled="!!busy || !reason(row.id)" @click="decide(row, 'REJECT')">拒绝本次回传</el-button></div>
        </div>
      </template>
      <p v-else class="caption">处理人 {{ row.operator_name }} · {{ row.decided_at }} · {{ row.decision === 'CONFIRM_SHARED' ? '确认共用运单' : '拒绝本次回传' }}：{{ row.reason }}</p>
      <p v-if="row.post_error" class="failure">记账提示：{{ row.post_error }}；请使用下方“核对出库回传”按原请求处理。</p>
    </article>
  </section>
</template>
<script>
import { receiptReviews, decideReceiptReview } from '@/api/order'
export default {
  props: { shipmentId: { type: Number, required: true } },
  data() { return { loading: false, busy: '', error: '', reviews: [], reasons: {}, confirmed: {}, sequence: 0, states: { WAIT_REVIEW: '待人工核对', CONFIRMED: '已确认处理', REJECTED: '已人工拒绝' }, stocks: { NOT_POSTED: '本次未扣账', POSTED: '本次已扣账', UNKNOWN: '扣账结果待确认' } } },
  computed: { pending() { return this.reviews.filter(row => row.state === 'WAIT_REVIEW').length } },
  watch: { shipmentId: { immediate: true, handler() { this.reviews = []; this.reasons = {}; this.confirmed = {}; this.load() } } },
  beforeDestroy() { this.sequence++ },
  methods: {
    reason(id) { return (this.reasons[id] || '').trim() },
    async load() { const seq = ++this.sequence; this.loading = true; this.error = ''; try { const r = await receiptReviews(this.shipmentId); if (seq === this.sequence) { this.reviews = r.data.reviews || []; this.$emit('loaded', this.pending) } } catch (_) { if (seq === this.sequence) this.error = '仓库回传核对信息读取失败，请刷新重试。' } finally { if (seq === this.sequence) this.loading = false } },
    async decide(row, decision) { if (this.busy || !this.reason(row.id)) return; this.busy = String(row.id); this.error = ''; try { const r = await decideReceiptReview(this.shipmentId, row.id, { decision, reason: this.reason(row.id), control_version: row.control_version }); this.reviews = r.data.reviews; this.$message.success(decision === 'REJECT' ? '已记录拒绝结论，本次未提交扣账' : '核对结论已记录，原回传已提交出库记账，请查看扣账结果'); this.$emit('changed') } catch (_) { this.error = '处理未确认，请先刷新核对进度；系统会按原回传确认结果。' } finally { this.busy = ''; await this.load() } }
  }
}
</script>
<style scoped>.receipt-reviews{margin:22px 0}.heading,.review-title,.actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px 12px}.heading{justify-content:space-between}.heading h3{font-size:15px}.review-card{border:1px solid #e5d5b5;background:#fffdf8;border-radius:8px;padding:16px;margin:12px 0}.review-title span,.caption{font-size:12px;color:#738198;overflow-wrap:anywhere}.review-card h4{font-size:13px;margin:16px 0 8px}.review-card p{overflow-wrap:anywhere;line-height:1.7}.decision{margin-top:14px}.decision .el-checkbox{margin:12px 0;white-space:normal;line-height:1.7}.actions{margin-top:8px}.failure{color:#b65b39;font-size:13px}</style>
