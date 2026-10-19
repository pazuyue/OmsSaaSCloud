<template>
 <section class="return-rule-editor" v-loading="loading">
  <div v-if="form"><p><b>{{ shop.channel_name }}</b> · 天猫 · 渠道 {{ shop.channel_id }} · 配置版本 {{ form.revision }} <el-tag size="mini" :type="form.revision?'success':'info'">{{ form.revision?'已保存配置':'使用系统默认规则' }}</el-tag><el-tag v-if="readOnly||!editable" size="mini" type="info">只读查看</el-tag></p>
   <el-alert title="多笔退款共用一个实物包裹；TK独立，共用一张THK与仓库交互。共同THK生成后冻结关联范围，物流签收不等于实收入账。" type="info" :closable="false" show-icon/>
   <el-form label-width="170px" size="small" :disabled="saving||readOnly||!editable" class="config-form">
    <h3>1. 包裹关联</h3>
    <el-form-item label="允许合包退回"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" @change="normalize"/></el-form-item>
    <el-form-item label="允许人工关联"><el-switch v-model="form.manual_enabled" :active-value="1" :inactive-value="0" :disabled="!form.enabled"/></el-form-item>
    <el-form-item label="合包范围"><el-radio-group v-model="form.scope" :disabled="!form.enabled"><el-radio label="SAME_ORDER">同一原平台订单</el-radio><el-radio label="CROSS_ORDER_MANUAL">同店铺跨订单，仅人工核实同一买家</el-radio></el-radio-group></el-form-item>

    <el-form-item label="单包最大退款数"><el-input-number v-model="form.max_refunds" :min="1" :max="100" :precision="0"/> 笔</el-form-item>
    <el-form-item label="单包最大计划件数"><el-input-number v-model="form.max_quantity" :min="0" :max="100000000" :precision="0"/><span class="hint"> 0表示不设业务上限，来源额度校验始终保留。</span></el-form-item>
    <h4>自动合包</h4>
    <el-form-item label="开启自动合包"><el-switch v-model="form.auto_enabled" :active-value="1" :inactive-value="0" :disabled="!form.enabled" @change="normalize"/><div class="hint">平台物流到达、审核通过或退回安排就绪后检查；后台补偿漏处理任务。自动关联固定限同一原平台订单。</div></el-form-item>
    <el-form-item label="可信物流来源"><el-checkbox-group v-model="form.logistics_sources"><el-checkbox label="PLATFORM">平台同步物流</el-checkbox><el-checkbox label="MANUAL">人工核实登记物流</el-checkbox></el-checkbox-group></el-form-item>
    <el-form-item label="自动处理退回仓"><el-select v-model="form.store_codes" multiple filterable style="width:100%" placeholder="开启自动合包须选择退回仓"><el-option v-for="w in warehouses" :key="w.store_code" :value="w.store_code" :label="w.store_name+' · '+w.store_code" :disabled="!Number(w.usable)"/></el-select></el-form-item>

    <h3>2. 仓库下发</h3>
    <el-form-item label="关联后自动下发"><el-switch v-model="form.auto_dispatch" :active-value="1" :inactive-value="0" :disabled="!form.auto_enabled"/><div class="hint">关闭时只关联包裹，仍需人工下发。开启仅下发已审核并明确来源数量的退回安排。</div></el-form-item>
    <el-form-item label="合包归集等待"><el-input-number v-model="form.collect_seconds" :min="0" :max="3600" :precision="0"/> 秒<div class="hint">最后一次新增来源后重新等待，窗口结束统一下发。0为不等待，可人工立即下发。</div></el-form-item>
    <h3>3. 实收分配</h3>
    <el-form-item label="分配方式"><el-radio-group v-model="form.allocation_mode" @change="allocationModeChanged"><el-radio label="MANUAL">人工确认</el-radio><el-radio label="STRICT">严格自动匹配</el-radio><el-radio label="RULE">按规则自动分配</el-radio></el-radio-group></el-form-item>
    <el-form-item label="部分实收自动分配"><el-switch v-model="form.allow_partial_allocation" :active-value="1" :inactive-value="0" :disabled="form.allocation_mode!=='RULE'"/><div class="hint">开启后，同SKU同品质且原发货来源、接收仓及成本一致时，允许按规则分配尚未收齐的数量。明确行号优先，错误行号不降级猜测。</div></el-form-item>
    <el-form-item label="分配顺序">退款申请时间从早到晚<div class="hint">同时间按TK及来源顺序；平台申请时间缺失时使用OMS退款创建时间并留痕。与自动合包、自动下发开关独立。</div></el-form-item>
    <el-alert v-if="form.allocation_mode==='RULE'&&form.allow_partial_allocation" title="示例：三张TK计划2＋1＋1件，仓库回传3件，可自动分配2＋1＋0件；已收3件按原来源入账，剩余1件继续跟进。" type="info" :closable="false" show-icon/>
    <p class="hint">错货、超收、截单或来源冲突转人工；自动分配不自动关闭退款，不执行平台付款。配置不重算已入账结果，已有待人工回传可在包裹详情按当前规则预览处理。</p>
   </el-form>
   <el-alert :title="summary" :type="form.enabled?'success':'info'" :closable="false" show-icon/>
   <h3>仓库能力与生效检查</h3><el-table :data="selectedWarehouses" size="small" empty-text="尚未选择自动处理退回仓"><el-table-column label="退回仓" min-width="170"><template slot-scope="s">{{ s.row.store_name }}<div class="hint">{{ s.row.store_code }}</div></template></el-table-column><el-table-column prop="connection_name" label="仓库连接" min-width="180"/><el-table-column label="生效情况" min-width="170"><template slot-scope="s"><span :class="usable(s.row)?'':'warning'">{{ usable(s.row)?'满足当前配置':'被仓库能力或启用状态限制' }}</span></template></el-table-column></el-table>
   <p class="hint">配置仅影响后续动作，不改写已有包裹、仓库单及入账记录；同公司、店铺、退回仓和货主一致是固定规则。</p>
  </div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><div class="rule-actions"><el-button :disabled="loading||saving" @click="open(shop)">重新加载</el-button><el-button v-if="!readOnly&&editable" type="primary" :disabled="loading||!form" :loading="saving" @click="save">保存退货规则</el-button></div>
 </section>
