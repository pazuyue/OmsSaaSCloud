<template>
  <el-form v-if="value" label-width="200px" size="small" :disabled="disabled" class="automation-form">
    <el-alert title="本页总开关只控制平台与业务自动处理；退款同步、退货入库按各自页签设置独立运行。" type="info" :closable="false" show-icon />
    <el-form-item label="启用自动处理"><el-switch :value="value.enabled" :active-value="1" :inactive-value="0" @input="set('enabled', $event)" /></el-form-item>
    <h3>自动确认退款方案</h3>
    <el-form-item label="退货退款自动确认方案"><el-switch :value="value.auto_return_review" :active-value="1" :inactive-value="0" @input="set('auto_return_review', $event)" /><p class="hint">仅明确已全部出库、原子单实付及平台退货数量可核对时，自动采用退回方案；支持普通商品的明确申请数量，以及整套、整袋原实际组件。部分套袋、多个发货来源或额度冲突转人工。自动确认会预占原退货来源，仓库实收入库后才退款。</p></el-form-item>
    <el-form-item v-if="Number(value.auto_return_review)" label="单退款自动确认上限" required><el-input-number :value="value.return_review_limit" :min="0.01" :max="999999999" :precision="2" @input="set('return_review_limit', $event)" /> 元<p class="hint">同时检查该子单累计已退、其他已审核占用和剩余可退额度；此上限独立于付款上限。须开启自动创建退回安排并配置默认退回仓及地址。</p></el-form-item>
    <el-form-item label="采用明确差价授权"><el-switch :value="value.auto_difference" :active-value="1" :inactive-value="0" @input="set('auto_difference', $event)" /><p class="hint">仅采用事先登记、仍有效且店铺、主单、子单、退款号及金额完全一致的差价业务授权。不会凭原因文字或小额金额认定差价；授权登记不代表已审核退款或已付款。</p><refund-money-authorizations v-if="Number(value.auto_difference) && value.channel_id" :channel-id="value.channel_id" :disabled="disabled" /></el-form-item>
    <el-form-item label="小额退款免退货"><el-switch :value="value.small_refund_enabled" :active-value="1" :inactive-value="0" @input="set('small_refund_enabled', $event)" /><p class="hint">商家明确允许小额申请继续履约、免退货；优先检查此规则，不会据此标记为退差价。按原平台子订单累计已退和已审核占用金额，并发申请共同占用限额。</p></el-form-item>
    <el-form-item v-if="Number(value.small_refund_enabled)" label="子订单累计免退上限" required><el-input-number :value="value.small_refund_limit" :min="0.01" :max="999999999" :precision="2" @input="set('small_refund_limit', $event)" /> 元<p class="hint">例如上限20元，先退10元后再申请15元，累计25元转人工；此限额独立于下方单退款付款上限。</p></el-form-item>
    <el-form-item label="未发全额退款按取消购买"><el-switch :value="value.auto_full_cancel" :active-value="1" :inactive-value="0" @input="set('auto_full_cancel', $event)" /><p class="hint">仅实际全部未出库、原子单商品实付明确、扣除已确认退款后恰好全额，且没有其他待处理退款时适用。已确认继续发货的方案优先，不凭关键词判断差价。</p></el-form-item>
    <el-form-item label="已发剩余全额自动追回"><el-switch :value="value.auto_shipped_cancel" :active-value="1" :inactive-value="0" @input="set('auto_shipped_cancel', $event)" /><p class="hint">普通商品已发或部分发货，申请金额恰好等于扣除前次纯资金退款后的剩余实付、申请数量一致时，自动取消未发部分，并按实际来源追回已发部分。平台明确已收货走退回，未收货走物流拦截；不明状态及套装、福袋范围转人工。须配置自动创建退回安排及退回资料；在途场景还须开启自动申请拦截。</p></el-form-item>
    <h3>物流与货物结案</h3>
    <el-form-item label="自动回告取消发货"><el-switch :value="value.auto_cancel_feedback" :active-value="1" :inactive-value="0" @input="set('auto_cancel_feedback', $event)" /><p class="hint">本子单全部取消并确认后才回告平台；部分截停不会报为全部取消成功。</p></el-form-item>
    <el-form-item label="自动申请在途拦截"><el-switch :value="value.auto_intercept" :active-value="1" :inactive-value="0" @input="set('auto_intercept', $event)" /><p class="hint">仅原包裹全部商品均在本退款拦截范围内时提交；共用包裹范围不完整转人工核对。</p></el-form-item>
    <el-form-item label="无差异实收入账自动结案"><el-switch :value="value.auto_finish_return" :active-value="1" :inactive-value="0" @input="set('auto_finish_return', $event)" /><p class="hint">购买商品和应追回赠品全部实际收到、完成库存入账，且没有缺件、免退或待处理仓库差异后，自动完成货物结案，再继续平台审核与付款。退款成功、拦截受理均不能代替真实入库。</p></el-form-item>
    <h3>平台退货与退款审核</h3>
    <el-form-item label="自动同意平台退货"><el-switch :value="value.auto_agree_return" :active-value="1" :inactive-value="0" @input="set('auto_agree_return', $event)" /><p class="hint">本地方案审核通过，且平台允许本次退货时执行。</p></el-form-item>
    <el-form-item label="平台退回地址" :required="!!Number(value.auto_agree_return)"><el-select :value="value.seller_address_id" filterable clearable placeholder="选择告知平台及买家的寄回地址" @input="set('seller_address_id', $event)"><el-option v-for="address in addresses" :key="address.value" :value="address.value" :label="address.label" /></el-select><el-button type="text" :loading="addressLoading" @click="$emit('addresses')">从平台取得地址</el-button><p class="hint">此地址用于告知买家寄回；仓库接单与库存入账使用“退货与入库”配置，请核对两处资料一致。</p></el-form-item>
    <el-form-item label="自动审核平台退款"><el-switch :value="value.auto_review" :active-value="1" :inactive-value="0" @input="set('auto_review', $event)" /><p class="hint">货物处理完成，或已确认继续履约后执行平台审核；最终资金结果另行核对。</p></el-form-item>
    <h3>退款付款</h3>
    <el-form-item label="付款执行方"><el-radio-group :value="value.funds_owner" @input="ownerChanged"><el-radio label="PLATFORM">平台处理，OMS核对结果</el-radio><el-radio label="OMS">OMS提交付款</el-radio></el-radio-group></el-form-item>
    <template v-if="value.funds_owner==='OMS'"><el-form-item label="自动提交退款付款"><el-switch :value="value.auto_pay" :active-value="1" :inactive-value="0" @input="set('auto_pay', $event)" /><p class="hint">付款权限、子账号授权、平台免验证码、自动审核及金额上限同时满足后执行。</p></el-form-item><el-form-item label="单退款自动付款上限"><el-input-number :value="value.payment_limit" :min="0" :max="999999999" :precision="2" @input="set('payment_limit', $event)" /> 元</el-form-item></template>
  </el-form>
</template>
<script>
import RefundMoneyAuthorizations from './RefundMoneyAuthorizations'
export default {
  name: 'RefundFlowSettings',
  components: { RefundMoneyAuthorizations },
  props: { value: { type: Object, required: true }, disabled: Boolean, addresses: { type: Array, default: () => [] }, addressLoading: Boolean },
  methods: { set(field, value) { this.$emit('input', { ...this.value, [field]: value }) }, ownerChanged(owner) { this.$emit('input', { ...this.value, funds_owner: owner, auto_pay: owner === 'OMS' ? this.value.auto_pay : 0 }) } }
}
</script>
<style scoped>
.automation-form .el-alert{margin-bottom:24px}.automation-form h3{font-size:14px;border-top:1px solid #e8edf3;margin:24px 0;padding-top:18px;color:#30445d}.hint{font-size:12px;color:#758397;line-height:1.8;margin:5px 0}.automation-form .el-select{width:min(100%,600px)}
</style>
