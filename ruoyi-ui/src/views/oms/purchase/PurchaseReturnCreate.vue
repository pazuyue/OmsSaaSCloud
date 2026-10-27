<template>
 <el-dialog title="新增采购退仓" :visible="visible" width="92%" top="4vh" append-to-body :close-on-click-modal="false" @close="$emit('update:visible',false)">
  <div class="return-create">
   <el-alert title="按原入库来源退回供应商。保存草稿不占库存，审核后锁定原批次并自动执行退仓。" type="info" :closable="false"/>
   <section><h3><span>1</span> 原采购与入库来源</h3>
    <el-form inline size="small" label-position="top">
     <el-form-item label="原采购单" required><el-select v-model="selectedPurchase" filterable remote clearable :remote-method="findPurchases" :loading="purchaseLoading" placeholder="搜索采购单号或采购名称" class="purchase-picker" @change="purchaseChanged"><el-option v-for="p in purchases" :key="p.id" :value="Number(p.id)" :label="p.po_sn+' · '+p.supplier_name+' · '+p.po_name"/></el-select></el-form-item>
     <el-form-item label="原入库单号（可选）"><el-input v-model.trim="ticket" placeholder="来源较多时填写完整入库单号" clearable/></el-form-item>
     <el-form-item label="来源库存"><el-button :loading="loading" :disabled="!selectedPurchase" @click="loadSources">查询可退来源</el-button></el-form-item>
    </el-form>
    <p v-if="purchase" class="muted">供应商：{{purchase.supplier_name}} · 原采购：{{purchase.po_sn}} · 出库虚仓：{{sources[0]&&sources[0].store_code||'当前没有成功入账来源'}}</p>
    <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false"/>
   </section>
   <section><h3><span>2</span> 退仓商品与数量</h3><p class="muted">仅使用已成功入账来源。可退上限同时受原来源余额、当前批次库存及其他业务占用约束，审核时再次校验。</p>
    <el-table :data="sources" size="small" v-loading="loading" empty-text="先选择原采购并查询可退来源">
     <el-table-column label="商品 / SKU / 原入库" min-width="270"><template slot-scope="{row}"><strong>{{row.goods_name}}</strong><div class="muted">{{row.sku_sn}}</div><div class="muted">{{row.ticket_sn}}</div></template></el-table-column>
     <el-table-column label="原批次 / 质量" min-width="150"><template slot-scope="{row}">{{row.batch_code}}<div><el-tag size="mini" :type="row.inventory_type==='ZP'?'info':'warning'">{{row.inventory_type==='ZP'?'正品':'次品'}}</el-tag></div></template></el-table-column>
     <el-table-column label="原实收 / 已退 / 退仓占用" min-width="190"><template slot-scope="{row}">{{row.source_quantity}} / {{row.returned_quantity||0}} / {{row.other_claimed_quantity||0}} {{row.stock_unit}}<div class="muted">来源余额 {{row.remaining_quantity}} · 批次可用 {{row.batch_free}}</div></template></el-table-column>
     <el-table-column label="本次最多可退" min-width="165"><template slot-scope="{row}"><strong>{{maxFor(row)}} {{row.stock_unit}}</strong><div class="muted">{{sourceHint(row)}}</div></template></el-table-column>
     <el-table-column label="原采购单价" width="120"><template slot-scope="{row}">¥{{money(row.purchase_price)}}</template></el-table-column>
     <el-table-column label="本次退仓数量" width="190"><template slot-scope="{row}"><el-input-number v-model="row.selected" :min="0" :max="maxFor(row)" :precision="0" size="small" :disabled="maxFor(row)===0"/></template></el-table-column>
    </el-table>
   </section>
   <section><h3><span>3</span> 供应商收货与交付信息</h3>
    <el-form class="destination" label-position="top" size="small">
     <el-form-item label="供应商收件人" required><el-input v-model.trim="receiver.name" maxlength="50"/></el-form-item><el-form-item label="联系电话" required><el-input v-model.trim="receiver.mobile" maxlength="50"/></el-form-item>
     <el-form-item label="省 / 市 / 区" required><div class="address-region"><el-select v-model="receiver.province" filterable allow-create clearable placeholder="选择或输入省份" aria-label="供应商收货省份" @change="regionChanged('province')"><el-option v-for="v in regionOptions('province')" :key="v" :value="v" :label="v"/></el-select><el-select v-model="receiver.city" filterable allow-create clearable :disabled="!receiver.province" placeholder="选择或输入城市" aria-label="供应商收货城市" @change="regionChanged('city')"><el-option v-for="v in regionOptions('city')" :key="v" :value="v" :label="v"/></el-select><el-select v-model="receiver.area" filterable allow-create clearable :disabled="!receiver.city" placeholder="选择或输入区县" aria-label="供应商收货区县"><el-option v-for="v in regionOptions('area')" :key="v" :value="v" :label="v"/></el-select></div></el-form-item>
     <el-form-item label="供应商退仓地址" required><el-input v-model.trim="receiver.detailAddress" maxlength="200"/></el-form-item>
     <el-form-item label="退仓标准快递" required><purchase-return-carrier-select v-if="selectedPurchase" v-model="carrier" :purchase-id="selectedPurchase" flow="OUTBOUND" :province="receiver.province" :active="visible"/></el-form-item>
     <el-form-item label="退仓原因与协商依据" required><el-input v-model.trim="reason" type="textarea" maxlength="1000" show-word-limit/></el-form-item>
    </el-form>
   </section><el-alert v-if="error" :title="error" type="error" :closable="false"/>
  </div>
  <div slot="footer" class="create-footer"><div>已选 <strong>{{selected.length}}</strong> 行 · {{summary}} · 预计采购货值 <strong>¥{{money(amount)}}</strong></div><div><el-button :disabled="busy" @click="$emit('update:visible',false)">取消</el-button><el-button :loading="busy" @click="save(false)">保存草稿</el-button><el-button v-hasPermi="['warehouse:purchaseReturn:approve']" type="primary" :loading="busy" @click="save(true)">保存并审核退仓</el-button></div></div>
 </el-dialog>
