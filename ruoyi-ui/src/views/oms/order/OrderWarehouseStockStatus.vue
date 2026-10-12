<template>
  <span class="warehouse-stock-status">
    <el-tag size="small" :type="warehouseStockTone(order.warehouse_inventory_status)">{{ warehouseStockLabel(order.warehouse_inventory_status) }}</el-tag>
    <span v-if="progress" class="warehouse-progress">{{ progress }}</span>
  </span>
</template>

<script>
import { warehouseStockLabel, warehouseStockTone } from './stockLabels'

export default {
  name: 'OrderWarehouseStockStatus',
  props: { order: { type: Object, required: true }},
  computed: {
    progress() {
      const status = this.order.warehouse_inventory_status
      const outbound = ['PART_OUTBOUND', 'OUTBOUND'].includes(status)
      if (!outbound && !['PART_OCCUPIED', 'OCCUPIED'].includes(status)) return ''
      const quantity = this.order[outbound ? 'warehouse_outbound_quantity' : 'warehouse_occupied_quantity']
      const required = this.order.warehouse_required_quantity
      if (quantity === undefined || quantity === null || required === undefined || required === null) return ''
      return `${outbound ? '已出库' : '已占用'} ${quantity} / ${required} 件${outbound && this.order.warehouse_occupied_quantity ? ' · 仍占用 ' + this.order.warehouse_occupied_quantity + ' 件' : ''}`
    }
  },
  methods: { warehouseStockLabel, warehouseStockTone }
}
</script>

<style scoped>
.warehouse-progress{display:block;margin-top:4px;font-size:12px;line-height:1.6;color:#68778b;font-variant-numeric:tabular-nums}
</style>
