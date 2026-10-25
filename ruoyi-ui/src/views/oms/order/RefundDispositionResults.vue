<template>
  <section class="refund-disposition" v-loading="loading" aria-label="本退款商品处置结果">
    <div class="result-heading"><div><h3>本退款商品处置结果</h3><p class="hint">取消、退回及库存结果按原来源核对；以下仅统计本退款，停止的旧任务不计入完成数量。</p></div><el-button size="small" icon="el-icon-refresh" @click="load">刷新结果</el-button></div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <template v-if="result">
      <el-alert v-if="result.receipt_issue" :title="result.receipt_issue" type="warning" :closable="false" show-icon />
      <refund-logistics v-if="result.logistics" :value="result.logistics" :refund="refund" @platform="$emit('open-platform')" @plan="$emit('open-plan',$event)" @parcel="$emit('open-parcel',$event)" />
      <el-alert v-if="result.truncated" title="当前只展示有界明细，不能据此判断整单全部完成，请核对完整原单据。" type="warning" :closable="false" show-icon />
      <div class="result-filter"><el-radio-group v-model="scope" size="small"><el-radio-button label="all">全部结果</el-radio-button><el-radio-button label="cancel">未发取消</el-radio-button><el-radio-button label="return">购买商品退回</el-radio-button><el-radio-button label="gift">关联赠品退回</el-radio-button></el-radio-group><el-checkbox v-if="stoppedCount" v-model="showStopped">显示已停止任务（{{ stoppedCount }}）</el-checkbox></div>
      <template v-if="scope==='all'||scope==='cancel'">
        <h4>未发商品取消结果 <span class="hint">仓库撤回与逻辑预占释放是不同阶段，数量不相加。</span></h4>
        <el-table v-if="cancellations.length" ref="cancelTable" :data="cancellations" row-key="key" size="small" class="cancellation-results">
          <el-table-column type="expand"><template slot-scope="{row}"><div class="result-detail">
            <div v-if="row.warehouses.length" class="warehouse-results"><article v-for="w in row.warehouses" :key="w.shipment_id"><div><b>{{ w.kind==='LOCAL'?'本地发货安排撤回':'仓内截单' }}</b> · {{ warehouseState(w.warehouse_state) }}<router-link :to="shipmentLink(w.shipment_sn)">原发货单 {{ w.shipment_sn }}</router-link></div><p>整张原单撤回范围：{{ scopeQuantity(w.whole_scope) }}；其中本退款商品所在范围：{{ scopeQuantity(w.scope) }}。</p><p class="hint">本退款计划取消 {{ qty(row.quantity,row) }}；上述原单撤回范围不代表这些商品全部取消，其他销售需求以原订单为准。</p><div v-for="(s,i) in w.scope" :key="i" class="hint">发货仓 {{ s.store_code }} · {{ s.goods_name || s.sku_sn }} · {{ qty(s.quantity,s) }}</div><p :class="w.stock_receipt_confirmed?'success':'warning'">分仓撤回回执：{{ w.stock_receipt_confirmed?'已核对':'待核对' }}<span v-if="w.last_error"> · {{ w.last_error }}</span></p></article></div>
            <p v-else class="hint">未关联仓库截单明细；本退款销售需求取消与库存原请求仍独立核对。</p>
            <h5>库存原回执（本退款取消请求）</h5><el-descriptions v-if="row.receipt_confirmed" :column="3" border size="small"><el-descriptions-item label="原请求编号">{{ row.receipt_request_id }}</el-descriptions-item><el-descriptions-item label="需求取消结果">已确认 {{ qty(row.canceled_quantity,row) }}</el-descriptions-item><el-descriptions-item label="实际释放预占">{{ row.logical_released_quantity==null?'原回执未记录数量':qty(row.logical_released_quantity,row) }}</el-descriptions-item><el-descriptions-item label="回执记录时间">{{ time((row.inventory_receipt||{}).recorded_at) }}</el-descriptions-item></el-descriptions><p v-else class="warning">原回执尚未取得或身份未匹配，取消与释放数量待核对。</p>
            <h5>执行节点</h5><el-table :data="row.timeline" size="mini"><el-table-column prop="title" label="处理环节" min-width="165"/><el-table-column label="结果" width="130"><template slot-scope="s">{{ nodeState(s.row.state) }}</template></el-table-column><el-table-column prop="time" label="已记录时间" width="180"><template slot-scope="s">{{ time(s.row.time) }}</template></el-table-column><el-table-column prop="actor" label="来源 / 执行方" min-width="130"/><el-table-column prop="detail" label="结果依据" min-width="300"/></el-table>
            <p class="hint">库存原请求：{{ row.receipt_request_id || '尚未发出' }}。未记录的节点时间不使用“最近刷新时间”代替。</p><router-link :to="orderLink">查看原订单与截单记录</router-link>
          </div></template></el-table-column>
          <el-table-column label="商品 / 来源" min-width="245"><template slot-scope="{row}"><strong>{{ row.goods_name || row.sku_sn }}</strong><div class="hint">{{ row.sku_sn }}</div><div class="hint">{{ sourceStores(row) }}</div></template></el-table-column>
          <el-table-column label="计划取消" width="105"><template slot-scope="{row}">{{ qty(row.quantity,row) }}</template></el-table-column>
          <el-table-column label="确认取消" width="110"><template slot-scope="{row}">{{ qty(row.canceled_quantity,row) }}</template></el-table-column>
          <el-table-column label="释放逻辑预占" min-width="150"><template slot-scope="{row}">{{ row.logical_released_quantity==null?'数量未记录 / 待核对':qty(row.logical_released_quantity,row) }}</template></el-table-column>
          <el-table-column label="处理结果" min-width="235"><template slot-scope="{row}"><el-tag :type="cancelResult(row).tone" size="mini">{{ cancelResult(row).label }}</el-tag><p class="hint">{{ cancelResult(row).detail }}</p><div v-if="row.result_message" class="hint">{{ row.result_message }}</div></template></el-table-column>
          <el-table-column label="操作" width="105"><template slot-scope="{row}"><el-button type="text" @click="$refs.cancelTable.toggleRowExpansion(row)">查看明细</el-button><el-button v-if="row.state==='MANUAL'" v-hasPermi="['order:refund:goods']" type="text" :loading="retrying" @click="retry(row)">重新核对</el-button></template></el-table-column>
        </el-table><p v-else class="empty-result">{{ refund.goods_action==='KEEP'?'本退款已采用免退货或继续履约方案，无未发取消任务。':'本退款暂无未发取消任务；待审核不代表取消已经完成。' }}</p>
      </template>
      <template v-if="scope!=='cancel'">
        <h4>{{ scope==='gift'?'本退款关联赠品退回结果':'本退款商品退回结果' }} <span class="hint">实收、入库、不再追回分别列示，未分配回传不计入实收。</span></h4>
        <el-table v-if="returns.length" ref="returnTable" :data="returns" row-key="key" size="small" class="disposition-return-results">
          <el-table-column type="expand"><template slot-scope="{row}"><div class="result-detail">
            <p>处理方式：{{ modeText(row.mode) }} · 原发货仓 {{ row.original_store_code || '未提供' }}<router-link v-if="row.shipment_sn" :to="shipmentLink(row.shipment_sn)">原发货单 {{ row.shipment_sn }}</router-link></p>
            <p>退回仓：{{ row.store_code || '待确认' }} · 关联包裹 {{ row.parcel_sn || '尚未关联' }}<span v-if="row.shared_document"> · 与其他商品共用THK，本行数量仅属于本退款此来源。</span></p>
            <p class="hint">物流记录：{{ row.transport_display || transportState(row.transport_state) }}。</p>
            <div class="quality-results"><span>正品实收 {{ qty(row.good_quantity,row) }}</span><span>次品实收 {{ qty(row.bad_quantity,row) }}</span><span>正品已入库 {{ qty(row.posted_good_quantity,row) }}</span><span>次品已入库 {{ qty(row.posted_bad_quantity,row) }}</span></div>
            <el-alert v-if="row.parcel_pending" title="包裹另有实收待分配，尚未归属本退款，不计入本行实收及库存。" type="warning" :closable="false"/>
            <p v-if="row.pending_review_quantity" class="warning">另有回传待核对 {{ qty(row.pending_review_quantity,row) }}；未计入确认实收。</p>
            <p v-if="row.waived_quantity" class="warning">已确认不再追回 {{ qty(row.waived_quantity,row) }}，与实际收到数量分别保留。</p>
            <div class="result-documents"><b>关联退货入库单</b><p v-for="d in row.documents" :key="d.id"><router-link :to="ticketLink(d)">{{ d.return_sn }}</router-link> · {{ nodeState(d.dispatch_state) }} · {{ d.cancel_state==='CONFIRMED'?'剩余收货已截停':'沿用原单核对实收' }}</p><p v-if="!row.documents.length" class="hint">尚未生成THK；建立安排不等于仓库已接单。</p></div>
            <h5>实收与入账依据</h5><el-table :data="row.receipt_lines || []" size="mini" empty-text="尚无已归属本退款的实收记录"><el-table-column prop="return_sn" label="退货入库单" min-width="245"/><el-table-column prop="batch_code" label="入库批次" min-width="175"/><el-table-column label="正品 / 次品" width="130"><template slot-scope="s">{{ s.row.good_quantity || 0 }} / {{ s.row.bad_quantity || 0 }} {{ row.stock_unit }}</template></el-table-column><el-table-column label="入账结果" width="120"><template slot-scope="s">{{ Number(s.row.posted)?'已入账':'待入账' }}</template></el-table-column><el-table-column prop="recorded_at" label="回传记录时间" width="185"/></el-table>
            <h5>执行节点</h5><el-table :data="row.timeline" size="mini"><el-table-column prop="title" label="处理环节" min-width="150"/><el-table-column label="结果" width="135"><template slot-scope="s">{{ nodeState(s.row.state) }}</template></el-table-column><el-table-column label="已记录时间" width="185"><template slot-scope="s">{{ time(s.row.time) }}</template></el-table-column><el-table-column prop="actor" label="来源 / 执行方" min-width="120"/><el-table-column prop="detail" label="说明" min-width="280"/></el-table>
            <p class="hint">回传时间与入账完成时间分别记录；没有独立时间记录时显示“未记录”。次品入库不直接等同可售库存增加。</p>
          </div></template></el-table-column>
          <el-table-column label="商品 / 原来源 / 退回仓" min-width="250"><template slot-scope="{row}"><strong>{{ row.goods_name || row.sku_sn }}</strong><div class="hint">{{ row.sku_sn }}</div><el-tag size="mini" :type="row.item_kind==='GIFT'?'warning':'info'">{{ row.item_kind==='GIFT'?'关联赠品':'购买商品' }}</el-tag><span class="hint"> · {{ modeText(row.mode) }}</span><div v-if="row.parent_goods_name" class="hint">所属 {{ row.parent_goods_name }} · {{ row.parent_sku_sn }}</div><div class="hint">{{ row.original_store_code || '原仓待核对' }} → {{ row.store_code || '退回仓待确认' }}</div></template></el-table-column>
          <el-table-column label="应退" width="90"><template slot-scope="{row}">{{ qty(row.quantity,row) }}</template></el-table-column>
          <el-table-column label="确认实收" width="100"><template slot-scope="{row}">{{ qty(row.accepted_quantity,row) }}</template></el-table-column>
          <el-table-column label="已入库" width="95"><template slot-scope="{row}">{{ qty(row.posted_quantity,row) }}</template></el-table-column>
          <el-table-column label="仍需处理" min-width="170"><template slot-scope="{row}"><div>未收 {{ qty(row.remaining_quantity,row) }}</div><div v-if="row.unposted_quantity" class="warning">已收待入库 {{ qty(row.unposted_quantity,row) }}</div><div v-if="row.pending_review_quantity" class="warning">回传待核对 {{ qty(row.pending_review_quantity,row) }}</div><div v-if="row.parcel_pending" class="warning">另有整包实收待分配</div><div v-if="row.waived_quantity" class="hint">不再追回 {{ qty(row.waived_quantity,row) }}</div></template></el-table-column>
          <el-table-column label="当前结果" min-width="195"><template slot-scope="{row}"><el-tag size="mini" :type="returned(row).tone">{{ returned(row).label }}</el-tag><p class="hint">{{ returned(row).detail }}</p><p v-if="row.result_message" class="warning">{{ row.result_message }}</p></template></el-table-column>
          <el-table-column label="操作" width="125"><template slot-scope="{row}"><el-button type="text" @click="$refs.returnTable.toggleRowExpansion(row)">查看明细</el-button><el-button v-if="row.plan_id" type="text" @click="$emit('open-plan',row)">处理原安排</el-button><el-button v-if="row.parcel_id" type="text" @click="$emit('open-parcel',row)">查看包裹</el-button></template></el-table-column>
        </el-table><p v-else class="empty-result">{{ scope==='gift'?'本退款尚无已关联的赠品退回安排；原订单其他赠品另行核对。':'本退款暂无退回安排；是否需要退回以已审核方案及实际发货来源为准。' }}</p>
      </template>
      <el-collapse class="result-history"><el-collapse-item title="物流 / 平台回告结果与业务处理记录" name="history"><el-alert title="平台返回成功只代表本次操作响应；不会替代仓库截停、商品实收或库存入账。" type="info" :closable="false"/><el-table :data="result.platform_operations || []" size="small" empty-text="本退款暂无拦截或取消回告记录"><el-table-column label="平台动作" min-width="160"><template slot-scope="s">{{ operationText(s.row.action) }}</template></el-table-column><el-table-column label="结果" width="135"><template slot-scope="s">{{ nodeState(s.row.state) }}</template></el-table-column><el-table-column prop="actor" label="执行来源" min-width="135"/><el-table-column prop="create_time" label="操作记录时间" width="180"/><el-table-column prop="result_message" label="结果说明" min-width="250"/></el-table><el-table :data="result.events || []" size="small"><el-table-column prop="create_time" label="时间" width="180"/><el-table-column prop="actor" label="执行来源" width="150"/><el-table-column prop="message" label="业务记录" min-width="450"/></el-table></el-collapse-item></el-collapse>
    </template>
  </section>
