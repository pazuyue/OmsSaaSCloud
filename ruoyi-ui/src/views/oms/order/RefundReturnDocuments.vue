<template>
  <section class="return-documents">
    <div class="section-heading"><h3>退货入库单 <span class="hint">{{ documents.length }} 张</span></h3><span class="hint">THK为退货入库单；仓库接单、实收和库存入账分别跟踪。</span></div>
    <el-empty v-if="!documents.length" description="尚未生成退货入库单，可在商品明细中核对待下发安排。" :image-size="45" />
    <article v-for="doc in documents" :key="doc.return_sn" class="return-document" :class="{'previous-document': !doc.current}">
      <div class="section-heading">
        <div class="document-number"><router-link :to="ticketLink(doc)">{{ doc.return_sn }}</router-link><el-button v-clipboard:copy="doc.return_sn" v-clipboard:success="copied" size="mini" type="text">复制</el-button><el-tag size="mini" effect="plain">{{ doc.shared ? '包裹共用' : '独立入库' }}</el-tag><el-tag v-if="!doc.current" size="mini" type="info">此前安排</el-tag></div>
        <el-button v-if="doc.shared" size="small" @click="$emit('parcel',doc.parcel)">查看整单与处理实收</el-button>
        <el-button v-else size="small" @click="$emit('source',doc.plan)">查看商品来源</el-button>
      </div>
      <p class="warehouse">接收仓：{{ warehouseNames[doc.store_code || (doc.parcel || {}).store_code] || doc.store_code || (doc.parcel || {}).store_code || '待确认' }} <span v-if="warehouseNames[doc.store_code || (doc.parcel || {}).store_code]" class="hint">（{{ doc.store_code || doc.parcel.store_code }}）</span></p>
      <div class="document-stages"><span>下发 <b>{{ dispatchText(doc) }}</b></span><span>实收 <b>{{ receiveText(totals(doc)) }}</b></span><span>入账 <b>{{ postingText(totals(doc)) }}</b></span></div>
      <div class="scope-line"><b>本退款</b><span>本单安排 {{ quantityText(doc.ownRows) }}</span><span>已确认实收 {{ quantityText(doc.ownRows,'accepted_quantity') }}</span><span>已入库 {{ quantityText(doc.ownRows,'posted_quantity') }}</span></div>
      <p v-if="doc.shared" class="hint">整张单据：{{ quantityText(doc.rows) }} · 关联 {{ refundCount(doc) }} 张退款 · {{ doc.parcel.carrier_code }} {{ doc.parcel.waybill }}</p>
      <p v-if="doc.last_error" class="warning">{{ doc.last_error }}</p>
      <div v-if="!doc.shared" class="document-actions">
        <el-button v-if="canDispatch(doc)" v-hasPermi="permissions(doc)" size="small" type="primary" @click="$emit('command',doc.plan,doc.document,'dispatch')">下发至仓库</el-button>
        <el-button v-if="doc.dispatch_state==='FAILED'&&doc.cancel_state==='NONE'&&editable(doc)" v-hasPermi="permissions(doc)" :disabled="blocked(doc)" size="small" @click="$emit('command',doc.plan,doc.document,'retry')">重试下发</el-button>
        <el-button v-if="Number(doc.accepted_quantity)>Number(doc.posted_quantity)" v-hasPermi="permissions(doc)" size="small" type="primary" @click="$emit('command',doc.plan,doc.document,'posting')">重试实收入账</el-button>
        <el-button v-hasPermi="permissions(doc)" size="small" @click="$emit('command',doc.plan,doc.document,'query')">查询仓库进度</el-button>
        <el-popover placement="bottom" trigger="click" width="250"><div class="more-actions"><router-link :to="{path:'/oms-supplychain/wmsLogs',query:{numberType:'DOCUMENT',number:doc.return_sn,allTime:'true'}}">仓库交互日志</router-link><el-button v-if="editable(doc)&&doc.cancel_state!=='CONFIRMED'&&Number(doc.accepted_quantity)<Number(doc.quantity)" v-hasPermi="permissions(doc)" type="text" @click="$emit('action','cancel',doc.plan,doc.document)">截停本单剩余收货</el-button><el-button v-if="doc.current&&doc.cancel_state==='CONFIRMED'&&remaining(doc.plan)>0&&doc.plan.state!=='CLOSED'" v-hasPermi="permissions(doc)" type="text" :disabled="blocked(doc)" @click="$emit('action','rearrange',doc.plan,doc.document)">重新安排剩余退回</el-button></div><el-button slot="reference" size="small">更多</el-button></el-popover>
      </div>
      <el-collapse class="trace"><el-collapse-item title="仓库单号与执行信息" name="trace"><p>仓库单号 {{ doc.external_order || '尚未返回' }} · 仓库状态 {{ doc.warehouse_status || '尚未返回' }}</p><p v-if="doc.create_time || doc.update_time">创建 {{ doc.create_time || '—' }} · 最近更新 {{ doc.update_time || '—' }}</p><p v-if="doc.cancel_state==='CONFIRMED'">本次剩余收货已截停，已确认实收和入库保留；本单数量不与重排后的计划相加。</p></el-collapse-item></el-collapse>
    </article>
  </section>
