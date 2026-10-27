<template>
 <div>
  <el-form-item label="退回快递识别"><el-switch :value="!!value.carrier_unverified" active-text="暂未识别，先登记原始物流" @change="toggle" /></el-form-item>
  <template v-if="value.carrier_unverified">
   <el-alert title="可先保存收回草稿；审核前须凭证确认标准快递及仓库收货能力。登记物流不增加库存。" type="info" :closable="false" />
   <el-form-item label="原始快递编码"><el-input :value="value.raw_carrier_code" maxlength="128" @input="set('raw_carrier_code',$event)" /></el-form-item>
   <el-form-item label="原始快递名称"><el-input :value="value.raw_carrier_name" maxlength="200" @input="set('raw_carrier_name',$event)" /><span class="hint">编码或名称至少保留一项，请按供应商凭证填写。</span></el-form-item>
  </template>
  <el-form-item v-else label="退回标准快递" required><purchase-return-carrier-select :value="value.standard_id" :purchase-id="purchaseId" :active="active" @input="set('standard_id',$event)" /></el-form-item>
  <el-form-item label="供应商退回运单" required><el-input :value="value.waybill" maxlength="100" @input="set('waybill',$event)" /></el-form-item>
 </div>
</template>
<script>
import PurchaseReturnCarrierSelect from './PurchaseReturnCarrierSelect'
export default {
 components: { PurchaseReturnCarrierSelect },
 props: { value: { type: Object, required: true }, purchaseId: [Number, String], active: Boolean },
 methods: { set(key, value) { this.$emit('input', { ...this.value, [key]: value }) }, toggle(value) { this.$emit('input', { ...this.value, carrier_unverified: value, standard_id: '' }) } }
}
</script>
<style scoped>.hint{color:#7a899d;font-size:12px}.el-alert{margin-bottom:16px}</style>
