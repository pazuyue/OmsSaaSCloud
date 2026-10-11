<template>
  <el-dialog :title="title" :visible.sync="visible" width="min(850px, 94vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <template v-if="!completed">
      <el-alert :title="explanation" type="info" :closable="false" show-icon />
      <p>本次处理 {{ orders.length }} 张订单。每张订单独立校验，不符合条件的订单会显示原因。</p>
      <el-input v-model.trim="reason" type="textarea" :rows="3" maxlength="200" show-word-limit :placeholder="action === 'hold' ? '请填写挂起原因（必填）' : '操作说明（选填）'" />
    </template>
    <el-table v-else :data="results" max-height="440"><el-table-column label="业务订单" min-width="240"><template slot-scope="s">{{ s.row.order_sn || s.row.id }}</template></el-table-column><el-table-column label="结果" width="90"><template slot-scope="s"><el-tag :type="s.row.success ? 'success' : 'warning'">{{ s.row.success ? '成功' : '未处理' }}</el-tag></template></el-table-column><el-table-column label="说明" prop="message" min-width="260" /></el-table>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">{{ completed ? '关闭' : '取消' }}</el-button><el-button v-if="!completed" type="primary" :loading="saving" @click="submit">确认{{ title }}</el-button></span>
  </el-dialog>
</template>
<script>
import { reviewOrders } from '@/api/order'
export default {
  data() { return { visible: false, saving: false, completed: false, action: '', orders: [], reason: '', results: [] } },
  computed: {
    title() { return ({ approve: '审核通过', hold: '挂起订单', resume: '解除挂起' })[this.action] || '' },
    explanation() { return ({ approve: '审核不解除挂起。待预占订单可先审核，库存足量后才满足分仓条件。', hold: '挂起后停止后续预占，保留需求和已预占库存；审核结果保持不变。', resume: '仅解除人工挂起，保留审核结果。平台阻断未解除的订单不能恢复。' })[this.action] || '' }
  },
  methods: {
    open(action, rows) { if (!rows.length || rows.length > 100) return this.$message.warning('请选择1至100张订单'); this.action = action; this.orders = rows.map(r => ({ id: r.id, control_version: r.control_version })); this.reason = ''; this.results = []; this.completed = false; this.visible = true },
    async submit() { if (this.saving) return; if (this.action === 'hold' && !this.reason) return this.$message.warning('请填写挂起原因'); this.saving = true; try { this.results = (await reviewOrders(this.action, { orders: this.orders, reason: this.reason })).data; this.completed = true; this.$emit('changed') } finally { this.saving = false } }
  }
}
</script>
