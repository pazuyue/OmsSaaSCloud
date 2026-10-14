<template>
  <el-dialog title="订单分仓" :visible.sync="visible" width="min(900px, 94vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-alert title="按店铺规则分配虚拟仓和批次，确认库存占用。不同虚拟仓分别生成发货单、分别下发；包裹以仓库实际出库回传为准。" type="info" :closable="false" show-icon />
    <el-table :data="completed ? results : orders" max-height="460"><el-table-column label="OMS / 平台订单" min-width="250"><template slot-scope="s"><order-identity :order="s.row" /></template></el-table-column><el-table-column label="结果 / 前置条件" min-width="280"><template slot-scope="s"><el-tag v-if="completed" size="mini" :type="s.row.success ? 'success' : 'warning'">{{ s.row.success ? '已处理' : '未分仓' }}</el-tag><div>{{ completed ? s.row.message : s.row.can_allocate ? '可分仓，使用店铺规则' : (s.row.release_blocks || []).join('；') || '请先配置并启用店铺分仓规则' }}</div></template></el-table-column></el-table>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">{{ completed ? '关闭' : '取消' }}</el-button><el-button v-if="!completed" type="primary" :loading="saving" @click="submit">执行分仓（{{ orders.length }} 单）</el-button></span>
  </el-dialog>
</template>
<script>
import OrderIdentity from './OrderIdentity'
import { allocateOrders } from '@/api/order'
export default {
  components: { OrderIdentity },
  data() { return { visible: false, saving: false, completed: false, orders: [], results: [] } },
  methods: {
    open(rows) { if (!rows.length || rows.length > 20) return this.$message.warning('分仓每次请选择1至20张订单'); this.orders = rows.map(r => ({ ...r })); this.completed = false; this.results = []; this.visible = true },
    async submit() { if (this.saving) return; this.saving = true; try { const result = (await allocateOrders({ items: this.orders.map(r => ({ id: r.id, control_version: r.control_version })) })).data; this.results = result.map(r => ({ ...this.orders.find(o => o.id === r.id), ...r })); this.completed = true; this.$emit('changed') } finally { this.saving = false } }
  }
}
</script>
