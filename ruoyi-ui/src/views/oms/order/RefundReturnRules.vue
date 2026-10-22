<template>
  <div class="app-container return-rules">
    <div v-if="returnRefundId" class="intro"><router-link :to="{path:'/oms-order/businessRefunds',query:{id:returnRefundId}}">返回退款单</router-link></div>
    <div class="rule-overview">
      <button :class="{active:!query.autoCreate&&!query.autoDispatch&&!query.autoEnabled}" @click="preset('ALL')"><span>全部店铺</span><b>{{ summary.shops }}</b></button>
      <button :class="{active:query.autoCreate==='1'}" @click="preset('CREATE')"><span>自动创建安排</span><b>{{ summary.auto_create||0 }}</b></button>
      <button :class="{active:query.autoDispatch==='1'}" @click="preset('DISPATCH')"><span>自动下发仓库</span><b>{{ summary.auto_dispatch||0 }}</b></button>
      <button :class="{active:query.autoEnabled==='1'}" @click="preset('AUTO')"><span>自动合包</span><b>{{ summary.auto_enabled }}</b></button>
    </div>
    <el-form inline size="small" @submit.native.prevent="search" class="filters">
      <el-form-item label="店铺"><el-input v-model.trim="query.keyword" clearable placeholder="店铺名称 / 渠道ID" @keyup.enter.native="search"/></el-form-item>
      <el-form-item label="下发方式"><el-select v-model="query.autoDispatch" clearable placeholder="全部"><el-option label="自动下发" value="1"/><el-option label="人工下发" value="0"/></el-select></el-form-item>
      <el-form-item label="合包方式"><el-select v-model="query.mergeMode" clearable placeholder="全部"><el-option label="不合包" value="NONE"/><el-option label="人工合包" value="MANUAL"/><el-option label="自动合包" value="AUTO"/></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="search">查询</el-button><el-button @click="reset">重置</el-button><el-button type="text" @click="advanced=!advanced">更多筛选<i :class="advanced?'el-icon-arrow-up':'el-icon-arrow-down'"/></el-button></el-form-item>
      <div v-show="advanced"><el-form-item label="创建方式"><el-select v-model="query.autoCreate" clearable placeholder="全部"><el-option label="审核后自动创建" value="1"/><el-option label="人工创建" value="0"/></el-select></el-form-item><el-form-item label="保存情况"><el-select v-model="query.configState" clearable placeholder="全部"><el-option label="系统默认规则" value="DEFAULT"/><el-option label="已保存配置" value="CUSTOM"/></el-select></el-form-item></div>
    </el-form>
    <div class="list-toolbar"><span v-if="query.channelId">当前店铺：渠道 {{ query.channelId }} <el-button type="text" @click="clearContext">取消定位</el-button></span><span v-else class="hint">共 {{ total }} 家店铺 · 停用店铺暂停自动处理</span><el-button size="small" icon="el-icon-refresh" :loading="loading" @click="load()">刷新</el-button></div>
    <el-alert v-if="error||contextError" :title="error||contextError" type="warning" show-icon :closable="false"/>
    <el-alert v-if="warehouseNamesUnavailable" title="仓库名称暂不可用，当前保留仓库编码；请在编辑页核对仓库能力。" type="info" :closable="false"/>
    <el-table v-loading="loading" :data="rows" row-key="channel_id" :row-class-name="rowClass" empty-text="没有符合筛选的店铺" class="return-rules-table">
      <el-table-column label="店铺" min-width="240"><template slot-scope="s"><strong class="shop-title" :title="s.row.channel_name">{{ s.row.channel_name }}</strong><p class="hint">天猫 · 渠道 {{ s.row.channel_id }} · {{ s.row.configured?'已保存配置':'系统默认规则' }}</p><el-tag v-if="!Number(s.row.shop_enabled)" size="mini" type="info">已停用</el-tag></template></el-table-column>
      <el-table-column label="创建方式" min-width="150"><template slot-scope="s">{{ Number(s.row.auto_create)?'审核后自动创建':'人工创建' }}</template></el-table-column>
      <el-table-column label="下发方式" min-width="155"><template slot-scope="s"><span :class="{'on':Number(s.row.auto_dispatch)}">{{ Number(s.row.auto_dispatch)?'自动下发':'人工下发' }}</span><p v-if="Number(s.row.auto_dispatch)" class="hint">{{ s.row.merge_mode==='NONE'?'安排就绪后独立下发':s.row.merge_mode==='MANUAL'?'确认包裹后下发':'归集结束后下发' }}</p></template></el-table-column>
      <el-table-column label="合包方式" min-width="140"><template slot-scope="s">{{ modeLabel(s.row.merge_mode) }}<p v-if="s.row.merge_mode==='AUTO'" class="hint">{{ Number(s.row.collect_seconds)?'归集等待 '+s.row.collect_seconds+' 秒':'不等待归集' }}</p></template></el-table-column>
      <el-table-column label="默认退回仓" min-width="210"><template slot-scope="s"><span :title="s.row.create_store_code">{{ s.row.create_store_name||'未配置' }}</span></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="115"><template slot-scope="s"><el-button type="text" :disabled="busy" @click="openEditor(s.row,true)">查看</el-button><el-button type="text" :disabled="busy||!Number(s.row.shop_enabled)" @click="openEditor(s.row,false)">编辑</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load()"/>
    <el-drawer :title="editorReadOnly?'查看退货规则':'编辑退货规则'" :visible.sync="drawerVisible" size="min(1020px,96vw)" append-to-body :wrapper-closable="false" :before-close="closeDrawer" custom-class="return-rules-drawer">
      <div class="drawer-content"><refund-parcel-settings v-if="drawerVisible&&activeShop" :key="activeShop.channel_id" ref="rules" :read-only="editorReadOnly" @busy="busy=$event" @saving="saving=$event" @changed="saved"/><el-button class="close-drawer" :disabled="saving" @click="closeDrawer()">关闭并返回列表</el-button></div>
    </el-drawer>
  </div>
