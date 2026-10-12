<template>
  <div>
    <el-alert v-if="order.allocation_error" :title="order.allocation_error" type="warning" :closable="false" show-icon />
    <el-descriptions :column="2" border class="summary"><el-descriptions-item label="分仓结果">{{ ({ NONE: '未分仓', PROCESSING: '分仓处理中', UNCERTAIN: '结果待确认，请重试核对', FAILED: '分仓失败', PART: '部分分仓', FULL: '全部分仓' })[order.allocation_status] || '未分仓' }}</el-descriptions-item><el-descriptions-item label="已分仓 / 原单需求">{{ order.allocation_quantity || 0 }} / {{ order.demand_quantity || 0 }} 件</el-descriptions-item><el-descriptions-item label="待分仓余量">{{ order.status === 'VOIDED' ? 0 : Math.max(0, (order.demand_quantity || 0) - (order.allocation_quantity || 0)) }} 件</el-descriptions-item><el-descriptions-item label="最近分仓时间">{{ order.allocation_at || '—' }}</el-descriptions-item></el-descriptions>
    <p v-if="order.allocation_rule" class="muted">收货省份：{{ order.allocation_rule.receiver_province || '未识别，使用默认顺序' }} · 多仓：{{ order.allocation_rule.allow_multi ? '允许' : '不允许' }} · 部分发货：{{ order.allocation_rule.allow_partial ? '允许' : '不允许' }} · 同 SKU 跨仓：{{ order.allocation_rule.allow_split_sku ? '允许' : '不允许' }}</p>
    <el-table :data="order.warehouse_allocations || []" empty-text="尚无仓库分仓回执"><el-table-column label="实体仓 / 虚拟仓" min-width="230"><template slot-scope="s">{{ s.row.real_store_name }}（{{ s.row.real_store_code }}）<div class="muted">{{ s.row.store_code }} · 货主 {{ s.row.owner_code }}</div></template></el-table-column><el-table-column label="SKU / 平台子单" min-width="200"><template slot-scope="s">{{ s.row.sku_sn }}<div class="muted">{{ s.row.platform_oid }}</div></template></el-table-column><el-table-column label="批次 ID" prop="batch_id" width="100" /><el-table-column label="数量" prop="quantity" width="90" /><el-table-column label="占用来源" min-width="160"><template slot-scope="s">{{ s.row.source_type === 'LOCK' ? '继承渠道锁库' : '公共库存转占用' }}<div class="muted">{{ order.status === 'VOIDED' ? '已释放' : '待下发仓库' }}</div></template></el-table-column></el-table>
  </div>
</template>
<script>
export default { props: { order: { type: Object, required: true } } }
</script>
<style scoped>.summary{margin:16px 0}.muted{font-size:13px;color:#8490a2}</style>
