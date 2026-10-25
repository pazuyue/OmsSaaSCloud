<template>
  <section v-loading="loading" class="exchange-flow-settings">
    <template v-if="form">
      <el-alert title="店铺换货自动处理" description="仅纳入本店首次启用后新建的换货单。各节点分别授权；无法明确确认的商品、来源、地址或仓库结果转人工核对。" type="info" :closable="false" show-icon />
      <el-form :disabled="readOnly||saving" label-width="160px" size="small">
        <el-form-item label="自动流程总开关"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /><div class="hint">关闭后停止新动作；已发请求继续查询，原实收入账和出库事实保留。规则修改后，在待人工单据点击“重新评估当前节点”。</div></el-form-item>
        <el-tabs v-model="tab">
          <el-tab-pane label="适用范围与商品" name="scope">
            <el-form-item label="平台换货自动审核"><el-switch v-model="form.auto_review" :active-value="1" :inactive-value="0" /></el-form-item>
            <el-form-item label="协商换货自动审核"><el-switch v-model="form.auto_internal_review" :active-value="1" :inactive-value="0" /><div class="hint">创建协商单时，还须明确勾选商家自动处理授权并填写业务授权编号。</div></el-form-item>
            <el-form-item label="未发货换品"><el-switch v-model="form.auto_unshipped" :active-value="1" :inactive-value="0" /><div class="hint">原未发需求撤回确认后，终止原商品需求；换品在原SO形成有效明细，重新自动审单、分仓及发货。</div></el-form-item>
            <el-form-item label="继续换出自动审核"><el-switch v-model="form.auto_continue_review" :active-value="1" :inactive-value="0" /><div class="hint">每轮须另行取得买家及商家授权，重新核对剩余数量和退款冲突；复用原退回，不重复入库。</div></el-form-item>
            <el-form-item label="单次最大数量"><el-input-number v-model="form.quantity_limit" :min="0" :max="1000" :precision="0" /> 件</el-form-item>
            <el-form-item label="沿用原收件信息"><el-switch v-model="form.allow_original_recipient" :active-value="1" :inactive-value="0" /><div class="hint">仅在平台未提供地址时适用；地址、姓名或电话与原收件信息不一致时仍转人工核对。</div></el-form-item>
            <h4>明确的SKU与免补差规则</h4><p class="hint">按原实际发货SKU与平台期望的完整编码或描述精确匹配。协商单以目标SKU精确匹配；不根据名称相似或价格推断。</p>
            <el-table :data="form.mappings" size="small"><el-table-column label="原实际SKU" min-width="150"><template slot-scope="s"><el-input v-model.trim="s.row.source_sku" maxlength="128" /></template></el-table-column><el-table-column label="平台目标编码 / 完整描述" min-width="200"><template slot-scope="s"><el-input v-model.trim="s.row.platform_target" maxlength="1000" /></template></el-table-column><el-table-column label="换出SKU" min-width="150"><template slot-scope="s"><el-input v-model.trim="s.row.target_sku" maxlength="128" /></template></el-table-column><el-table-column label="无需补差 / 免补差依据" min-width="240"><template slot-scope="s"><el-input v-model.trim="s.row.no_surcharge_basis" maxlength="500" placeholder="如商家授权的同款尺码调整规则编号" /></template></el-table-column><el-table-column width="65"><template slot-scope="s"><el-button type="text" @click="form.mappings.splice(s.$index,1)">删除</el-button></template></el-table-column></el-table>
            <el-button size="small" :disabled="form.mappings.length>=200" @click="form.mappings.push({source_sku:'',platform_target:'',target_sku:'',no_surcharge_basis:''})">添加明确映射</el-button>
          </el-tab-pane>
          <el-tab-pane label="平台与退回" name="returns">
            <el-form-item v-for="item in returnNodes" :key="item.key" :label="item.label"><el-switch v-model="form[item.key]" :active-value="1" :inactive-value="0" /><div class="hint">{{ item.hint }}</div></el-form-item>
            <el-form-item label="退回仓"><el-select v-model="form.return_store" filterable clearable><el-option v-for="w in warehouses" :key="w.wms_simulation_code" :label="w.wms_simulation_name+' · '+w.wms_simulation_code" :value="w.wms_simulation_code" /></el-select></el-form-item>
            <el-form-item label="平台退货地址ID"><el-input v-model.trim="form.platform_address_id" maxlength="64" placeholder="已在平台维护的contact_id" /></el-form-item>
            <el-form-item label="买家寄回地址"><el-input v-model.trim="form.return_address" maxlength="500" type="textarea" /></el-form-item><el-form-item label="寄回联系人"><el-input v-model.trim="form.return_contact" maxlength="200" /></el-form-item>
            <el-form-item label="换货合包退回"><el-switch v-model="form.auto_return_merge" :active-value="1" :inactive-value="0" /></el-form-item><el-form-item label="允许与退款共同退回"><el-switch v-model="form.auto_mixed_return_merge" :active-value="1" :inactive-value="0" :disabled="!form.auto_return_merge" /><div class="hint">退款退货规则也须启用。仅同店铺、同原平台订单、同快递运单、同接收仓及货主合包；共同THK冻结后不追加。</div></el-form-item>
            <el-form-item label="明确实收自动分配"><el-switch v-model="form.auto_allocate_receipt" :active-value="1" :inactive-value="0" /><div class="hint">共同回传按原行号或唯一SKU归属。相同SKU有多个来源、短收、超收或错货时保留原实收，等待人工逐行分配。</div></el-form-item>
            <el-form-item label="最后新增后归集等待"><el-input-number v-model="form.collect_seconds" :min="0" :max="3600" :precision="0" /> 秒<div class="hint">每次真实新增来源重新计时；重复通知不延期。通过持久化消息到期检查推进。</div></el-form-item>
          </el-tab-pane>
          <el-tab-pane label="换出与等待期限" name="shipping">
            <el-form-item v-for="item in shippingNodes" :key="item.key" :label="item.label"><el-switch v-model="form[item.key]" :active-value="1" :inactive-value="0" /><div class="hint">{{ item.hint }}</div></el-form-item>
            <el-form-item label="换出缺货等待"><el-input-number v-model="form.stock_wait_minutes" :min="1" :max="10080" :precision="0" /> 分钟</el-form-item><el-form-item label="退回等待"><el-input-number v-model="form.return_wait_days" :min="1" :max="60" :precision="0" /> 天</el-form-item><el-form-item label="仓库结果等待"><el-input-number v-model="form.warehouse_wait_minutes" :min="1" :max="1440" :precision="0" /> 分钟</el-form-item>
            <p class="hint">换出使用本店铺已保存的货主、允许仓及快递规则。逻辑预占、分仓占用、实际出库分别核对；平台已换出发货后继续等待平台换货成功。</p>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <div class="actions"><span class="hint">配置版本 {{ form.revision }} · 首次纳入时间 {{ form.enabled_after||'尚未启用' }}</span><el-button v-if="!readOnly" type="primary" :loading="saving" @click="save">保存换货自动规则</el-button></div>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<script>
