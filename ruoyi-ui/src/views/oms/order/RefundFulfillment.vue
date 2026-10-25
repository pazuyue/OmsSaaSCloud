<template>
  <section class="refund-fulfillment">
    <div class="fulfillment-heading"><strong>本退款商品的实际履约</strong><el-tag size="small" :type="current.uncertain ? 'warning' : 'info'">{{ stage(current.stage) }}</el-tag></div>
    <p class="hint">子单 {{ refund.platform_oid }} · 按原商品及各仓出库明细核对，平台售中 / 售后阶段另行展示。</p>
    <p v-if="refund.initial_fulfillment_snapshot" class="hint">首次转单：{{ stage(refund.initial_fulfillment_stage) }}；当前：{{ stage(current.stage) }}。首次转单情况不代表平台申请发生时的精确出库情况。</p>
    <el-alert :type="advice.type" :title="advice.title" :description="advice.description" :closable="false" show-icon />
    <el-table :data="current.lines || []" size="small" row-key="order_line_id" class="fulfillment-lines">
      <el-table-column type="expand"><template slot-scope="s"><el-table :data="s.row.sources || []" size="mini"><el-table-column prop="shipment_sn" label="原发货单" min-width="220" /><el-table-column prop="store_code" label="发货仓" min-width="120" /><el-table-column prop="shipped_quantity" label="已出库" width="90" /><el-table-column prop="remaining_quantity" label="待出库" width="90" /><el-table-column label="处理提示" min-width="230"><template slot-scope="source"><div>{{ actions[source.row.suggested_action] || '无需处理未发余量' }}</div><span v-if="source.row.shared_shipment" class="shared-warning">与其他商品共用发货单，需核对撤回范围</span></template></el-table-column></el-table><p v-if="!(s.row.sources || []).length" class="hint">尚未形成发货安排。</p></template></el-table-column>
      <el-table-column label="原商品 / 组件" min-width="200"><template slot-scope="s"><strong>{{ s.row.goods_name }}</strong><div class="hint">{{ s.row.sku_sn }}</div><div v-if="s.row.bundle_id" class="hint">原组合 #{{ s.row.bundle_id }} 的履约组件</div></template></el-table-column>
      <el-table-column label="已取消" width="90"><template slot-scope="s">{{ s.row.cancelled_quantity }} {{ s.row.stock_unit }}</template></el-table-column>
      <el-table-column label="未取消需求" min-width="105"><template slot-scope="s">{{ Number(s.row.quantity) - Number(s.row.cancelled_quantity) }} {{ s.row.stock_unit }}</template></el-table-column>
      <el-table-column label="已出库" min-width="100"><template slot-scope="s">{{ s.row.shipped_quantity }} {{ s.row.stock_unit }}</template></el-table-column>
      <el-table-column label="未出库" min-width="100"><template slot-scope="s">{{ s.row.unshipped_quantity }} {{ s.row.stock_unit }}</template></el-table-column>
      <el-table-column label="实际履约阶段" min-width="130"><template slot-scope="s">{{ stage(s.row.stage) }}</template></el-table-column>
    </el-table>
    <el-table v-if="showTasks && (refund.goods_tasks || []).length" :data="refund.goods_tasks" size="small"><el-table-column label="本退款分项任务"><template slot-scope="s">商品行 #{{ s.row.order_line_id }} · {{ taskActions[s.row.action] || s.row.action }} × {{ s.row.quantity }}</template></el-table-column><el-table-column label="状态" width="140"><template slot-scope="s">{{ taskStates[s.row.state] || s.row.state }}</template></el-table-column><el-table-column prop="result_message" label="处理结果与下一步" min-width="240" /><el-table-column label="操作" width="90"><template slot-scope="s"><el-button v-if="s.row.state==='MANUAL'" v-hasPermi="['order:refund:goods']" type="text" :loading="busy" @click="retry(s.row)">重新核对</el-button></template></el-table-column></el-table>
    <p class="hint">库存占用只在取消得到确认后释放；出库商品实际退回并入账后才增加库存。仓库已接单、面单已生成均不等于实际出库。</p>
  </section>
</template>
<script>
import request from '@/utils/request'
import { fulfillmentStages, fulfillmentActions, fulfillmentAdvice } from './refundFulfillmentPresentation'
export default { name: 'RefundFulfillment', props: { refund: { type: Object, required: true }, showTasks: { type: Boolean, default: true }}, data: () => ({ busy: false, actions: fulfillmentActions, taskActions: { CANCEL: '取消未发', RETURN: '退回', INTERCEPT: '在途拦截退回', REFUSAL: '拒收退回' }, taskStates: { PENDING: '待执行', PROCESSING: '处理中', WAIT_WAREHOUSE: '等待仓库截停', WAIT_RETURN: '已建退回安排', UNKNOWN: '结果待核对', MANUAL: '需人工核对', DONE: '已完成', CANCELED: '已停止' }}), computed: { current() { return this.refund.fulfillment || {} }, advice() { return fulfillmentAdvice(this.refund) } }, methods: { async retry(task) { this.busy = true; try { await request({ url: `/order/refunds/business/${this.refund.id}/goods/tasks/${task.id}/retry`, method: 'post' }); this.$emit('changed') } finally { this.busy = false } }, stage(value) { return fulfillmentStages[value] || fulfillmentStages.UNKNOWN } }}
</script>
<style scoped>
.refund-fulfillment{margin:20px 0;padding:18px;border:1px solid #e4e7ed;border-radius:6px}.fulfillment-heading{display:flex;align-items:center;gap:12px}.fulfillment-lines{margin-top:14px}.hint{color:#75849a;font-size:12px;line-height:1.7}.shared-warning{color:#a86f13;font-size:12px}
</style>
