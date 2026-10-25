<template>
  <section class="refund-logistics" aria-label="物流与在途拦截">
    <div class="logistics-heading"><div><h3>物流与在途拦截</h3><p class="hint">原发货运单、拦截申请、退回物流与仓库收货分别核对；商品数量仅统计本退款。</p></div><el-tag v-if="value.goods_done" size="small" type="success">本退款货物已处理完成</el-tag></div>
    <el-alert v-if="simulation" :title="simulation" type="info" :closable="false" show-icon class="execution-mode"/>
    <el-alert v-for="issue in value.issues || []" :key="issue" :title="issue" type="warning" :closable="false" show-icon/>
    <article v-if="value.has_intercept" class="intercept-workspace">
      <div class="intercept-tracks"><div><span class="track-label">平台拦截申请</span><el-tag size="small" :type="application.tone">{{ application.label }}</el-tag><p class="hint">提交记录：{{ (value.application || {}).actor || '尚无提交记录' }} · {{ time((value.application || {}).recorded_at) }}</p><p class="hint">申请受理与快递实际拦截结果分别确认。</p></div><div><span class="track-label">已登记的物流处理结果</span><el-tag size="small" :type="outcome.tone">{{ outcome.label }}</el-tag><p class="hint">{{ outcome.source }}</p><p v-if="(value.reported_outcome || {}).recorded_at" class="hint">登记 / 回告记录：{{ value.reported_outcome.actor }} · {{ time(value.reported_outcome.recorded_at) }}</p><p v-else class="hint">当前尚未接入快递结果自动查询，可按真实依据核实登记。</p></div></div>
      <p v-if="value.goods_done && !(value.reported_outcome || {}).confirmed" class="hint completed-proof">本退款货物处理已完成；物流反馈缺失保留为追溯信息，完成结果保持不变。</p>
      <p v-if="(value.application || {}).state==='FAILED'" class="warning">{{ value.application.message }}</p>
      <div class="logistics-actions"><el-button size="mini" @click="$emit('platform')">查看拦截申请与回告记录</el-button><el-button v-if="canReport" v-hasPermi="['order:refund:execute']" size="mini" @click="$emit('platform')">去登记 / 回告物流结果</el-button></div>
    </article>
    <el-collapse class="outgoing-packages"><el-collapse-item :title="'原发货包裹与运单（'+(value.original_packages || []).length+'个）'" name="outgoing">
      <el-alert v-if="value.packages_truncated" title="原包裹明细已达到展示上限，请核对原发货单完整范围。" type="warning" :closable="false"/>
      <article v-for="p in value.original_packages || []" :key="p.id" class="logistics-package">
        <div class="waybill-heading"><b>原发货运单</b><span>{{ p.carrier_name || p.carrier_code || '承运商待核对' }}</span><code>{{ p.express_code || '尚未登记原运单' }}</code><el-button v-if="p.express_code" v-clipboard:copy="p.express_code" type="text" size="mini" aria-label="复制原发货运单">复制</el-button></div>
        <p class="hint">来源：OMS原发货包裹 · 运单登记时间 {{ time(p.registered_at) }} <router-link :to="{path:'/oms-order/shipments',query:{number:p.shipment_sn}}">原发货单 {{ p.shipment_sn }}</router-link></p>
        <p v-if="value.has_intercept && !p.whole_package_covered" class="warning">包内商品未全部覆盖在当前拦截安排中，请结合原申请核对整包范围。</p>
        <el-table :data="p.lines || []" size="mini"><el-table-column label="包内商品 / SKU" min-width="260"><template slot-scope="{row}">{{ row.goods_name || row.sku_sn }}<div class="hint">{{ row.sku_sn }} · {{ row.item_kind==='GIFT'?'赠品':'购买商品' }}</div></template></el-table-column><el-table-column label="包内数量" width="110"><template slot-scope="{row}">{{ row.quantity }} {{ row.stock_unit }}</template></el-table-column><el-table-column label="本退款范围" width="130"><template slot-scope="{row}">{{ row.in_refund_scope?'关联本退款':'同包其他商品' }}</template></el-table-column><el-table-column label="当前拦截安排" min-width="180"><template slot-scope="{row}">{{ row.intercept_covered?'已覆盖该来源包内数量':'范围待核对或无需拦截' }}</template></el-table-column></el-table>
      </article><p v-if="!(value.original_packages || []).length" class="hint">本退款尚无可核对的原发货包裹运单，请查看原发货来源。</p>
    </el-collapse-item></el-collapse>
    <h4>退回物流与仓库收货</h4>
    <article v-for="group in groups" :key="group.key" class="incoming-package">
      <div class="waybill-heading"><b>退回运单</b><span>{{ group.logistics.carrier || (group.logistics.state==='UNAVAILABLE'?'承运商资料暂未读取':'承运商未取得') }}</span><code>{{ group.logistics.waybill || (group.logistics.state==='UNAVAILABLE'?'运单资料暂未读取':'尚未取得') }}</code><el-button v-if="group.logistics.waybill" v-clipboard:copy="group.logistics.waybill" type="text" size="mini" aria-label="复制退回运单">复制</el-button><el-tag size="mini" type="info">{{ waybillState(group.logistics.state) }}</el-tag><el-tag v-if="group.logistics.warehouse_environment==='TEST'" size="mini" type="warning">当前仓库环境：TEST</el-tag></div>
      <p class="hint">来源：{{ source(group.logistics.source) }} · {{ timeLabel(group.logistics) }} {{ group.logistics.state==='UNAVAILABLE'?'暂未读取':time(group.logistics.registered_at) }}<span v-if="group.logistics.parcel_sn"> · 包裹 {{ group.logistics.parcel_sn }}</span><span v-if="group.logistics.refund_count"> · 关联 {{ group.logistics.refund_count }} 张退款</span></p>
      <el-alert v-if="group.logistics.source_conflict" :title="'最新提供的退回物流（'+(group.logistics.latest_claimed_carrier || '承运商未取得')+' / '+(group.logistics.latest_claimed_waybill || '运单未取得')+'）与已关联包裹不一致，当前沿用原包裹，请核对物流归属。'" type="warning" :closable="false"/>
      <p class="hint">{{ group.rows[0].transport_display }}。</p>
      <p v-if="group.logistics.state==='PROVIDED'" class="hint">运单资料已提供，关联包裹及下发条件仍需分别确认。</p>
      <p v-if="group.logistics.state==='MISSING'" class="warning">{{ missingMessage(group) }}</p>
      <div v-if="group.documents.length" class="incoming-documents"><span>关联退货入库单：</span><router-link v-for="d in group.documents" :key="d.return_sn" :to="{path:'/oms-supplychain/wmsTickets',query:{sn:d.return_sn,documentId:d.ticket_id}}">{{ d.return_sn }}</router-link></div>
      <p v-else class="hint">退回安排已建立，尚未生成退货入库单。</p>
      <el-table :data="group.rows" size="small" class="own-logistics-quantities"><el-table-column label="本退款商品 / 原来源" min-width="260"><template slot-scope="{row}">{{ row.goods_name || row.sku_sn }}<div class="hint">{{ row.sku_sn }} · {{ row.item_kind==='GIFT'?'关联赠品':'购买商品' }}</div></template></el-table-column><el-table-column label="应退" width="95"><template slot-scope="{row}">{{ row.quantity }} {{ row.stock_unit }}</template></el-table-column><el-table-column label="确认实收" width="110"><template slot-scope="{row}">{{ row.accepted_quantity }} {{ row.stock_unit }}</template></el-table-column><el-table-column label="已入库" width="100"><template slot-scope="{row}">{{ row.posted_quantity }} {{ row.stock_unit }}</template></el-table-column><el-table-column label="操作" width="135"><template slot-scope="{row}"><el-button type="text" size="mini" @click="$emit('plan',row)">查看退回安排</el-button></template></el-table-column></el-table>
      <div class="logistics-actions"><el-button v-if="group.logistics.parcel_id" size="mini" @click="$emit('parcel',parcelRow(group))">查看退回包裹</el-button><el-button v-if="!value.goods_done && ['MISSING','PROVIDED'].includes(group.logistics.state)" v-hasPermi="['order:refund:goods']" size="mini" @click="$emit('plan',group.rows[0])">去核对退回物流</el-button></div>
    </article>
    <p v-if="!groups.length" class="hint">本退款尚无有效退回安排，是否需要退回以已审核方案为准。</p>
  </section>
