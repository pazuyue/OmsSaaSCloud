<template>
  <section class="return-rule-editor" v-loading="loading">
    <template v-if="form">
      <div class="editor-heading"><div><strong>{{ shop.channel_name }}</strong><p class="hint">天猫 · 渠道 {{ shop.channel_id }} · 版本 {{ form.revision }}</p></div><el-tag size="small" type="info">{{ readOnly || !editable ? '只读查看' : form.revision ? '已保存配置' : '系统默认规则' }}</el-tag></div>
      <div class="rule-flow"><template v-for="(step,index) in flow"><i v-if="index" :key="'arrow'+index" class="el-icon-arrow-right"/><span :key="step">{{ step }}</span></template></div>
      <el-alert v-if="validation" :title="validation.message" type="error" show-icon :closable="false"/>
      <el-form ref="form" label-width="130px" size="small" :disabled="busy||readOnly||!editable" class="config-form">
        <el-tabs v-model="activeTab" class="rule-tabs">
          <el-tab-pane label="退回安排" name="arrangements">
            <h3>先确定如何创建退回安排</h3>
            <el-form-item label="创建方式"><el-radio-group v-model="form.auto_create"><el-radio :label="0">人工创建</el-radio><el-radio :label="1">审核后自动创建</el-radio></el-radio-group></el-form-item>
            <p v-if="Number(form.auto_create)" class="section-note">退货退款审核通过并选择“退回”后，按明确的原发货来源和平台申请数量创建。全部原福袋且出库来源明确时按原袋展开；部分福袋需人工选原袋，固定套装、多来源或数量冲突转人工核对。</p>
            <p v-else class="section-note">由业务人员确认发货来源和退回数量后创建。创建后的安排仍可按设置自动下发仓库。</p>
            <div class="section-heading"><h3>默认退回资料</h3><span class="hint">{{ Number(form.auto_create) ? '自动创建时必填' : '可选，供后续自动创建使用' }}</span></div>
            <el-form-item label="默认退回仓" :required="!!Number(form.auto_create)" :error="fieldError('create_store_code')"><el-select v-model="form.create_store_code" clearable filterable placeholder="请选择默认退回仓"><el-option v-for="w in warehouses" :key="w.store_code" :value="w.store_code" :label="w.store_name+' · '+w.store_code" :disabled="!Number(w.usable)"/></el-select></el-form-item>
            <el-form-item label="退回地址" :required="!!Number(form.auto_create)" :error="fieldError('create_return_address')"><el-input v-model.trim="form.create_return_address" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="填写退回接收地址"/></el-form-item>
            <el-form-item label="退回联系人" :required="!!Number(form.auto_create)" :error="fieldError('create_return_contact')"><el-input v-model.trim="form.create_return_contact" maxlength="200" placeholder="姓名及联系方式"/></el-form-item>
            <p class="hint">创建安排不会增加库存；赠品按各自已确认的退回安排处理。</p>
          </el-tab-pane>
          <el-tab-pane label="仓库下发" name="dispatch">
            <h3>安排确认后，如何下发仓库</h3>
            <el-form-item label="下发方式"><el-radio-group v-model="form.auto_dispatch"><el-radio :label="0">人工下发</el-radio><el-radio :label="1">自动下发</el-radio></el-radio-group></el-form-item>
            <div class="section-note">{{ dispatchHint }}</div>
            <template v-if="Number(form.auto_dispatch)">
              <el-form-item label="适用退回仓" :error="fieldError('store_codes')"><el-select v-model="form.store_codes" multiple filterable clearable placeholder="未指定时使用默认退回仓"><el-option v-for="w in warehouses" :key="w.store_code" :value="w.store_code" :label="w.store_name+' · '+w.store_code" :disabled="!Number(w.usable)"/></el-select><div class="hint">{{ storeSummary }}</div></el-form-item>
              <p class="hint">已审核、来源数量明确且仓库必填资料齐全后才会下发。缺少资料或范围冲突会显示具体原因；下发失败可重新检查原单，人工停止的安排保持停止。</p>
              <el-collapse><el-collapse-item title="查看适用仓库能力" name="warehouses"><el-table :data="selectedWarehouses" size="small" empty-text="尚未指定适用退回仓"><el-table-column prop="store_name" label="退回仓"/><el-table-column label="退货入库"><template slot-scope="s">{{ Number(s.row.usable)?'可用':'不可用，请核对仓库配置' }}</template></el-table-column></el-table></el-collapse-item></el-collapse>
            </template>
          </el-tab-pane>
          <el-tab-pane label="合包规则" name="merge">
            <h3>是否将多份退回安排合并下发</h3>
            <el-form-item label="合包方式"><el-radio-group v-model="form.merge_mode" @change="normalize"><el-radio label="NONE">不合包</el-radio><el-radio label="MANUAL">人工合包</el-radio><el-radio label="AUTO">自动合包</el-radio></el-radio-group></el-form-item>
            <div v-if="form.merge_mode==='NONE'" class="section-note">每份退回安排独立处理，不等待其他退款。是否自动下发由“仓库下发”页单独控制。</div>
            <template v-else>
              <div class="section-note">{{ form.merge_mode==='AUTO'?'按同一原平台订单、可信运单和退回仓自动归集；归集结束后按下发方式处理，单份安排也可下发。':'人工核实实际包裹并确认关联范围后，再按下发方式处理。' }}</div>
              <template v-if="form.merge_mode==='AUTO'">
                <el-form-item label="可信物流来源" :error="fieldError('logistics_sources')"><el-checkbox-group v-model="form.logistics_sources"><el-checkbox label="PLATFORM">平台同步物流</el-checkbox><el-checkbox label="MANUAL">人工核实物流</el-checkbox></el-checkbox-group></el-form-item>
                <el-form-item label="归集等待"><el-input-number v-model="form.collect_seconds" :min="0" :max="3600" :precision="0"/> 秒<div class="hint">从最后一次新增安排起等待；0 表示不等待。</div></el-form-item>
                <el-form-item v-if="!Number(form.auto_dispatch)" label="适用退回仓" :error="fieldError('store_codes')"><el-select v-model="form.store_codes" multiple filterable clearable placeholder="未指定时使用默认退回仓"><el-option v-for="w in warehouses" :key="w.store_code" :value="w.store_code" :label="w.store_name+' · '+w.store_code" :disabled="!Number(w.usable)"/></el-select><div class="hint">{{ storeSummary }}</div></el-form-item>
                <p v-else class="hint">适用退回仓沿用“仓库下发”页设置。自动归集仅限同一原平台订单。</p>
              </template>
              <el-collapse><el-collapse-item title="合包范围与数量限制" name="limits">
                <el-form-item label="人工关联范围"><el-radio-group v-model="form.scope"><el-radio label="SAME_ORDER">同一原平台订单</el-radio><el-radio label="CROSS_ORDER_MANUAL">同店铺跨订单，人工核实同一买家</el-radio></el-radio-group></el-form-item>
                <el-form-item label="单包最大退款数"><el-input-number v-model="form.max_refunds" :min="1" :max="100" :precision="0"/> 笔</el-form-item>
                <el-form-item label="单包最大件数"><el-input-number v-model="form.max_quantity" :min="0" :max="100000000" :precision="0"/><div class="hint">0 表示不限制，原发货来源额度仍须校验。</div></el-form-item>
                <p class="hint">共同仓库单生成后固定关联范围；新增或实收归属冲突转人工核对。</p>
              </el-collapse-item></el-collapse>
            </template>
          </el-tab-pane>
          <el-tab-pane label="实收处理" name="receipt">
            <h3>仓库实收后，如何确认归属</h3>
            <el-form-item label="分配方式"><el-radio-group v-model="form.allocation_mode" @change="allocationModeChanged"><el-radio label="MANUAL">人工确认</el-radio><el-radio label="STRICT">严格自动匹配</el-radio><el-radio label="RULE">按规则自动分配</el-radio></el-radio-group></el-form-item>
            <div class="section-note">{{ receiptHint }}</div>
            <template v-if="form.allocation_mode==='RULE'">
              <el-form-item label="部分实收分配"><el-switch v-model="form.allow_partial_allocation" :active-value="1" :inactive-value="0"/><div class="hint">同 SKU、品质、原发货来源、接收仓及成本一致时，允许分配尚未收齐的数量。</div></el-form-item>
              <el-form-item label="分配顺序">退款申请时间从早到晚<div class="hint">同时间按退款单及来源顺序；缺少平台申请时间时使用 OMS 创建时间。</div></el-form-item>
              <el-collapse v-if="Number(form.allow_partial_allocation)"><el-collapse-item title="查看部分实收示例" name="example"><p class="hint">三张退款计划 2＋1＋1 件，实收 3 件时可分配 2＋1＋0 件；已收数量按来源入账，剩余数量继续跟进。</p></el-collapse-item></el-collapse>
            </template>
            <p class="hint">物流签收与仓库实收分别跟踪。错货、超收和来源冲突转人工，不自动关闭退款。</p>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <div class="rule-actions"><span class="hint">保存后影响后续动作，保留已有单据和入账结果。</span><div><el-button :disabled="busy" @click="open(shop)">重新加载</el-button><el-button v-if="!readOnly&&editable" type="primary" :loading="saving" :disabled="loading" @click="save">保存退货规则</el-button></div></div>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
  </section>
