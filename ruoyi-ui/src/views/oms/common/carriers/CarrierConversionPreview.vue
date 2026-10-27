<template>
  <el-dialog title="快递场景核验" :visible.sync="visible" width="1100px" append-to-body :close-on-click-modal="false">
    <div class="scenario"><span>需要检查的业务</span><el-select v-model="scenario" @change="reset"><el-option v-if="direct" label="检查当前关联" value="CURRENT"/><el-option v-for="s in scenarios" :key="s.key" :value="s.key" :label="s.label"/></el-select></div>
    <p class="hint">{{mode.description}} 按下方具体店铺或仓库范围检查，本次结果仅适用于所选场景。</p>
    <div class="columns">
      <el-form label-width="125px"><h3>1. {{source.system_type==='OMS'?'OMS 标准快递':'物流来源'}}</h3>
        <el-form-item v-if="source.system_type==='OMS'" label="标准快递" required><carrier-select v-model="source.standard_id" standard-only/></el-form-item>
        <template v-else><carrier-scope v-model="source" :metadata="metadata" fixed-purpose/><el-form-item label="原始快递编码" required><el-input v-model="source.external_code" maxlength="128" placeholder="按原文填写，保留大小写和前导零"/></el-form-item><el-form-item v-if="source.system_type==='WMS'" label="运输产品编码"><el-input v-model="source.product_code" maxlength="64" placeholder="可选；留空按通用关联核验"/></el-form-item></template>
      </el-form>
      <el-form label-width="125px"><h3>2. 转换目标</h3><carrier-scope v-model="target" :metadata="metadata" fixed-purpose/>
        <template v-if="target.system_type==='WMS'"><p class="hint">仓库能力按连接、货主、实仓检查，请选择完整范围。</p><el-form-item v-if="target.purpose==='WMS_OUTBOUND_CREATE'" label="收货省份" required><el-select v-model="target.province" clearable placeholder="选择实际收货省份"><el-option v-for="p in metadata.provinces||[]" :key="p" :label="p" :value="p"/></el-select></el-form-item><el-form-item label="运输产品编码"><el-input v-model="target.product_code" maxlength="64" placeholder="可选；留空按通用关联核验"/></el-form-item></template>
      </el-form>
    </div>
    <el-alert ref="error" v-if="error" :title="error" type="error" :closable="false"/>
    <div v-if="result" ref="result" class="result"><div class="result-heading"><strong>本次场景检查结果</strong><span class="hint">{{checkedAt}}</span></div><p class="hint">{{scope(target)}}{{target.province?' · 收货省份 '+target.province:''}} · {{mode.label}}</p>
      <div class="check-steps"><article v-for="step in steps" :key="step.key" :class="{failed:step.available===false}"><el-tag size="small" :type="step.available===true?'success':step.available===false?'danger':'info'">{{step.available===true?'通过':step.available===false?'存在问题':'尚未检查'}}</el-tag><h4>{{step.title}}</h4><p>{{step.message}}</p><p v-if="step.data.standard_name">{{step.data.standard_name}}</p><carrier-code v-if="step.data.external_code" :value="step.data.external_code" label="快递编码"/><p v-if="step.data.scope_level" class="hint">命中{{level(step.data)}}配置 · 版本 {{step.data.revision}}</p><router-link v-if="step.available===false" :to="fixLink(step)">{{step.key==='capability'?'去配置仓库能力':step.key==='source'&&source.system_type==='OMS'?'去查看标准快递':'查看并修复关联'}}</router-link></article></div>
      <p v-if="(result.target||result.source).evidence" class="hint">配置来源与确认说明：{{(result.target||result.source).evidence}}</p>
      <el-collapse><el-collapse-item title="技术详情"><pre>{{JSON.stringify(result,null,2)}}</pre></el-collapse-item></el-collapse>
    </div>
    <span slot="footer"><el-button @click="visible=false">关闭</el-button><el-button type="primary" :loading="busy" @click="check">检查当前场景</el-button></span>
  </el-dialog>
