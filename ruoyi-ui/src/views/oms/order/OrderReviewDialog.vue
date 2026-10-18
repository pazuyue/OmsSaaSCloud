<template>
  <el-dialog class="review-dialog" top="5vh" :title="title" :visible.sync="visible" width="min(850px, 94vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-alert v-if="action === 'hold'" title="挂起仅暂停 OMS，不会通知仓库截单" description="已下发仓库的发货单仍可能继续发货，真实出库回传仍正常接收并记账。如需仓库截停，请逐单申请撤回，并核对仓库确认结果。" type="warning" :closable="false" show-icon class="hold-notice" />
    <template v-if="!completed">
      <el-alert :title="explanation" type="info" :closable="false" show-icon />
      <p>本次处理 {{ orders.length }} 张订单。每张订单独立校验，不符合条件的订单会显示原因。</p>
      <el-input v-model.trim="reason" type="textarea" :rows="3" maxlength="200" show-word-limit :placeholder="action === 'hold' ? '请填写挂起原因（必填）' : action === 'approve' ? '请填写人工审核说明（必填），原命中条件将保留' : '操作说明（选填）'" />
    </template>
    <el-table v-else :data="results" max-height="440"><el-table-column label="业务订单" min-width="240"><template slot-scope="s">{{ s.row.order_sn || s.row.id }}</template></el-table-column><el-table-column label="结果" width="90"><template slot-scope="s"><el-tag :type="s.row.success ? 'success' : 'warning'">{{ s.row.success ? '成功' : '未处理' }}</el-tag></template></el-table-column><el-table-column label="说明" prop="message" min-width="260" /></el-table>
    <section v-if="action === 'hold' && withdrawalOrders.length" class="withdrawal-guide">
      <h4>需要撤回并暂缓发货？</h4>
      <p>撤回保留销售需求和逻辑预占，仓库确认取消后释放对应分仓占用；后续人工重新分仓生成新 XS。已有人工挂起会保留，继续发货前需解除。</p>
      <el-table :data="withdrawalOrders" size="small" max-height="220">
        <el-table-column label="业务订单" prop="order_sn" min-width="240" />
        <el-table-column label="操作" width="180"><template slot-scope="s"><el-button v-hasPermi="['order:order:cutoff']" type="text" :disabled="saving" @click="withdraw(s.row)">申请撤回并暂缓发货</el-button></template></el-table-column>
      </el-table>
      <p>点击入口仅打开撤回申请，不会自动提交或代替本次挂起。</p>
    </section>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">{{ completed ? '关闭' : '取消' }}</el-button><el-button v-if="!completed" type="primary" :loading="saving" :disabled="['hold', 'approve'].includes(action) && !reason" @click="submit">确认{{ title }}</el-button></span>
  </el-dialog>
</template>
<script>
import { reviewOrders } from '@/api/order'
export default {
  data() { return { visible: false, saving: false, completed: false, action: '', orders: [], selectedRows: [], reason: '', results: [] } },
  computed: {
    withdrawalOrders() { return this.selectedRows.filter(row => row.can_cutoff && row.cutoff_state !== 'PROCESSING') },
    title() { return ({ approve: '审核通过', hold: '挂起订单', resume: '解除挂起' })[this.action] || '' },
    explanation() { return ({ approve: '审核不解除挂起。待逻辑预占订单可先审核，分仓按店铺整单或部分发货策略校验库存。', hold: '挂起后暂停后续逻辑预占、分仓、匹配快递及新下发。保留销售需求、已有逻辑预占和分仓占用，审核结果不变。', resume: '仅解除 OMS 人工挂起，保留审核结果，不会恢复已取消的仓库发货单。撤回完成的订单仍需人工重新分仓；平台阻断未解除的订单不能恢复。' })[this.action] || '' }
  },
  methods: {
    open(action, rows) { if (!rows.length || rows.length > 100) return this.$message.warning('请选择1至100张订单'); this.action = action; this.selectedRows = rows.map(row => ({ ...row })); this.orders = rows.map(r => ({ id: r.id, control_version: r.control_version })); this.reason = ''; this.results = []; this.completed = false; this.visible = true },
    withdraw(row) { if (this.saving) return; this.visible = false; this.$emit('withdraw', row) },
    async submit() { if (this.saving) return; if (['hold', 'approve'].includes(this.action) && !this.reason) return this.$message.warning(this.action === 'hold' ? '请填写挂起原因' : '请填写人工审核说明'); this.saving = true; try { this.results = (await reviewOrders(this.action, { orders: this.orders, reason: this.reason })).data; this.completed = true; this.$emit('changed') } finally { this.saving = false } }
  }
}
</script>

<style scoped>
.review-dialog ::v-deep .el-dialog__body{max-height:calc(100vh - 230px);overflow:auto}.hold-notice{margin-bottom:16px}.withdrawal-guide{margin-top:20px;padding:14px;background:#f5f8fc;border:1px solid #e4eaf3;border-radius:8px}.withdrawal-guide h4{margin:0 0 8px}.withdrawal-guide p{font-size:13px;line-height:1.7;color:#606d80;margin:8px 0}
</style>