</template>
<script>
import { refundParcelConfig, saveRefundParcelConfig } from '@/api/order/refund'
import { mergeMode, applyMergeMode, dispatchStores, ruleFlow, ruleValidation } from './returnRulePresentation'
export default {
  name: 'RefundParcelSettings', props: { readOnly: Boolean },
  data: () => ({ error: '', editable: true, loading: false, saving: false, shop: {}, form: null, warehouses: [], sequence: 0, activeTab: 'arrangements', validation: null }),
  computed: {
    busy() { return this.loading || this.saving },
    selectedWarehouses() { if (!this.form) return []; const codes = new Set(Number(this.form.auto_dispatch) || Number(this.form.auto_enabled) ? dispatchStores(this.form) : []); if (Number(this.form.auto_create)) codes.add(this.form.create_store_code); return this.warehouses.filter(w => codes.has(w.store_code)) },
    flow() { return this.form ? ruleFlow(this.form) : [] },
    summary() { return this.flow.join(' → ') },
    storeSummary() { const codes = dispatchStores(this.form || {}); return codes.length ? '当前适用：' + codes.map(code => { const w = this.warehouses.find(row => row.store_code === code); return w ? w.store_name || code : code }).join('、') : '尚未指定适用退回仓' },
    dispatchHint() { if (!Number(this.form.auto_dispatch)) return '安排确认后由业务人员手动下发。创建方式和合包方式可以单独选择。'; return ({ NONE: '退回安排资料就绪后独立下发，不等待其他退款或合包。', MANUAL: '人工确认实际包裹范围后，后台自动下发仓库。', AUTO: '自动合包归集结束且范围无冲突后，后台统一下发仓库。' })[this.form.merge_mode] },
    receiptHint() { return ({ MANUAL: '仓库回传后，由业务人员确认各退款的实收归属。', STRICT: '按明确的商品来源严格匹配；无法唯一确定时转人工核对。', RULE: '优先匹配明确来源，其余满足条件的同 SKU 实收按退款申请时间分配。' })[this.form.allocation_mode] }
  },
  watch: { busy(value) { this.$emit('busy', value) }, saving(value) { this.$emit('saving', value) } },
  beforeDestroy() { this.sequence++ },
  methods: {
    fieldError(field) { return this.validation && this.validation.field === field ? this.validation.message : '' },
    allocationModeChanged() { if (this.form.allocation_mode !== 'RULE') this.form.allow_partial_allocation = 0 },
    async open(shop) { const seq = ++this.sequence; this.shop = shop; this.form = null; this.error = ''; this.validation = null; this.activeTab = 'arrangements'; this.loading = true; try { const r = (await refundParcelConfig(shop.channel_id)).data; if (seq === this.sequence) { this.form = { auto_create: 0, auto_dispatch: 0, create_store_code: '', create_return_address: '', create_return_contact: '', ...r.policy, merge_mode: mergeMode(r.policy), allow_append: 0, auto_append: 0, auto_allocate: 0 }; this.warehouses = r.warehouses; this.editable = r.editable !== false } } catch (_) { if (seq === this.sequence) this.error = '退货规则加载失败，请重新加载' } finally { if (seq === this.sequence) this.loading = false } },
    normalize() { applyMergeMode(this.form, this.form.merge_mode || mergeMode(this.form)); this.validation = null },
    usable(w) { return Number(w.usable) },
    async save() { if (this.saving || this.readOnly || !this.editable || !this.form) return; this.validation = ruleValidation(this.form, this.warehouses); if (this.validation) { this.activeTab = this.validation.tab; return }; const tab = this.activeTab; this.saving = true; try { await saveRefundParcelConfig(this.shop.channel_id, { ...this.form, store_codes: dispatchStores(this.form) }); this.$message.success('本店铺退货规则已保存'); this.$emit('changed', this.shop.channel_id); await this.open(this.shop); this.activeTab = tab } finally { this.saving = false } }
  }
}
</script>
<style scoped>
.return-rule-editor{background:#fff;padding:20px 24px;min-height:580px}.editor-heading,.section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.editor-heading strong{font-size:16px;color:#30445d}.hint{color:#758397;font-size:12px;line-height:1.8;margin:5px 0}.rule-flow{display:flex;gap:12px;align-items:center;flex-wrap:wrap;background:#f5f8fc;padding:14px 16px;margin:16px 0;color:#536982;font-size:12px;border-radius:6px}.rule-flow i{color:#a5b0bf}.rule-tabs{min-height:410px}.config-form .el-select{width:100%}.section-note{padding:14px 16px;background:#f7f9fc;border-radius:6px;line-height:1.8;font-size:13px;color:#61728a;margin:0 0 24px}.config-form h3{font-size:14px;font-weight:500;color:#30445d;margin:22px 0 20px}.section-heading h3{margin:20px 0}.config-form .el-radio{margin-right:20px;line-height:2}.config-form .el-collapse{border:0;margin:16px 0}.rule-actions{position:sticky;bottom:0;display:flex;align-items:center;justify-content:space-between;gap:12px;background:#fff;padding:16px 0 0;border-top:1px solid #e5ebf2;margin-top:24px}.rule-actions>div{white-space:nowrap}.rule-actions .el-button{margin-left:8px}.el-alert{margin:12px 0}@media(max-width:760px){.return-rule-editor{padding:12px}.rule-flow{gap:8px}.rule-actions{align-items:flex-end}.rule-actions>.hint{max-width:40%}}
</style>
