<template>
 <section class="check-next-step" :class="step.tone" role="status">
  <h3>{{ step.title }}</h3>
  <p>{{ step.message }}</p>
  <div class="next-actions">
   <template v-if="step.key==='pending'">
    <el-button v-if="singleTransfer" v-hasPermi="['warehouse:transfer:query']" type="primary" size="small" :loading="saving" @click="$emit('pending',singleTransfer)">核查关联调拨</el-button>
    <el-button v-else size="small" @click="$emit('details','pending')">查看待处理单据</el-button>
    <el-button v-hasPermi="['warehouse:reconciliation:create']" size="small" @click="$emit('recheck')">处理后重新检查库存</el-button>
   </template>
   <el-button v-else-if="step.key==='adjust'" v-hasPermi="['warehouse:reconciliation:adjust']" type="primary" size="small" @click="$emit('adjust')">申请差异调整</el-button>
   <el-button v-else-if="step.key==='adjustment'" size="small" @click="$emit('details','adjustments')">查看调整申请与入账</el-button>
   <template v-else-if="step.key==='evidence'"><el-button size="small" @click="$emit('details','interactions')">查看库存查询交互</el-button><el-button v-hasPermi="['warehouse:reconciliation:create']" size="small" @click="$emit('recheck')">处理后重新检查库存</el-button></template>
   <el-button v-else v-hasPermi="['warehouse:reconciliation:create']" size="small" @click="$emit('recheck')">重新检查最新库存</el-button>
  </div>
 </section>
</template>
<script>
import { inventoryCheckNextStep, isTransferCheckDocument } from '@/utils/inventoryCheck'
export default {
 props:{document:{type:Object,required:true},canAdjust:Boolean,saving:Boolean},
 computed:{
  step(){return inventoryCheckNextStep(this.document,this.canAdjust)},
  singleTransfer(){const pending=this.document.result&&this.document.result.pending_documents||[];return pending.length===1&&isTransferCheckDocument(pending[0])?pending[0]:null}
 }
}
</script>
<style scoped>
.check-next-step{border:1px solid #d9e4f0;border-left:4px solid #408ee1;background:#f5f9ff;border-radius:8px;padding:18px 22px;margin:18px 0}.check-next-step.warning{background:#fffaf0;border-color:#efd8a8;border-left-color:#cf972d}.check-next-step.success{background:#f2faf6;border-color:#c8e4d7;border-left-color:#34845d}.check-next-step h3{font-size:17px;margin:0 0 10px;color:#34455d}.check-next-step p{font-size:13px;line-height:1.8;margin:0 0 14px;color:#65758a}.next-actions{display:flex;flex-wrap:wrap;gap:10px}.next-actions .el-button{margin:0}
</style>