</template>
<script>
import {purchaseChoices,returnSources,returnCarriers,createReturn,returnAction} from '@/api/purchase/returns'
import PurchaseReturnCarrierSelect from './PurchaseReturnCarrierSelect'
import {supplierRegionOptions} from '@/utils/supplierRegions'
const identity=()=> 'PR'+Date.now()+Math.random().toString(36).slice(2)
export default{
 components:{PurchaseReturnCarrierSelect},
 props:{visible:Boolean,purchaseId:[Number,String]},
 data:()=>({selectedPurchase:null,purchases:[],purchase:null,purchaseLoading:false,sources:[],ticket:'',loading:false,sourceError:'',receiver:{name:'',mobile:'',province:'',city:'',area:'',detailAddress:''},carriers:[],carrier:'',carrierLoading:false,carrierError:'',reason:'',error:'',busy:false,key:'',sequence:0,purchaseSequence:0,carrierSequence:0}),
 computed:{selected(){return this.sources.filter(l=>Number(l.selected)>0)},amount(){return this.selected.reduce((n,l)=>n+Number(l.selected)*Number(l.purchase_price),0)},summary(){const units={};this.selected.forEach(l=>{units[l.stock_unit]=(units[l.stock_unit]||0)+Number(l.selected)});return Object.keys(units).map(u=>units[u]+' '+u).join(' + ')||'0 件'}},
 watch:{visible(value){if(value)this.open();else{this.sequence++;this.purchaseSequence++;this.carrierSequence++}}},
 beforeDestroy(){this.sequence++;this.purchaseSequence++;this.carrierSequence++},
 methods:{
  money(v){return Number(v||0).toFixed(2)},
  async open(){this.key=identity();this.purchase=null;this.sources=[];this.ticket='';this.error='';this.sourceError='';this.reason='';this.carrier='';this.carriers=[];this.receiver={name:'',mobile:'',province:'',city:'',area:'',detailAddress:''};this.selectedPurchase=this.purchaseId?Number(this.purchaseId):null;await this.findPurchases('');if(this.selectedPurchase)await this.loadSources()},
  async findPurchases(keyword){const seq=++this.purchaseSequence;this.purchaseLoading=true;try{const rows=(await purchaseChoices({keyword})).data;if(seq===this.purchaseSequence)this.purchases=rows}catch(e){if(seq===this.purchaseSequence)this.sourceError=e.message||'采购单查询失败'}finally{if(seq===this.purchaseSequence)this.purchaseLoading=false}},
  purchaseChanged(){this.sequence++;this.carrierSequence++;this.carriers=[];this.carrier='';this.sources=[];this.purchase=null;this.sourceError='';if(this.selectedPurchase)this.loadSources()},
  async loadSources(){if(!this.selectedPurchase)return;const seq=++this.sequence;this.loading=true;this.sourceError='';this.sources=[];try{const d=(await returnSources({purchaseId:this.selectedPurchase,ticketSn:this.ticket})).data;if(seq!==this.sequence)return;this.purchase=d.purchase;this.sources=d.rows.map(l=>({...l,selected:0}));this.receiver={...this.receiver,...d.supplier};if(!this.purchases.some(p=>Number(p.id)===Number(d.purchase.id)))this.purchases.unshift({...d.purchase,po_name:''});}catch(e){if(seq===this.sequence)this.sourceError=e.message||'原入库来源查询失败'}finally{if(seq===this.sequence)this.loading=false}},
  regionOptions(field){return supplierRegionOptions({province:'contactProvince',city:'contactCity',area:'contactArea'}[field],{contactProvince:this.receiver.province,contactCity:this.receiver.city,contactArea:this.receiver.area})},
  regionChanged(field){if(field==='province'){this.receiver.city='';}this.receiver.area=''},
  async loadCarriers(){if(!this.selectedPurchase)return;const seq=++this.carrierSequence;this.carrierLoading=true;this.carrierError='';try{const d=(await returnCarriers({purchaseId:this.selectedPurchase,province:this.receiver.province||''})).data;if(seq===this.carrierSequence)this.carriers=d||[]}catch(e){if(seq===this.carrierSequence){this.carriers=[];this.carrierError='快递目录暂未查询成功，请核对仓库支持的快递编码后填写。'}}finally{if(seq===this.carrierSequence)this.carrierLoading=false}},
  maxFor(row){const same=l=>l!==row&&l.sku_sn===row.sku_sn&&l.store_code===row.store_code&&l.batch_code===row.batch_code&&l.inventory_type===row.inventory_type;const other=this.sources.filter(same).reduce((n,l)=>n+Number(l.selected||0),0);return Math.max(0,Math.min(Number(row.remaining_quantity),Number(row.batch_free)-other))},
  sourceHint(row){if(Number(row.remaining_quantity)===0)return '原来源可退额度已用完';if(Number(row.batch_free)===0)return '当前批次无可退库存，请核对库存与其他业务占用';if(this.maxFor(row)===0)return '本批次可退库存已分配给其他所选来源';return '审核时再次核对来源额度与可用库存'},
  async save(approve){if(this.busy)return;if(!this.purchase||!this.selected.length)return this.$message.warning('请选择原采购及至少一行退仓商品');if(this.selected.some(l=>Number(l.selected)>this.maxFor(l)))return this.$message.warning('本次数量超过当前原来源或批次库存，请重新核对');if(!['name','mobile','province','city','area','detailAddress'].every(k=>this.receiver[k])||!this.carrier||!this.reason)return this.$message.warning('请完善供应商收件信息、省市区、快递及退仓依据');if(approve){try{await this.$confirm('审核后将锁定 '+this.summary+'，预计货值 ¥'+this.money(this.amount)+'，并按当前供应商收货地址自动执行退仓。确认已核对？','审核前确认')}catch(_){return}}this.busy=true;this.error='';let id;try{id=(await createReturn({request_key:this.key,purchase_id:this.purchase.id,ticket_sn:this.ticket,receiver:this.receiver,standard_id:this.carrier,reason:this.reason,lines:this.selected.map(l=>({source_key:l.source_key,inventory_type:l.inventory_type,quantity:Number(l.selected)}))})).data;if(approve)try{await returnAction(id,'approve',{control_version:1})}catch(e){this.$message.warning('草稿已保存，审核未完成，请在详情核对后继续。')}this.$emit('created',id);this.$emit('update:visible',false)}catch(e){this.error=e.message||'退仓草稿未保存，请核对输入'}finally{this.busy=false}}
 }
}
</script>
<style scoped>
.return-create{max-height:calc(86vh - 170px);overflow:auto;padding-right:5px}.return-create section{border:1px solid #e2e8f1;border-radius:8px;padding:18px;margin-top:18px}.return-create h3{font-size:16px;margin:0 0 18px}.return-create h3 span{display:inline-flex;background:#eaf3ff;color:#0877dc;border-radius:50%;width:25px;height:25px;align-items:center;justify-content:center;margin-right:8px}.purchase-picker{width:520px}.muted{color:#7a899d;font-size:12px;line-height:1.8;overflow-wrap:anywhere}.destination{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.address-region{display:flex;gap:8px}.address-region .el-select{flex:1;min-width:0}.destination>.el-form-item>.el-form-item__content>.el-select{width:100%}.create-footer{display:flex;justify-content:space-between;align-items:center;gap:20px}.return-create ::v-deep .cell{overflow-wrap:anywhere}@media(max-width:900px){.destination{grid-template-columns:1fr}.purchase-picker{width:100%}.create-footer{align-items:flex-start;flex-direction:column}}
</style>
