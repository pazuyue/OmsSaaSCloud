<template>
  <section class="ticket-summary">
    <div class="heading"><strong>{{ document.sourceLabel }}</strong><el-tag type="info">{{ document.modeLabel }}</el-tag><el-tag :type="color">{{ document.progressLabel }}</el-tag><span>{{ document.executionLabel }} · {{ document.postingLabel }}</span></div>
    <p :class="{ warning: document.receiptMissing || document.progress === 'ATTENTION' }">{{ document.nextAction }}</p>
    <el-steps v-if="document.progress !== 'CANCELED'" :active="Number(document.step)" :process-status="document.progress === 'ATTENTION' ? 'error' : 'process'" finish-status="success" simple>
      <template v-if="document.actualWarehouse === 1"><el-step title="下发" /><el-step title="仓库受理" /><el-step title="收货" /><el-step title="入账" /></template>
      <template v-else><el-step title="自动收货" /><el-step title="入账" /></template>
    </el-steps>
    <div class="quantities"><div v-for="item in metrics" :key="item[0]"><span>{{ item[1] }}</span><strong>{{ document[item[0]] }}</strong></div></div>
    <p v-if="document.errorStage" class="warning">异常环节：{{ document.errorStage }} · 最近更新 {{ parseTime(document.businessUpdatedTime) }}<br>{{ document.lastError || (document.errorStage === '库存入账' ? '请在实际收货批次中查看每条失败原因。' : document.nextAction) }}</p>
    <p v-if="document.cancelBlockedReason" class="muted">{{ document.cancelBlockedReason }}</p>
  </section>
</template>
<script>
export default {
  props: { document: { type: Object, required: true } },
  computed: {
    color() { return { COMPLETE: 'success', ATTENTION: 'danger', CANCELED: 'info', PROCESSING: '' }[this.document.progress] },
    metrics() { const m = [['numberExpected', '计划数量'], ['numberActually', '实收数量'], ['numberPosted', '已入账'], ['numberPending', '待入账']]; if (this.document.receiptFinal) m.push(['shortQuantity', '最终短收']); return m }
  }
}
</script>
<style scoped>.ticket-summary{margin:20px 0;padding:20px;border:1px solid #dce6f2;border-radius:8px;background:#f6f9fd}.heading{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.ticket-summary p{font-size:14px;line-height:1.8}.quantities{display:flex;gap:40px;flex-wrap:wrap;margin-top:20px}.quantities span{display:block;font-size:13px;color:#64748b}.quantities strong{display:block;font-size:24px;margin-top:8px}.warning{color:#b66b13}.muted{color:#7c8798}</style>