</template>
<script>
import { interceptApplication, reportedOutcome, logisticsSource, waybillState, groupReturns } from './refundLogisticsPresentation'
export default {
  props: { value: { type: Object, required: true }, refund: { type: Object, required: true } },
  computed: {
    application() { return interceptApplication(this.value.application) }, outcome() { return reportedOutcome(this.value.reported_outcome) },
    groups() { return groupReturns(this.value.returns) },
    simulation() { return this.value.platform_mode==='SIMULATION'?'当前平台接入为模拟模式；申请、物流反馈和仓库收货结果分别保留。':'' },
    canReport() { return !this.value.goods_done && !['SUCCESS','CLOSED'].includes((this.refund.platform || {}).platform_status) }
  },
  methods: {
    source:logisticsSource, waybillState,
    parcelRow(group) { return {...group.rows[0],parcel_id:group.logistics.parcel_id} },
    time(v) { return v?String(v).replace('T',' '):'未单独记录' },
    timeLabel(info) { return info.state==='UNAVAILABLE'?'运单时间待核对':info.time_kind==='PARCEL_REGISTERED'?'包裹登记时间':info.time_kind==='PLATFORM_UPDATED'?'平台资料更新时间':'运单登记时间' },
    missingMessage(group) { return group.rows.some(r=>r.dispatch_state==='WAIT_LOGISTICS')?'当前下发正在等待退回物流，请核实运单后关联包裹。':'退回运单待补充；请结合原退回安排和下发进度核对。' }
  }
}
</script>
<style scoped>
.refund-logistics{margin-top:22px;padding-top:20px;border-top:1px solid #e5ebf2}.logistics-heading,.waybill-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}.logistics-heading h3{font-size:15px;margin:0;color:#30445f}.hint{font-size:12px;color:#74859c;line-height:1.8;margin:7px 0;overflow-wrap:anywhere}.warning{color:#a56e20;font-size:12px;line-height:1.8}.intercept-workspace{padding:16px;background:#f6f9fd;border-radius:7px;margin:12px 0}.intercept-tracks{display:grid;grid-template-columns:1fr 1fr;gap:24px}.track-label{display:block;font-size:13px;font-weight:600;margin-bottom:10px;color:#405774}.completed-proof{border-top:1px solid #e1e8f2;padding-top:10px}.logistics-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:12px}.logistics-actions .el-button{margin:0}.logistics-package,.incoming-package{border:1px solid #dfe7f1;border-radius:7px;padding:14px;margin:12px 0}.waybill-heading{justify-content:flex-start;font-size:13px;line-height:1.8}.waybill-heading code{font-size:13px;overflow-wrap:anywhere;white-space:normal}.incoming-documents{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;font-size:12px;margin:12px 0}.incoming-documents a{overflow-wrap:anywhere}a{color:#1677ff}.outgoing-packages{margin:14px 0}.el-alert{margin:10px 0}h4{font-size:14px;color:#405774;margin:18px 0 10px}@media(max-width:850px){.intercept-tracks{grid-template-columns:1fr}.logistics-package,.incoming-package{padding:10px}}
</style>
