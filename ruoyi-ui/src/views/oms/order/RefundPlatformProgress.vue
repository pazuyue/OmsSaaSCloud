<template>
  <section class="refund-platform-progress">
    <div class="platform-heading"><div><h3>平台处理与资金结果</h3><p>本地审核、平台同意或拒绝、实际退款结果分别记录。</p></div><el-button size="small" @click="$emit('refresh')">刷新已同步结果</el-button></div>
    <el-alert :title="interaction.message || '平台处理结果以已同步的平台资料为准。'" type="info" :closable="false" show-icon />
    <div class="platform-facts"><article><span>平台当前处理状态</span><strong>{{ platform.platform_status ? platformLabel(platform.platform_status) : '尚未同步' }}</strong><small>平台更新时间 {{ platform.source_modified || '未提供' }}</small></article><article><span>平台资金结果</span><strong>{{ refund.funds_state==='SUCCESS' ? '平台已退款'+(confirmedFee==null?'（金额待核对）':' '+money(confirmedFee)) : refund.funds_state==='CLOSED' ? '申请已关闭，未确认退款成功' : '尚未确认退款成功' }}</strong><small>最近同步 {{ platform.last_sync || '未提供' }}</small></article><article><span>OMS向平台提交操作</span><strong>{{ operationSummary }}</strong><small>本地审核通过不会生成“已同意退款”的平台结果。</small></article></div>
    <refund-platform-actions :refund="refund" @refresh="$emit('refresh')" />
    <h4>OMS平台操作记录</h4>
    <p v-if="!operations.length" class="no-operations">当前没有由OMS提交的同意退款、同意退货或拒绝退款记录。平台页面的操作结果可通过下方同步记录核对。</p>
    <el-table v-else :data="operations" size="small" class="platform-operation-records"><el-table-column label="操作" min-width="140"><template slot-scope="s">{{ operationText(s.row.action) }}</template></el-table-column><el-table-column label="提交及返回结果" min-width="160"><template slot-scope="s">{{ operationState(s.row.state) }}<div class="hint">{{ s.row.result_message || '未提供返回说明' }}</div></template></el-table-column><el-table-column label="涉及金额" width="120"><template slot-scope="s">{{ money(s.row.fee) }}</template></el-table-column><el-table-column prop="actor" label="操作人" width="110" /><el-table-column prop="create_time" label="记录时间" width="170" /><el-table-column type="expand"><template slot-scope="s"><p class="hint">请求编号 {{ s.row.request_id }} · 平台申请版本 {{ s.row.platform_version || '未提供' }} · 错误码 {{ s.row.error_code || '无' }}<br>处理依据 {{ s.row.reason }}</p><p class="hint">接口受理或操作返回成功，与平台最终退款成功分别核对。</p></template></el-table-column></el-table>
    <h4>平台状态同步记录 <span class="hint">最近30条来源记录</span></h4>
    <p class="hint">以下是OMS收到的平台事实，不代表由OMS执行了同意或拒绝。异常、冲突和旧版本不会直接覆盖当前采用的方案。</p>
    <el-table :data="observations" size="small" empty-text="尚无平台来源记录" class="platform-observations"><el-table-column label="同步到的平台状态" min-width="220"><template slot-scope="s">{{ s.row.platform_status ? platformLabel(s.row.platform_status) : '资料无法确认' }}<div class="hint">{{ label(s.row.outcome) }}</div></template></el-table-column><el-table-column label="来源申请金额" width="130"><template slot-scope="s">{{ money(s.row.refund_fee) }}</template></el-table-column><el-table-column prop="source_modified" label="平台更新时间" width="170" /><el-table-column prop="create_time" label="OMS收到时间" width="170" /><el-table-column label="操作" width="110"><template slot-scope="s"><el-button type="text" size="small" @click="$emit('snapshot',s.row)">查看来源原文</el-button></template></el-table-column></el-table>
    <p class="hint">刷新详情只读取已保存的同步结果，不会直接向平台提交同意、拒绝或付款请求。</p>
  </section>
</template>
<script>
import RefundPlatformActions from './RefundPlatformActions'
import { label } from './refundLabels'
import { money } from './orderAmounts'
import { operationText, operationState, confirmedRefundFee } from './refundProgressPresentation'
export default {
  name: 'RefundPlatformProgress',
  components: { RefundPlatformActions },
  props: { refund: { type: Object, required: true }},
  computed: {
    platform() { return this.refund.platform || {} },
    interaction() { return this.refund.platform_interaction || {} },
    confirmedFee() { return confirmedRefundFee(this.refund) },
    operationSummary() {
      if (this.operations.length) return operationText(this.operations[0].action) + ' · ' + operationState(this.operations[0].state)
      if (['SUCCESS', 'CLOSED'].includes(this.platform.platform_status)) return '无OMS提交记录'
      return this.interaction.execution_supported ? '当前没有提交记录' : '同意／拒绝需在平台处理'
    },
    operations() { return this.interaction.operations || [] },
    observations() { return this.interaction.observations || [] }
  },
  methods: { label, money, operationText, operationState, platformLabel(value) { return value === 'SUCCESS' ? '退款成功' : value === 'CLOSED' ? '申请已关闭' : label(value) } }
}
</script>
<style scoped>
.refund-platform-progress{margin-top:18px}.platform-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.platform-heading h3{font-size:15px;margin:0}.platform-heading p,.hint{font-size:12px;line-height:1.8;color:#7e8da1}.platform-facts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:18px 0}.platform-facts article{padding:16px;border:1px solid #e3eaf2;border-radius:7px;min-width:0}.platform-facts span,.platform-facts small{display:block;font-size:12px;color:#7e8da1;line-height:1.8}.platform-facts strong{display:block;font-weight:500;font-size:14px;line-height:1.8;margin:8px 0;color:#354c68}.no-operations{padding:14px;background:#f5f8fc;color:#6d7f95;font-size:13px;line-height:1.8;border-radius:6px}h4{font-size:14px;font-weight:500;color:#354c68;margin:24px 0 10px}@media(max-width:900px){.platform-facts{grid-template-columns:minmax(0,1fr)}}
</style>