import { exchangeFlowConfig, saveExchangeFlowConfig, exchangeCatalog } from '@/api/order/exchange'
export default {
  name:'ExchangeFlowSettings', props:{readOnly:Boolean},
  data:()=>({form:null,loading:false,saving:false,error:'',baseline:'',sequence:0,tab:'scope',warehouses:[],returnNodes:[{key:'auto_agree',label:'自动同意平台换货',hint:'原来源唯一、库存预占满足且平台允许时提交；失败或结果未知分别跟踪。'},{key:'auto_return_create',label:'自动创建退回安排',hint:'按审核的实际原发货来源和完整数量创建。'},{key:'auto_return_dispatch',label:'自动下发退回仓库',hint:'合包时所有成员都须授权自动下发。'},{key:'auto_qc',label:'完整良品自动验收',hint:'仅全部良品实收入账且无待核对回传时通过；差异转人工。'},{key:'auto_receive',label:'自动告知平台已收货',hint:'本地完整实收和验收通过后，平台处于待商家收货阶段才提交。'}],shippingNodes:[{key:'auto_allocate',label:'自动换出预占与分仓',hint:'实际预占与仓库分仓分别执行，缺货不缩减申请数量。'},{key:'auto_carrier',label:'自动匹配快递',hint:'使用本店快递规则，匹配不唯一转人工。'},{key:'auto_dispatch',label:'自动下发换出仓库',hint:'等待仓库受理和实际出库回传；接单不代表发货。'},{key:'auto_consign',label:'自动回传换出运单',hint:'只回传实际完整出库且包裹归属已确认的真实物流。'},{key:'auto_stop',label:'平台撤回后自动截停',hint:'平台关闭或转退款后截停未完成履约；仓库确认后释放未发库存，实收实发保留。共同THK涉及其他售后时须核对整包。'}]}),
  watch:{form:{deep:true,handler(v){this.$emit('dirty',!!v&&!!this.baseline&&JSON.stringify(v)!==this.baseline)}},saving(v){this.$emit('saving',v)}},beforeDestroy(){this.sequence++},
  methods:{async open(shop){const seq=++this.sequence;this.form=null;this.error='';this.loading=true;try{const [config,catalog]=await Promise.all([exchangeFlowConfig(shop.channel_id),exchangeCatalog()]);if(seq!==this.sequence)return;this.form=config.data;this.warehouses=catalog.data.warehouses||[];this.baseline=JSON.stringify(this.form)}catch(_){if(seq===this.sequence)this.error='换货自动规则读取失败，请重新加载'}finally{if(seq===this.sequence)this.loading=false}},async save(){if(this.readOnly||this.saving)return;this.saving=true;try{this.form=(await saveExchangeFlowConfig(this.form.channel_id,this.form)).data;this.baseline=JSON.stringify(this.form);this.$emit('dirty',false);this.$emit('changed');this.$message.success('换货自动规则已保存')}finally{this.saving=false}}}
}
</script>
<style scoped>.exchange-flow-settings{max-width:1300px}.el-alert{margin-bottom:20px}.hint{color:#7a8a9d;font-size:12px;line-height:1.8}.el-input,.el-select{max-width:600px}.el-table .el-input{width:100%}.actions{display:flex;justify-content:space-between;align-items:center;margin-top:24px;border-top:1px solid #e8edf4;padding-top:20px}.el-table{margin-bottom:16px}</style>
