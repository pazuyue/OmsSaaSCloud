<template>
  <div :class="['auto-allocation', { compact }]">
    <div><strong v-if="!compact">自动分仓 </strong><el-tag size="mini" :type="tone">{{ label }}</el-tag><span v-if="!compact" class="muted"> · {{ task.execution_mode === 'AUTO' ? '店铺自动模式' : '店铺手动模式' }} · 余量{{ task.auto_replenish ? '自动补充' : '人工处理' }}</span></div>
    <div v-if="!compact && order.demand_quantity" class="muted">已分仓 {{ order.allocation_quantity || 0 }} / {{ order.demand_quantity }} 件 · 剩余 {{ Math.max(0, order.demand_quantity - (order.allocation_quantity || 0)) }} 件</div>
    <div class="reason">{{ task.reason || (task.state === 'PROCESSING' ? '正在检查并分配库存，请稍后刷新' : '等待执行') }}</div>
    <div class="muted" v-if="task.last_attempt">最近 {{ time(task.last_attempt) }}<span v-if="!compact"> · 已尝试 {{ task.attempts || 0 }} 次 · 触发：{{ task.trigger_name || '—' }}</span></div>
    <div class="muted" v-if="task.next_attempt && task.state !== 'PAUSED'">下次 {{ time(task.next_attempt) }}</div>
    <div v-if="!compact && active" class="actions">
      <el-button v-if="canRun" v-hasPermi="['order:order:allocate']" size="mini" :loading="saving" @click="act(task.state === 'PAUSED' ? 'resume' : 'retry')">{{ task.state === 'PAUSED' ? '恢复自动分仓' : '立即重试' }}</el-button>
      <el-button v-if="task.order_id && task.state !== 'PAUSED'" v-hasPermi="['order:order:allocate']" size="mini" :disabled="saving" @click="act('pause')">暂停本单自动分仓</el-button>
      <el-button v-if="order.can_allocate" v-hasPermi="['order:order:allocate']" size="mini" :disabled="saving" @click="$emit('preview')">{{ order.allocation_quantity ? '预览剩余分仓' : '预览分仓' }}</el-button>
    </div>
  </div>
</template>
<script>
import { autoAllocationAction } from '@/api/order'
const labels = { MANUAL: '手动分仓', PENDING: '待执行', PROCESSING: '处理中', WAIT_STOCK: '等待补货', WAIT_CONDITION: '等待前置条件', RETRY: '等待重试', UNCERTAIN: '结果待核对', NEEDS_ACTION: '待人工处理', PARTIAL_PAUSED: '余量待人工分仓', PAUSED: '本单已暂停', DISABLED: '店铺自动分仓已关闭', DONE: '已完成', CANCELED: '已停止' }
export default {
  props: { order: { type: Object, required: true }, compact: Boolean },
  data() { return { saving: false } },
  computed: {
    task() { return this.order.auto_allocation || { state: 'MANUAL', reason: '当前为手动分仓' } },
    label() { return labels[this.task.state] || this.task.state },
    tone() { return this.task.state === 'DONE' ? 'success' : ['NEEDS_ACTION', 'UNCERTAIN'].includes(this.task.state) ? 'danger' : ['MANUAL', 'PAUSED', 'CANCELED', 'DISABLED'].includes(this.task.state) ? 'info' : 'warning' },
    active() { return this.order.status === 'WAIT_ALLOCATION' && this.order.allocation_status !== 'FULL' },
    canRun() { return this.task.execution_mode === 'AUTO' && this.task.enabled && this.task.state !== 'PROCESSING' && (!this.order.allocation_quantity || this.task.auto_replenish) }
  },
  methods: { time(value) { return String(value).replace('T', ' ').slice(0, 19) }, async act(action) { if (this.saving) return; this.saving = true; try { await autoAllocationAction(this.order.id, action, { control_version: this.order.control_version }); this.$message.success(action === 'pause' ? '已暂停后续自动分仓，已提交的占用保留' : '已提交处理'); this.$emit('changed') } finally { this.saving = false } } }
}
</script>
<style scoped>
.auto-allocation{padding:14px 16px;border:1px solid #e4eaf2;background:#f8fafc;border-radius:8px;margin:14px 0}.compact{border:0;background:none;padding:0;margin:0}.reason{font-size:13px;line-height:1.6;margin:6px 0;color:#526079}.muted{font-size:12px;line-height:1.7;color:#8490a2}.actions{margin-top:10px}
</style>
