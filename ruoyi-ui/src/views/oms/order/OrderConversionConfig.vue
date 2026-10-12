<template>
  <el-dialog title="店铺转单设置" :visible.sync="visible" width="min(740px, 94vw)" append-to-body :close-on-click-modal="false">
    <p>{{ form.channel_name }} · {{ form.channel_id }}</p>
    <el-form ref="form" :model="form" :rules="rules" label-width="150px">
        <el-form-item label="自动转单"><el-switch v-model="form.auto_convert" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="允许交易类型" prop="allowed_trade_types"><el-select v-model="form.allowed_trade_types" multiple filterable allow-create default-first-option :multiple-limit="20" placeholder="选择或输入平台交易类型编码"><el-option v-for="type in ['fixed', 'guarantee_trade', 'tmall_i18n']" :key="type" :label="type" :value="type" /></el-select><p class="muted">可录入其他类型编码，保存后用于后续转单尝试。仍需已付款待发货，已生成业务订单不受此项变更影响。</p></el-form-item><el-form-item label="审单方式"><el-radio-group v-model="form.review_mode"><el-radio label="AUTO">自动审核</el-radio><el-radio label="MANUAL">人工审核</el-radio></el-radio-group><p class="muted">仅影响新生成的业务订单；库存不足不阻止审核，后续分仓按店铺是否允许部分发货校验逻辑预占。</p></el-form-item><el-form-item label="人工匹配需复核"><el-switch v-model="form.manual_match_review" :active-value="1" :inactive-value="0" /><p class="muted">开启后，人工匹配过商品的订单转入待审核，即使选择了自动审核。</p></el-form-item><el-form-item label="库存逻辑预占策略"><span>优先渠道锁库，同订单同 SKU 足量逻辑预占</span></el-form-item><el-form-item label="允许公共库存补占"><el-switch v-model="form.allow_public_stock" :active-value="1" :inactive-value="0" /><p class="muted">优先使用本店铺渠道的锁库库存。开启后，不足部分可补占公司公共正品库存；关闭时仅使用渠道锁库库存。设置用于后续逻辑预占尝试，不改变已逻辑预占订单。</p></el-form-item><p class="muted">同订单中相同 SKU 的数量合并判断，足量才逻辑预占；不足时保留完整需求，不阻塞库存足够的小单。此处不确定发货仓库。</p>
    </el-form>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存转单设置</el-button></span>
  </el-dialog>
</template>
<script>
import { saveConversionConfig } from '@/api/order'
export default {
  data() { return { visible: false, saving: false, form: {}, rules: { allowed_trade_types: [{ required: true, message: '请选择允许转单的交易类型', trigger: 'change' }] } } },
  methods: {
    open(row) { this.form = { ...row, allowed_trade_types: [...row.allowed_trade_types] }; this.visible = true; this.$nextTick(() => this.$refs.form.clearValidate()) },
    save() { this.$refs.form.validate(async valid => { if (!valid || this.saving) return; this.saving = true; try { await saveConversionConfig(this.form); this.visible = false; this.$message.success('转单设置已保存'); this.$emit('changed') } finally { this.saving = false } }) }
  }
}
</script>
<style scoped>
.muted{font-size:13px;color:#8490a2;margin:6px 0 0}.el-select{width:100%}
</style>
