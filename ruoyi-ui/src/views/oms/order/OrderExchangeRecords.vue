<template>
  <section class="order-exchange-records">
    <div class="records-heading"><div><b>换货记录</b><p class="muted">包含未发货换品与售后换货；申请未生成发货单也会展示。具体操作在换货工作台处理。</p></div><el-radio-group v-model="scope" size="small"><el-radio-button label="active">进行中（{{ active.length }}）</el-radio-button><el-radio-button label="all">全部（{{ records.length }}）</el-radio-button></el-radio-group></div>
    <el-alert v-if="order.exchange_goods_error" :title="order.exchange_goods_error" type="warning" :closable="false" />
    <article v-for="e in shown" :key="e.id" class="exchange-record">
      <header><div><router-link :to="link(e)"><b>{{ e.exchange_sn }}</b></router-link><el-tag size="mini" effect="plain" :type="e.source_kind === 'INTERNAL' ? '' : 'warning'">{{ phaseName(e) }} · {{ e.source_kind === 'INTERNAL' ? '客服协商' : '平台申请' }}</el-tag><el-tag size="mini" :type="ended(e) ? 'info' : 'warning'">{{ states[e.state] || e.state }}</el-tag></div><span class="muted">{{ e.create_time }}</span></header>
      <div class="product-comparison"><div><span class="muted">本次被替换商品</span><b>{{ product(e, 'original').name || '商品名称待核对' }}</b><span>{{ e.original_sku || '来源 SKU 待核对' }}</span><span class="muted"><template v-if="product(e, 'original').spec">{{ product(e, 'original').spec }} · </template>{{ e.requested_quantity }} 件</span></div><i class="el-icon-right" aria-label="替换为" /><div><span class="muted">换货后发出的商品</span><b>{{ product(e, 'target').name || (e.target_sku ? '商品资料暂未加载' : '目标 OMS SKU 待确认') }}</b><span>{{ e.target_sku || e.target_description || '尚未选择' }}</span><span class="muted"><template v-if="product(e, 'target').spec">{{ product(e, 'target').spec }} · </template>{{ e.requested_quantity }} 件</span></div></div>
      <div class="record-facts"><span>平台子单：{{ e.platform_oid }}</span><span>来源履约明细：{{ e.order_line_id ? '#' + e.order_line_id : '待匹配' }}</span><span v-if="e.source_shipment_line_id">来源发货明细：#{{ e.source_shipment_line_id }}</span></div>
      <div class="record-progress"><div><b>{{ statusText(e) }}</b><div v-if="!ended(e) && (e.problem_message || e.last_error)" class="attention">{{ e.problem_message || e.last_error }}</div><div v-if="e.source_kind !== 'INTERNAL'" class="muted">平台状态：{{ platforms[e.platform_status] || e.platform_status || '待核对' }}<span v-if="e.platform_operation !== 'NONE'"> · {{ operations[e.platform_operation] || e.platform_operation }}</span></div></div><router-link :to="link(e)">{{ ended(e) ? '查看换货详情' : '前往换货工作台' }} <i class="el-icon-arrow-right" /></router-link></div>
      <div class="documents"><span v-if="unshipped(e)">生效后从原订单审核、分仓与发货</span><template v-else><span>退回入库单：{{ e.return_sn || '尚未生成' }}</span><span>换出发货单：</span><el-button v-for="ship in e.shipments || []" :key="ship.id" type="text" size="mini" @click="$emit('open-shipment', ship.id)">{{ ship.shipment_sn }}（已出库 {{ ship.shipped_quantity || 0 }} / {{ ship.quantity }} 件）</el-button><span v-if="!(e.shipments || []).length">尚未生成</span></template></div>
    </article>
    <el-empty v-if="!shown.length" :description="scope === 'active' ? '暂无进行中的换货' : '暂无换货记录'" :image-size="70"><el-button v-if="scope === 'active' && records.length" type="text" @click="scope='all'">查看全部记录</el-button></el-empty>
  </section>
</template>
<script>
import { states, stages, platforms, operations } from './exchangeLabels'
import { ended, good, unshipped, phaseName } from './exchangePresentation'
export default {
  props: { order: { type: Object, required: true } },
  data() { return { scope: 'active', states, platforms, operations } },
  computed: { records() { return this.order.exchange_records || [] }, active() { return this.records.filter(e => !ended(e)) }, shown() { return this.scope === 'active' ? this.active : this.records } },
  methods: {
    ended, unshipped, phaseName, product(e, side) { return good(e[side + '_goods'] || {}, e[side + '_sku']) },
    link(e) { return { path: '/oms-order/exchanges', query: { exchangeId: e.id } } },
    statusText(e) { if (ended(e)) return states[e.state]; if (['UNKNOWN', 'SENDING', 'FAILED', 'STOCK_WAIT', 'PENDING'].includes(e.platform_operation)) return operations[e.platform_operation]; if (['NEEDS_REVIEW', 'WAIT_STOCK', 'BLOCKED'].includes(e.state)) return states[e.state]; if (unshipped(e) && e.fulfillment_line_id) return '换品已生效，原单继续发货'; return stages[e.stage] || states[e.state] || '待核对' }
  }
}
</script>
<style scoped>
.records-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin:16px 0}.records-heading p{margin:6px 0}.muted{color:#8490a2;font-size:12px;line-height:1.7}.exchange-record{border:1px solid #e3eaf3;border-radius:8px;margin-bottom:18px;overflow:hidden;font-size:13px;color:#485c73}.exchange-record header{display:flex;justify-content:space-between;gap:16px;padding:14px 18px;background:#f6f9fd}.exchange-record header>div{display:flex;gap:10px;align-items:center;flex-wrap:wrap;word-break:break-word}.product-comparison{display:grid;grid-template-columns:minmax(0,1fr) 36px minmax(0,1fr);align-items:center;gap:14px;padding:18px}.product-comparison>div{display:flex;flex-direction:column;gap:5px;word-break:break-word}.product-comparison>i{font-size:24px;color:#9bacbf}.record-facts{padding:0 18px 14px;display:flex;gap:8px 22px;flex-wrap:wrap;color:#77879c;font-size:12px;word-break:break-word}.record-progress{border-top:1px solid #edf1f7;padding:14px 18px;display:flex;justify-content:space-between;align-items:center;gap:18px}.record-progress>a{flex-shrink:0}.attention{margin-top:5px;color:#ab7618}.documents{padding:10px 18px;background:#fafbfd;display:flex;flex-wrap:wrap;align-items:center;gap:6px 12px;font-size:12px;word-break:break-word}@media(max-width:800px){.records-heading,.exchange-record header,.record-progress{flex-direction:column;align-items:flex-start}.product-comparison{gap:8px;padding:12px}}
</style>