</template>
<script>
import { quantityText, dispatchText, receiveText, postingText } from './refundReturnPresentation'
import { remaining, pendingReviews } from './refundGoodsPresentation'
export default {
  name: 'RefundReturnDocuments',
  props: { documents: { type: Array, default: () => [] }, warehouseNames: { type: Object, default: () => ({}) }, refund: { type: Object, required: true }},
  methods: {
    quantityText, dispatchText, receiveText, postingText, remaining,
    copied() { this.$message.success('单号已复制') },
    ticketLink(doc) { return { path: '/oms-supplychain/wmsTickets', query: { sn: doc.return_sn, documentId: doc.ticket_id }} },
    totals(doc) { return { ...doc, quantity: doc.rows.reduce((n, r) => n + Number(r.quantity || 0), 0), accepted_quantity: doc.rows.reduce((n, r) => n + Number(r.accepted_quantity || 0), 0), posted_quantity: doc.rows.reduce((n, r) => n + Number(r.posted_quantity || 0), 0) } },
    refundCount(doc) { return new Set(doc.rows.map(row => String(row.refund_id))).size },
    permissions(doc) { return doc.plan.gift_plan_id ? ['order:gift:execute', 'order:refund:goods'] : ['order:refund:goods'] },
    editable(doc) { return doc.plan.gift_plan_id || this.refund.goods_state !== 'DONE' },
    blocked(doc) { const p = doc.plan; return pendingReviews(p).length > 0 || p.parcel_state === 'NEED_CHECK' || ['WAIT_BUYER', 'WAREHOUSE_CHECK'].includes(p.parcel_resolution) },
    canDispatch(doc) { return doc.dispatch_state === 'PENDING' && doc.cancel_state === 'NONE' && this.editable(doc) && !this.blocked(doc) }
  }
}
</script>
<style scoped>
.return-documents{margin:24px 0}.section-heading,.document-number,.document-stages,.scope-line,.document-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.section-heading{justify-content:space-between}.section-heading h3{font-size:15px;margin:0}.return-document{border:1px solid #e2e8f0;border-radius:8px;padding:16px;margin:12px 0}.document-number{min-width:0}.document-number a{font-size:13px;overflow-wrap:anywhere}.warehouse{font-size:13px;margin:12px 0}.document-stages{font-size:12px;color:#758397;margin:12px 0;gap:24px}.document-stages b{font-weight:500;color:#354960;margin-left:8px}.scope-line{padding:12px;background:#f5f8fc;border-radius:6px;font-size:13px}.scope-line>b{color:#354960}.hint{color:#758397;font-size:12px;line-height:1.8}.previous-document{background:#fafbfc}.warning{color:#a46808}.document-actions{margin-top:12px}.document-actions .el-button{margin:0}.more-actions{display:flex;flex-direction:column;gap:12px;font-size:13px}.more-actions .el-button{margin:0;align-self:flex-start}.trace{margin-top:12px}.trace p{font-size:12px;line-height:1.8}a{color:#1677ff}
</style>
