<template>
  <div v-if="pending || order.receipt_review_lookup_failed" class="order-receipt-review" :class="{ compact }">
    <template v-if="pending"><el-tag size="small" type="warning">仓库回传待核对 {{ pending }} 条</el-tag><span class="review-hint">回传被拒绝，本次未扣账</span><el-button v-if="shipments.length === 1" v-hasPermi="['order:order:query']" type="text" size="mini" @click="$emit('open', shipments[0].id)">处理回传异常</el-button><el-dropdown v-else-if="shipments.length > 1" v-hasPermi="['order:order:query']" @command="$emit('open', Number($event))"><el-button type="text" size="mini">处理回传异常<i class="el-icon-arrow-down el-icon--right" /></el-button><el-dropdown-menu slot="dropdown"><el-dropdown-item v-for="shipment in shipments" :key="shipment.id" :command="shipment.id">{{ shipment.shipment_sn }} · {{ shipment.receipt_review_pending_count }} 条</el-dropdown-item></el-dropdown-menu></el-dropdown></template>
    <span v-if="order.receipt_review_lookup_failed" class="review-hint">仓库回传核对状态读取失败，请刷新重试</span>
  </div>
</template>
<script>
export default { props: { order: { type: Object, required: true }, compact: Boolean }, computed: { pending() { return Number(this.order.receipt_review_pending_count || 0) }, shipments() { return this.order.receipt_review_shipments || [] } } }
</script>
<style scoped>.order-receipt-review{display:flex;flex-wrap:wrap;align-items:center;gap:8px 14px;padding:12px 16px;margin:12px 0;background:#fff8eb;border:1px solid #f1d9ac;border-radius:6px}.review-hint{color:#ad7419;font-size:12px;line-height:1.6}.compact{padding:0;margin:0 0 8px;background:transparent;border:0;gap:4px}.compact .review-hint{flex-basis:100%}</style>
