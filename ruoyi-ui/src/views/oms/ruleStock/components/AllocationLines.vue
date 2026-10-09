<template>
  <el-table :data="rows" size="mini" empty-text="暂无渠道明细">
    <el-table-column label="渠道" prop="channelName" min-width="140" />
    <el-table-column label="目标配额" prop="target" width="100" />
    <el-table-column :label="locking ? '锁库量 · 执行前' : '可售量 · 执行前'" prop="before" width="135" />
    <el-table-column :label="locking ? '锁库量 · 执行后' : '可售量 · 执行后'" prop="after" width="135" />
    <el-table-column label="变化" width="100"><template slot-scope="s">{{ s.row.change > 0 ? '+' : '' }}{{ s.row.change }}</template></el-table-column>
    <el-table-column label="订单预占 / 冻结" width="140"><template slot-scope="s">{{ s.row.reservedStock }} / {{ s.row.frozenStock }}</template></el-table-column>
    <el-table-column label="说明" prop="warning" min-width="180" />
  </el-table>
</template>
<script>
export default { name: 'AllocationLines', props: { rows: { type: Array, default: () => [] }, locking: Boolean }}
</script>