</template>
<script>
import { refundParcelConfig,saveRefundParcelConfig } from '@/api/order/refund'
export default {name:'RefundParcelSettings',props:{readOnly:Boolean},data:()=>({error:'',editable:true,loading:false,saving:false,shop:{},form:null,warehouses:[],sequence:0}),computed:{busy(){return this.loading||this.saving},selectedWarehouses(){return this.warehouses.filter(w=>this.form&&this.form.store_codes.includes(w.store_code))},summary(){if(!this.form)return '';if(!this.form.enabled)return '合包退回未开启，不新增合包关联；已有包裹与单据继续查询、接收回传和核对。';return `允许${this.form.scope==='SAME_ORDER'?'同一原平台订单':'同店铺跨订单人工核实'}合包；${this.form.auto_enabled?'自动关联已开启':'自动关联未开启'}，${this.form.auto_append?'允许自动追加':'不自动追加'}；${this.form.auto_dispatch?'归集后统一下发THK':'关联后等待人工下发'}。${this.form.allocation_mode==='MANUAL'?'实收人工确认':this.form.allocation_mode==='RULE'&&this.form.allow_partial_allocation?'允许同SKU部分实收按规则分配':'实收严格自动匹配'}。`}},watch:{busy(value){this.$emit('busy',value)},saving(value){this.$emit('saving',value)}},beforeDestroy(){this.sequence++},methods:{allocationModeChanged(){if(this.form.allocation_mode!=='RULE')this.form.allow_partial_allocation=0},async open(shop){const seq=++this.sequence;this.shop=shop;this.form=null;this.error='';this.loading=true;try{const r=(await refundParcelConfig(shop.channel_id)).data;if(seq===this.sequence){this.form={...r.policy,allow_append:0,auto_append:0,auto_allocate:0};this.warehouses=r.warehouses;this.editable=r.editable!==false}}catch(_){if(seq===this.sequence)this.error='退货规则加载失败，请重新加载'}finally{if(seq===this.sequence)this.loading=false}},normalize(){if(!this.form.enabled){this.form.auto_enabled=0;this.form.auto_allocate=0}if(!this.form.auto_enabled){this.form.auto_append=0;this.form.auto_dispatch=0}if(!this.form.allow_append)this.form.auto_append=0},usable(w){return Number(w.usable)},async save(){if(this.saving||this.readOnly||!this.editable||!this.form)return;if(this.form.auto_enabled&&(!this.form.store_codes.length||!this.form.logistics_sources.length))return this.$message.warning('请选择自动处理退回仓和可信物流来源');if(this.selectedWarehouses.some(w=>!this.usable(w)))return this.$message.warning('所选退回仓不满足仓库能力要求，请先核实仓库连接配置');this.saving=true;try{await saveRefundParcelConfig(this.shop.channel_id,this.form);this.$message.success('本店铺退货规则已保存');this.$emit('changed',this.shop.channel_id);await this.open(this.shop)}finally{this.saving=false}}}}
</script>
<style scoped>.config-form{margin-top:20px}.hint{color:#8290a4;font-size:12px;line-height:1.8}.warning{color:#b77515}h3{margin:24px 0 16px}.return-rule-editor{min-height:180px;background:#fff;padding:20px 24px;border:1px solid #e1e8f0;border-radius:8px}.rule-actions{position:sticky;bottom:0;display:flex;justify-content:flex-end;gap:10px;background:#fff;padding:16px 0 0;border-top:1px solid #e1e8f0;margin-top:24px}.rule-actions .el-button{margin:0}h4{margin:20px 0 12px}
</style>
