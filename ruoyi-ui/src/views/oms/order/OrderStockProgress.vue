<template>
  <div class="stock-progress">
    <template v-if="order.status === 'VOIDED'">
      <div class="stock-progress-main">原需求 <b>{{ order.demand_quantity || 0 }}</b> 件</div>
      <div class="stock-progress-meta">订单共 {{ order.sku_count || 0 }} 个 SKU</div>
      <div class="stock-progress-meta">需求已取消，无需逻辑预占</div>
    </template>
    <template v-else>
      <div class="stock-progress-main" :title="'已逻辑预占 ' + (order.reserved_quantity || 0) + ' 件 / 需求 ' + effectiveDemand + ' 件'">逻辑预占 <b>{{ order.reserved_quantity || 0 }}</b> / {{ effectiveDemand }} 件</div><div v-if="order.cancelled_quantity" class="stock-progress-meta">累计需求 {{ order.demand_quantity }} · 已终止 {{ order.cancelled_quantity }} 件</div>
      <div v-if="order.issue_synced_at" class="stock-progress-meta">来源：渠道锁库 {{ order.locked_reserved_quantity || 0 }} · 公共补占 {{ order.public_reserved_quantity || 0 }} 件</div>
      <div class="stock-progress-meta">共 {{ order.sku_count || 0 }} 个 SKU</div><div v-if="order.logical_outbound_quantity" class="stock-progress-complete">已出库核销 {{ order.logical_outbound_quantity }} 件 · 仍预占 {{ order.logical_active_quantity }} 件</div>
      <div v-if="order.status === 'VOIDING'" class="stock-progress-meta">作废处理中，等待逻辑预占释放</div>
      <div v-else-if="order.pending_quantity > 0" class="stock-progress-pending">待逻辑预占 {{ order.pending_quantity }} 件</div>
      <div v-else-if="order.inventory_status === 'RESERVED'" class="stock-progress-complete">逻辑预占完成</div>
    </template>
  </div>
</template>

<script>
export default {
  name: 'OrderStockProgress',
  props: { order: { type: Object, required: true } },
  computed: { effectiveDemand() { return Math.max(0, Number(this.order.demand_quantity || 0) - Number(this.order.cancelled_quantity || 0)) } }
}
</script>

<style scoped>
.stock-progress{line-height:1.6;word-break:break-word}
.stock-progress-main{color:#526176;font-size:13px}
.stock-progress-main b{font-size:16px;font-variant-numeric:tabular-nums}
.stock-progress-meta{color:#8490a2;font-size:12px;margin-top:2px}
.stock-progress-pending{color:#9a6700;font-size:12px;margin-top:2px}
.stock-progress-complete{color:#218567;font-size:12px;margin-top:2px}
</style>