</template>
<script>
import CarrierScope from './CarrierScope'
import CarrierSelect from '@/components/CarrierSelect'
import CarrierCode from './CarrierCode'
import { carrierPreview } from '@/api/common/carriers'
import { carrierMappingRule } from '@/utils/carrierMappingPurpose'
import { scopeName } from '@/utils/carrierManagement'
const scenarios = [
  {key:'SHIPPING',label:'销售发货回传',source:'WMS_OUTBOUND_ACTUAL',target:'CHANNEL_SHIPPING',description:'识别仓库实际发货快递，再转换为天猫发货标识。'},
  {key:'RETURN',label:'售后退回入库',source:'CHANNEL_RETURN_IDENTIFY',target:'WMS_RETURN_CREATE',description:'识别买家寄回快递，再检查仓库入库编码和收货能力。'},
  {key:'EXCHANGE',label:'换货发货回传',source:'WMS_OUTBOUND_ACTUAL',target:'CHANNEL_EXCHANGE_LOGISTICS',description:'识别换出包裹快递，再转换为天猫换货物流标识。'},
  {key:'BACKFILL',label:'寄回物流回填',source:'WMS_RETURN_ACTUAL',target:'CHANNEL_RETURN_BACKFILL',description:'识别仓库实际入库物流，再转换为平台寄回物流标识。'},
  {key:'WAREHOUSE_OUT',label:'仓库发货下发',source:'OMS',target:'WMS_OUTBOUND_CREATE',description:'检查标准快递对应的仓库出库编码、发货能力及配送省份。'},
  {key:'WAREHOUSE_RETURN',label:'仓库退回入库下发',source:'OMS',target:'WMS_RETURN_CREATE',description:'检查标准快递对应的仓库入库编码及收货能力。'}
]
const initial=(purpose,seed={})=>{
  const system=purpose==='OMS'?'OMS':purpose.startsWith('WMS_')?'WMS':'CHANNEL'
  const row={system_type:system,provider:system==='CHANNEL'?'TMALL':'',purpose,connection_id:'',shop_id:'',owner_code:'',real_store_code:'',external_code:'',product_code:'',province:''}
  if(system===seed.system_type)for(const key of ['provider','connection_id','shop_id','owner_code','real_store_code','product_code','province'])if(seed[key])row[key]=String(seed[key])
  if(purpose===seed.purpose)row.external_code=seed.external_code||''
  if(system==='OMS')row.standard_id=seed.standard_id||''
  return row
}
export default {
  components:{CarrierScope,CarrierSelect,CarrierCode},props:{metadata:{type:Object,required:true}},
  data:()=>({visible:false,busy:false,error:'',scenario:'SHIPPING',source:{},target:{},result:null,epoch:0,seed:{},scenarios,checkedAt:''}),
  computed:{direct(){return this.seed.id&&carrierMappingRule(this.seed)?.direction==='OUT'},mode(){return this.scenario==='CURRENT'?{source:'OMS',target:this.seed.purpose,label:'当前关联',description:'确认该标准快递在指定用途和适用范围内的转换结果。'}:scenarios.find(s=>s.key===this.scenario)||scenarios[0]},fingerprint(){return JSON.stringify([this.scenario,this.source,this.target])},steps(){const a=this.result.source,b=this.result.target;const rows=[{key:'source',title:this.source.system_type==='OMS'?'标准快递确认':'原始物流识别',available:a.available,message:a.message,data:a},{key:'target',title:'目标编码关联',available:b?b.mapping_available===undefined?b.available:b.mapping_available:null,message:b?b.mapping_message||b.message:'请先解决来源识别问题',data:b||{}}];if(this.target.system_type==='WMS')rows.push({key:'capability',title:this.target.purpose==='WMS_OUTBOUND_CREATE'?'仓库发货与配送能力':'仓库收货能力',available:b&&b.capability_available!==undefined?b.capability_available:null,message:b?.capability_message||'目标编码通过后检查仓库能力',data:b||{}});return rows}},
  watch:{fingerprint(){this.epoch++;this.busy=false;this.result=null;this.error=''},error(value){if(value)this.$nextTick(()=>{const panel=this.$refs.error;if(panel)panel.$el.scrollIntoView({block:'nearest'})})}},
  beforeDestroy(){this.epoch++},
  methods:{
    open(seed={}){this.seed={...seed};this.scenario=this.direct?'CURRENT':seed.purpose==='CHANNEL_RETURN_IDENTIFY'?'RETURN':seed.purpose==='WMS_RETURN_ACTUAL'?'BACKFILL':'SHIPPING';this.reset();this.visible=true},
    reset(){this.source=initial(this.mode.source,this.seed);this.target=initial(this.mode.target,this.seed);if(this.target.system_type==='WMS')this.target.scope_level='WAREHOUSE';this.result=null;this.error=''},
    scope(ctx){return scopeName(ctx,this.metadata)},level(result){return ({1:result.context?.system_type==='WMS'?'连接通用':'平台通用',2:result.context?.system_type==='WMS'?'货主':'接入应用',3:result.context?.system_type==='WMS'?'货主与实仓':'店铺'})[result.scope_level]},
    fixLink(step){if(step.key==='source'&&this.source.system_type==='OMS')return {path:'/common-config/carriers/standards',query:{keyword:step.data.standard_code||'',from_check:'1'}};const ctx=step.key==='source'?this.source:this.target;const query={...ctx,standard_id:step.data.standard_id||this.source.standard_id||undefined,from_check:'1'};if(step.key==='capability'){query.flow=ctx.purpose==='WMS_OUTBOUND_CREATE'?'OUTBOUND':'INBOUND';return {path:'/common-config/carriers/capabilities',query}}return {path:'/common-config/carriers/'+(ctx.system_type==='WMS'?'wms':'channels'),query}},
    async check(){
      if(this.busy)return
      if(this.source.system_type==='OMS'&&!this.source.standard_id){this.error='请选择需要核验的标准快递';return}
      if(this.source.system_type!=='OMS'&&!this.source.external_code){this.error='请填写原始快递编码';return}
      if([this.source,this.target].some(c=>c.system_type==='WMS'&&!c.connection_id)){this.error='请选择对应的 WMS 连接';return}
      if(this.target.system_type==='WMS'&&(!this.target.owner_code||!this.target.real_store_code)){this.error='请选择目标仓库的货主与实体仓';return}
      if(this.target.purpose==='WMS_OUTBOUND_CREATE'&&!this.target.province){this.error='请选择实际收货省份，检查仓库配送范围';return}
      const fingerprint=this.fingerprint,epoch=++this.epoch;this.busy=true;this.error='';this.result=null
      try{const result=(await carrierPreview({source:{...this.source},target:{...this.target}})).data;if(epoch!==this.epoch||fingerprint!==this.fingerprint||!this.visible)return;this.result=result;this.checkedAt=new Date().toLocaleString();this.$nextTick(()=>{const panel=this.$refs.result;if(panel)panel.scrollIntoView({block:'nearest'})});const hit=[result.source,result.target].find(r=>r&&String(r.mapping_id)===String(this.seed.id)&&String(r.revision)===String(this.seed.revision));if(hit)this.$emit('checked',{id:this.seed.id,result,available:result.source.available&&Boolean(result.target?.available),time:this.checkedAt,scope:this.scope(this.target),scenario:this.mode.label,fingerprint})}catch(e){if(epoch===this.epoch)this.error=e.message||'检查失败，请核对场景条件'}finally{if(epoch===this.epoch)this.busy=false}
    }
  }
}
</script>
<style scoped>.scenario{display:flex;align-items:center;gap:18px}.scenario .el-select{width:280px}.columns{display:grid;grid-template-columns:1fr 1fr;gap:30px;margin-top:18px}.columns h3{font-size:15px;margin:0 0 18px}.hint{color:#748399;font-size:12px;line-height:1.7}.el-select{width:100%}.result{padding:18px;background:#f5f8fc;border-radius:8px;margin-top:20px}.result-heading{display:flex;justify-content:space-between}.check-steps{display:flex;gap:12px}.check-steps article{flex:1;min-width:0;padding:14px;background:white;border:1px solid #dce5ef;border-radius:6px}.check-steps article.failed{border-color:#efb5b5}.check-steps h4{margin:12px 0 6px}.check-steps p{font-size:12px;line-height:1.7;overflow-wrap:anywhere}.check-steps a{color:#0066cc;font-size:12px}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow:auto}@media(max-width:900px){.columns{grid-template-columns:1fr}.check-steps{flex-direction:column}}</style>