</template>
<script>
import RefundLogistics from './RefundLogistics'
import { refundDisposition, retryRefundGoodsTask } from '@/api/order/refund'
import { cancellationResult, returnResult, stopped, valueQuantity, scopeQuantity, nodeState, modeText } from './refundDispositionPresentation'
import { operationText } from './refundProgressPresentation'
export default {
  components: { RefundLogistics },
  props: { refund: { type: Object, required: true }, active: Boolean },
  data: () => ({ result: null, loading: false, error: '', scope: 'all', showStopped: false, retrying: false, sequence: 0 }),
  watch: { active(value) { if(value && !this.result) this.load() }, refund() { this.sequence++;this.result=null;this.loading=false;if(this.active)this.load() } },
  created() { if(this.active) this.load() }, beforeDestroy() { this.sequence++ },
  computed: {
    cancellations() { return ((this.result || {}).cancellations || []).filter(row => this.showStopped || !stopped(row)) },
    returns() { return ((this.result || {}).returns || []).filter(row => (this.showStopped || !stopped(row)) && (this.scope==='gift'?row.item_kind==='GIFT':this.scope==='return'?row.item_kind!=='GIFT':true)) },
    stoppedCount() { return [...((this.result || {}).cancellations || []),...((this.result || {}).returns || [])].filter(stopped).length },
    orderLink() { return {path:'/oms-order/business',query:{view:'business',orderId:this.refund.order_id}} }
  },
  methods: {
    qty:valueQuantity, scopeQuantity, nodeState, modeText, operationText, cancelResult:cancellationResult, returned:returnResult,
    time(value) { return value ? String(value).replace('T',' ') : '未记录' },
    shipmentLink(number) { return {path:'/oms-order/shipments',query:{number}} },
    ticketLink(row) { return {path:'/oms-supplychain/wmsTickets',query:{sn:row.return_sn,documentId:row.ticket_id}} },
    sourceStores(row) { const values=[...new Set((row.warehouses || []).flatMap(w=>w.scope || []).map(s=>s.store_code).filter(Boolean))];return values.length?'原发货仓：'+values.join('、'):'未关联原仓库截单记录' },
    warehouseState(value) { return ({CONFIRMED:'撤回已确认',OUTBOUND:'已实际出库，未按截停释放',PENDING:'等待仓库确认'})[value] || '原结果待核对' },
    transportState(value) { return ({WAITING:'待退回',IN_TRANSIT:'退回在途',REFUSED:'拒收退回',INTERCEPTED:'已拦截待退回',DELIVERED:'物流显示签收'})[value] || '未取得独立物流结果' },
    async load() { const seq=++this.sequence;this.loading=true;this.error='';try{const response=await refundDisposition(this.refund.id);if(seq===this.sequence)this.result=response.data}catch(e){if(seq===this.sequence){this.result=null;this.error=e.message || '处置结果暂未读取，请重新核对'}}finally{if(seq===this.sequence)this.loading=false} },
    async retry(row) { if(this.retrying)return;this.retrying=true;try{await retryRefundGoodsTask(this.refund.id,row.task_id);this.$emit('changed');await this.load()}finally{this.retrying=false} }
  }
}
</script>
<style scoped>
.refund-disposition{margin:24px 0;border:1px solid #e1e8f0;border-radius:8px;padding:18px}.result-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.result-heading h3{font-size:15px;margin:0;color:#30445f}.hint{font-size:12px;color:#7c8ba1;line-height:1.8}.result-heading p{margin:6px 0}.result-filter{display:flex;gap:18px;align-items:center;flex-wrap:wrap;margin:16px 0}h4{font-size:14px;color:#405774;margin:18px 0 12px}h5{font-size:13px;margin:18px 0 8px}.result-detail{padding:14px;background:#f7f9fc;line-height:1.8}.result-detail p{font-size:12px;margin:8px 0}.result-detail a{color:#1677ff;font-size:12px;margin-left:12px}.warehouse-results article{border:1px solid #e3eaf3;padding:12px;margin-bottom:10px;border-radius:6px;font-size:13px}.warning{font-size:12px;color:#a16c20;line-height:1.8}.success{color:#338464}.empty-result{background:#f7f9fc;color:#7a8ba0;padding:16px;font-size:12px}.quality-results{display:flex;flex-wrap:wrap;gap:10px 25px;font-size:12px;color:#526b87}.result-documents{margin:16px 0;font-size:12px}.result-history{margin-top:18px}.el-table .el-button{display:block;margin:0;padding:5px 0}.result-history .el-table{margin-top:14px}.el-alert{margin:12px 0}@media(max-width:900px){.refund-disposition{padding:12px}.result-heading{flex-wrap:wrap}}
</style>
