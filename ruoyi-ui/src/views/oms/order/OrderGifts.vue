<template>
  <section v-loading="loading" class="gift-panel" :class="{ 'gift-panel--refund': refundId }">
    <template v-if="refundId">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
      <refund-gift-lines v-if="!loading || lines.length" :lines="lines" :refund-id="refundId" @open-goods="$emit('open-goods', $event)"/>
      <el-collapse v-if="lines.length || quotaNotices.length" v-model="originalPanels" class="order-gift-context"><el-collapse-item :title="'原订单赠品情况 · ' + lines.length + ' 种'" name="original"><template v-if="originalPanels.includes('original')">
        <p class="muted">以下为原订单累计判定，不代表本退款新增应退。安排退回时才关联当前退款，其他退款的安排仍归原退款。</p>
        <el-alert v-if="row.block_reason" :title="row.block_reason" type="info" :closable="false"/>
        <gift-progress-card v-for="line in otherLines" :key="line.id" :line="line" :origin="origins(line.gift_parent_sku || line.sku_sn)" :refunds="row.triggers || []" :saving="saving" @cancel="cancel" @return="startReturn" @waive="startWaive" @refund="goRefund"/>
        <p v-if="!otherLines.length && lines.length" class="muted">赠品均已有本退款关联安排，累计数量可展开上方“判定依据”查看。</p>
      <el-collapse class="evidence"><el-collapse-item title="赠品资格计算依据与处理记录" name="evidence"><el-alert title="采用下单时的策略版本，只核对原赠品权益；不会因售后重新参加其他活动。退差价是否影响金额门槛以原策略配置为准。" :closable="false" type="info"/><el-alert v-for="(notice,i) in quotaNotices" :key="i" :title="notice" type="info" :closable="false"/><el-table :data="row.effective_basis"><el-table-column prop="platform_oid" label="原平台子单" min-width="190"/><el-table-column prop="sku_sn" label="购买 SKU"/><el-table-column prop="quantity" label="售后后有效数量"/><el-table-column prop="payment" label="门槛计算金额"/></el-table><el-table :data="row.events"><el-table-column prop="create_time" label="时间" width="170"/><el-table-column prop="actor" label="操作人" width="130"/><el-table-column prop="message" label="记录" min-width="300"/></el-table></el-collapse-item></el-collapse>
        <el-alert v-for="(notice,i) in !lines.length ? quotaNotices : []" :key="i" :title="notice" type="info" :closable="false"/>
      </template></el-collapse-item></el-collapse>
    </template>
    <template v-else>
    <div class="heading"><div><h3>{{ refundId ? '本退款赠品处理' : '赠品处理' }}</h3><p class="muted">{{ refundId ? '退回安排按本退款展示，赠送资格按原订单累计判定。' : '先处理待办，赠送来源与计算依据按需展开。' }}</p></div><el-button size="small" :loading="loading" @click="load">刷新赠品进度</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <el-alert v-if="row.block_reason" :title="row.block_reason" type="warning" :closable="false" show-icon/>
    <el-empty v-if="!loading && !error && !lines.length" description="本订单没有 OMS 策略赠品" :image-size="65"/>
    <el-collapse v-if="!lines.length && quotaNotices.length"><el-collapse-item title="未赠送原因与额度" name="quota"><el-alert v-for="(notice,i) in quotaNotices" :key="i" :title="notice" type="info" :closable="false"/></el-collapse-item></el-collapse>
    <template v-else-if="lines.length">
      <gift-contents :parents="row.parents || []" :voided="row.order_status==='VOIDED'"/>
      <p v-if="refundId && !currentLines.length" class="scope-note">本退款尚无已关联的赠品退回安排。原订单待办见下方；平台退款成功不代表赠品已处理完成。</p>
      <template v-for="group in displayGroups">
        <section v-if="group.lines.length && !group.collapsed" :key="group.key" class="gift-group"><div class="group-heading"><h4>{{ group.title }}</h4><span class="muted">{{ group.lines.length }} 种</span></div><p v-if="group.description" class="muted">{{ group.description }}</p><gift-progress-card v-for="line in group.lines" :key="line.id" :line="line" :origin="origins(line.gift_parent_sku || line.sku_sn)" :refund-id="refundId" :refunds="row.triggers || []" :saving="saving" @cancel="cancel" @return="startReturn" @waive="startWaive" @refund="goRefund"/></section>
        <el-collapse v-else-if="group.lines.length" :key="group.key"><el-collapse-item :title="group.title + ' · ' + group.lines.length + ' 种'" :name="group.key"><gift-progress-card v-for="line in group.lines" :key="line.id" :line="line" :origin="origins(line.gift_parent_sku || line.sku_sn)" :refund-id="refundId" :refunds="row.triggers || []" :saving="saving" @cancel="cancel" @return="startReturn" @waive="startWaive" @refund="goRefund"/></el-collapse-item></el-collapse>
      </template>
      <el-collapse class="evidence"><el-collapse-item title="赠品资格计算依据与处理记录" name="evidence"><el-alert title="采用下单时的策略版本，只核对原赠品权益；不会因售后重新参加其他活动。退差价是否影响金额门槛以原策略配置为准。" :closable="false" type="info"/><el-alert v-for="(notice,i) in quotaNotices" :key="i" :title="notice" type="info" :closable="false"/><el-table :data="row.effective_basis"><el-table-column prop="platform_oid" label="原平台子单" min-width="190"/><el-table-column prop="sku_sn" label="购买 SKU"/><el-table-column prop="quantity" label="售后后有效数量"/><el-table-column prop="payment" label="门槛计算金额"/></el-table><el-table :data="row.events"><el-table-column prop="create_time" label="时间" width="170"/><el-table-column prop="actor" label="操作人" width="130"/><el-table-column prop="message" label="记录" min-width="300"/></el-table></el-collapse-item></el-collapse>
    </template>
    </template>
    <el-dialog title="安排赠品退回" :visible.sync="returnVisible" width="min(750px, 96vw)" append-to-body :close-on-click-modal="false">
      <el-alert title="按赠品原出库来源创建退回安排。相同运单、地址等满足店铺规则时，可与正品共用退回包裹及 THK；创建安排不增加库存。" type="info" :closable="false" show-icon/>
      <el-form label-width="125px" size="small" style="margin-top:18px"><el-form-item label="关联退款单" required><el-select v-model="form.refund_id" :disabled="!!refundId" style="width:100%" @change="options"><el-option v-for="r in eligibleRefunds" :key="r.id" :value="r.id" :label="r.refund_sn"/></el-select></el-form-item><el-form-item label="原发货明细" required><el-select v-model="form.shipment_line_id" style="width:100%"><el-option v-for="s in selected.sources||[]" :key="s.id" :value="s.id" :label="`${s.shipment_sn} · 明细 #${s.id} · 已发 ${s.shipped_quantity} 件`"/></el-select></el-form-item><el-form-item label="本次退回数量" required><el-input-number v-model="form.quantity" :min="1" :max="Number(selected.unarranged_quantity||1)" :precision="0"/></el-form-item><el-form-item label="退回仓" required><el-select v-model="form.store_code" style="width:100%"><el-option v-for="w in warehouses" :key="w.code" :value="w.code" :label="`${w.name} (${w.code})`"/></el-select></el-form-item><el-form-item label="退回地址" required><el-input v-model="form.return_address" maxlength="500"/></el-form-item><el-form-item label="退回联系人" required><el-input v-model="form.return_contact" maxlength="200"/></el-form-item><el-form-item label="处理依据" required><el-input v-model="form.reason" type="textarea" maxlength="500"/></el-form-item><el-form-item label="协商凭证" required><el-input v-model="form.evidence" type="textarea" maxlength="1000"/></el-form-item><el-form-item><el-checkbox v-model="form.confirmed">已确认赠品退回范围和原发货来源</el-checkbox></el-form-item></el-form>
      <span slot="footer"><el-button @click="returnVisible=false">取消</el-button><el-button type="primary" :disabled="!form.confirmed" :loading="saving" @click="createReturn">创建退回安排</el-button></span>
    </el-dialog>
    <el-dialog title="确认不再追回赠品" :visible.sync="waiveVisible" width="min(600px, 96vw)" append-to-body :close-on-click-modal="false"><el-alert title="确认不再追回不代表实收，不增加库存，也不调整平台退款金额。" type="warning" :closable="false"/><el-form label-width="100px" style="margin-top:18px"><el-form-item label="不再追回数量"><el-input-number v-model="waiver.quantity" :min="1" :max="Number(selected.unarranged_quantity||1)" :precision="0"/></el-form-item><el-form-item label="原因" required><el-input v-model="waiver.reason" type="textarea" maxlength="500"/></el-form-item><el-form-item label="审批凭证" required><el-input v-model="waiver.evidence" type="textarea" maxlength="1000"/></el-form-item><el-checkbox v-model="waiver.confirmed">确认不再追回上述数量</el-checkbox></el-form><span slot="footer"><el-button @click="waiveVisible=false">取消</el-button><el-button type="primary" :disabled="!waiver.confirmed" :loading="saving" @click="waive">确认不再追回</el-button></span></el-dialog>
  </section>