</template>
<script>
import { refundReturnRules } from '@/api/order/refund'
import RefundParcelSettings from './RefundParcelSettings'
const initialQuery=()=>({keyword:'',channelId:'',mergeMode:'',autoCreate:'',autoEnabled:'',autoDispatch:'',configState:'',pageNum:1,pageSize:20})
export default {
  name:'RefundReturnRules',components:{RefundParcelSettings},
  data:()=>({query:initialQuery(),rows:[],total:0,summary:{shops:0,configured:0,auto_enabled:0},loading:false,sequence:0,advanced:false,busy:false,saving:false,error:'',contextError:'',warehouseNamesUnavailable:false,drawerVisible:false,activeShop:null,editorReadOnly:false}),
  computed:{returnRefundId(){const id=String(this.$route.query.refundId||'');return /^\d+$/.test(id)?id:''}},
  watch:{'$route.query.channelId'(){this.openRequestedShop()}},
  created(){this.openRequestedShop()},beforeDestroy(){this.sequence++},
  beforeRouteUpdate(to,from,next){if(this.saving){this.$message.warning('规则正在保存，请稍后切换');next(false)}else next()},
  beforeRouteLeave(to,from,next){if(this.saving){this.$message.warning('规则正在保存，请稍后离开');next(false)}else next()},
  methods:{
    modeLabel(mode){return ({NONE:'不合包',MANUAL:'人工合包',AUTO:'自动合包'})[mode]||'人工合包'},
    async load(focus=false){const seq=++this.sequence;this.loading=true;this.error='';this.contextError='';try{const r=(await refundReturnRules({...this.query})).data;if(seq!==this.sequence)return;this.rows=r.rows;this.total=Number(r.total);this.summary=r.summary;this.warehouseNamesUnavailable=!!r.warehouse_names_unavailable;if(focus&&this.query.channelId){const shop=this.rows.find(s=>String(s.channel_id)===this.query.channelId);if(shop)this.openEditor(shop,!Number(shop.shop_enabled));else this.contextError='未找到当前公司下的指定店铺，请重新选择'}}catch(_){if(seq===this.sequence){this.rows=[];this.total=0;this.summary={shops:0,configured:0,auto_enabled:0};this.error='退货规则列表加载失败，请重试'}}finally{if(seq===this.sequence)this.loading=false}},
    openRequestedShop(){this.drawerVisible=false;this.activeShop=null;this.busy=false;this.query={...initialQuery(),channelId:String(this.$route.query.channelId||'')};return this.load(true)},
    search(){this.query.pageNum=1;return this.load()},
    reset(){this.query={...initialQuery(),channelId:this.query.channelId,pageSize:this.query.pageSize};return this.load()},
    preset(kind){this.query.configState=kind==='CUSTOM'?'CUSTOM':'';this.query.autoEnabled=kind==='AUTO'?'1':'';this.query.autoDispatch=kind==='DISPATCH'?'1':'';this.query.autoCreate=kind==='CREATE'?'1':'';this.query.mergeMode='';return this.search()},
    clearContext(){const query={...this.$route.query};delete query.channelId;this.$router.replace({path:'/oms-order/afterSaleRules/returns',query})},
    rowClass({row}){return String(row.channel_id)===this.query.channelId?'focused-shop':''},
    openEditor(shop,readOnly=false){if(this.saving)return;this.activeShop={channel_id:shop.channel_id,channel_name:shop.channel_name};this.editorReadOnly=readOnly||!Number(shop.shop_enabled);this.drawerVisible=true;this.$nextTick(()=>{if(this.$refs.rules)this.$refs.rules.open(this.activeShop)})},
    closeDrawer(done){if(this.saving)return this.$message.warning('规则正在保存，请稍后关闭');this.drawerVisible=false;this.busy=false;if(typeof done==='function')done()},
    saved(){return this.load()}
  }
}
</script>
<style scoped>.intro,.list-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:18px;color:#61728a;line-height:1.8}.intro a{color:#1677ff;font-size:13px}.rule-overview{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}.rule-overview button{font:inherit;text-align:left;background:#f4f7fc;border:1px solid #e1e8f0;border-radius:8px;padding:16px 20px;cursor:pointer}.rule-overview span{display:block;color:#61728a;font-size:13px}.rule-overview b{display:block;font-size:26px;color:#263c57;margin-top:8px}.rule-overview button.active{border-color:#1677ff;background:#eef5ff}.hint{font-size:12px;color:#8290a4;line-height:1.8;margin:5px 0}.filters{margin:20px 0 4px}.filters .el-select{width:155px}.on{color:#198754}.shop-title{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:500}.drawer-content{padding:0 22px 24px}.close-drawer{margin-top:16px}.list-toolbar{margin-bottom:12px}.return-rules ::v-deep .focused-shop td{background:#f0f6ff}.el-alert{margin:12px 0}@media(max-width:760px){.rule-overview{gap:8px}.rule-overview button{padding:12px}.drawer-content{padding:0 12px 16px}}</style>