</template>
<script>
import * as api from '@/api/order/gift'
import GiftProgressCard from './GiftProgressCard'
import GiftContents from './GiftContents'
import RefundGiftLines from './RefundGiftLines'
import { giftDisplayGroups, scopedGiftPlans } from './giftPresentation'

export default {
  components: { GiftProgressCard, GiftContents, RefundGiftLines },
  props: { orderId: [Number, String], refundId: [Number, String] },
  data: () => ({ loading: false, saving: false, sequence: 0, error: '', row: {}, originalPanels: [], selected: {}, form: {}, waiver: {}, returnVisible: false, waiveVisible: false, warehouses: [] }),
  computed: {
    lines() { return this.row.lines || [] },
    otherLines() { return this.lines.filter(line => !scopedGiftPlans(line, this.refundId).length) },
    currentLines() { return this.lines.filter(line => scopedGiftPlans(line, this.refundId).length) },
    displayGroups() { return giftDisplayGroups(this.lines, this.refundId) },
    quotaNotices() { return ((this.row.snapshot || {}).trace || []).flatMap(g => (g.rules || []).filter(r => r.requested_count > r.granted_count).map(r => `${g.name} / ${r.name}：${r.reason}`)) },
    eligibleRefunds() { return (this.row.triggers || []).filter(r => r.review_state === 'APPROVED' && (!this.refundId || String(r.id) === String(this.refundId))) }
  },
  watch: { orderId: { immediate: true, handler() { this.row = {}; this.originalPanels = []; this.load() } }, refundId() { this.returnVisible = false; this.waiveVisible = false; this.originalPanels = [] } },
  beforeDestroy() { this.sequence++ },
  methods: {
    async load() {
      if (!this.orderId) return
      const seq = ++this.sequence
      this.loading = true
      this.error = ''
      this.$emit('load-start')
      try { const result = (await api.orderGifts(this.orderId)).data; if (seq === this.sequence) { this.row = result; this.$emit('loaded', result) } }
      catch (_) { if (seq === this.sequence) { this.error = '赠品进度加载失败，请刷新重试'; this.$emit('load-error', this.error) } }
      finally { if (seq === this.sequence) this.loading = false }
    },
    origins(sku) { const parent=(this.row.parents||[]).find(p=>p.sku_sn===sku),snapshot=parent&&parent.bundle_snapshot,unit=snapshot?snapshot.business_type==='LUCKY_BAG'?'袋':'套':'件';return ((this.row.snapshot || {}).entitlements || []).filter(g => g.sku_sn === sku).map(g => `${g.group_name} / ${g.rule_name}：${g.quantity} ${unit}`).join('；') },
    async cancel(line) {
      await this.$confirm(line.needs_cutoff ? '将撤回包含赠品的相关发货单。仓库确认后取消失效赠品，剩余正品需重新分仓。' : '确认取消失效赠品并核对原库存占用？', '处理失效赠品', { type: 'warning' })
      this.saving = true
      try { const result = (await api.cancelGift(this.orderId, { control_version: this.row.control_version, order_line_id: line.id })).data; this.$message.success(result.message || '赠品取消已完成'); await this.load(); this.$emit('changed') }
      finally { this.saving = false }
    },
    async startReturn(line) {
      if (!this.eligibleRefunds.length) return this.$message.warning(this.refundId ? '当前退款尚未审核通过或已关闭，请先核对退款方案' : '请先审核关联退款方案，再安排赠品退回')
      this.selected = line
      this.form = { gift_plan_id: this.row.id, quantity: Number(line.unarranged_quantity), shipment_line_id: (line.sources || [])[0] && line.sources[0].id, refund_id: this.eligibleRefunds[0].id, reason: '售后后不再满足原赠送条件', evidence: '', request_key: `GIFT_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`, confirmed: false }
      this.returnVisible = true
      await this.options()
    },
    async options() {
      const refund = this.form.refund_id
      this.warehouses = []
      if (!refund) return
      const result = await api.giftReturnOptions(refund)
      if (String(this.form.refund_id) === String(refund)) this.warehouses = result.data.warehouses
    },
    async createReturn() {
      if (!this.form.reason || !this.form.evidence || !this.form.store_code || !this.form.return_address || !this.form.return_contact || !this.form.refund_id || !this.form.shipment_line_id) return this.$message.warning('请补全退回来源、地址和协商凭证')
      const refund = this.eligibleRefunds.find(r => String(r.id) === String(this.form.refund_id))
      if (!refund) return this.$message.warning('关联退款状态已变化，请刷新核对')
      this.saving = true
      try { await api.createGiftReturn(this.form.refund_id, { ...this.form, control_version: refund.control_version }); this.returnVisible = false; this.$message.success('赠品退回安排已创建，请在退回与包裹中继续处理'); await this.load(); this.$emit('changed') }
      finally { this.saving = false }
    },
    startWaive(line) { this.selected = line; this.waiver = { order_line_id: line.id, quantity: Number(line.unarranged_quantity), reason: '', evidence: '', confirmed: false }; this.waiveVisible = true },
    async waive() {
      if (!this.waiver.reason || !this.waiver.evidence) return this.$message.warning('请填写原因和审批凭证')
      this.saving = true
      try { await api.waiveGift(this.orderId, { ...this.waiver, control_version: this.row.control_version }); this.waiveVisible = false; this.$message.success('已确认不再追回，库存未增加'); await this.load(); this.$emit('changed') }
      finally { this.saving = false }
    },
    goRefund(id) { if (this.refundId && String(id) === String(this.refundId)) this.$emit('open-goods'); else this.$router.push({ path: '/oms-order/businessRefunds', query: { id } }) }
  }
}
</script>
<style scoped>
.gift-panel--refund{margin:18px 0 10px;min-height:40px}.order-gift-context{margin-top:12px}.order-gift-context ::v-deep .el-collapse-item__header{font-size:12px;color:#758397}

.gift-panel{margin:18px 0}.heading,.group-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.heading h3,.group-heading h4{margin:0}.heading h3{font-size:16px}.group-heading{justify-content:flex-start;margin:20px 0 10px}.muted{color:#758397;font-size:12px;line-height:1.8}.scope-note{padding:12px;background:#f4f7fb;color:#65768c;font-size:13px;line-height:1.8;border-radius:6px}.evidence{margin-top:18px}.evidence .el-alert{margin:12px 0}@media(max-width:650px){.heading{align-items:flex-start;flex-wrap:wrap}}
</style>
