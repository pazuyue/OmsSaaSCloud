<template>
  <div class="app-container return-rules">
    <div class="intro"><span>每家店铺一行，查看当前生效规则；使用系统默认规则与关闭功能分别展示。</span><router-link v-if="returnRefundId" :to="{path:'/oms-order/businessRefunds',query:{id:returnRefundId}}">返回退款单</router-link></div>
    <div class="rule-overview">
      <button :class="{active:!query.configState&&!query.autoEnabled}" @click="preset('ALL')"><span>店铺数</span><b>{{ summary.shops }}</b></button>
      <button :class="{active:query.configState==='CUSTOM'}" @click="preset('CUSTOM')"><span>已保存配置</span><b>{{ summary.configured }}</b></button>
      <button :class="{active:query.autoEnabled==='1'}" @click="preset('AUTO')"><span>开启自动合包</span><b>{{ summary.auto_enabled }}</b></button>
    </div>
    <p class="hint">概览按当前店铺搜索范围统计。开关开启的停用店铺仍会暂停执行自动流程。</p>
    <el-form inline size="small" @submit.native.prevent="search" class="filters">
      <el-form-item label="店铺"><el-input v-model.trim="query.keyword" clearable placeholder="店铺名称 / 渠道ID" @keyup.enter.native="search"/></el-form-item>
      <el-form-item label="自动合包"><el-select v-model="query.autoEnabled" clearable placeholder="全部"><el-option label="开启" value="1"/><el-option label="关闭" value="0"/></el-select></el-form-item>
      <el-form-item label="自动下发"><el-select v-model="query.autoDispatch" clearable placeholder="全部"><el-option label="开启" value="1"/><el-option label="关闭" value="0"/></el-select></el-form-item>
      <el-form-item label="配置情况"><el-select v-model="query.configState" clearable placeholder="全部"><el-option label="使用系统默认规则" value="DEFAULT"/><el-option label="已保存配置" value="CUSTOM"/></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="search">查询</el-button><el-button @click="reset">重置筛选</el-button></el-form-item>
    </el-form>
    <div class="list-toolbar"><span v-if="query.channelId">当前定位店铺：渠道 {{ query.channelId }} <el-button type="text" @click="clearContext">取消店铺定位</el-button></span><span v-else class="hint">在抽屉中查看或编辑，列表不直接修改开关。</span><el-button size="small" icon="el-icon-refresh" :loading="loading" @click="load()">刷新列表</el-button></div>
    <el-alert v-if="error||contextError" :title="error||contextError" type="warning" show-icon :closable="false"/>
    <el-alert v-if="warehouseNamesUnavailable" title="仓库名称暂时无法加载，当前显示仓库编码；规则值保留，仓库生效情况请在详情中核对。" type="info" :closable="false"/>
    <el-table v-loading="loading" :data="rows" row-key="channel_id" :row-class-name="rowClass" empty-text="当前筛选下没有店铺，请调整筛选条件">
      <el-table-column label="店铺 / 渠道" min-width="220"><template slot-scope="s"><strong>{{ s.row.channel_name }}</strong><p class="hint">天猫 · 渠道 {{ s.row.channel_id }}</p><el-tag v-if="!Number(s.row.shop_enabled)" size="mini" type="info">店铺已停用</el-tag></template></el-table-column>
      <el-table-column label="合包规则" min-width="155"><template slot-scope="s"><el-tag size="small" :type="Number(s.row.enabled)?'success':'info'">{{ Number(s.row.enabled)?'允许合包':'不新增合包' }}</el-tag><p class="hint">{{ s.row.scope==='SAME_ORDER'?'同一原平台订单':'跨订单仅人工关联' }}</p></template></el-table-column>
      <el-table-column label="自动流程" min-width="145"><template slot-scope="s"><div>自动合包 <span :class="Number(s.row.auto_enabled)?'on':'hint'">{{ Number(s.row.auto_enabled)?'开启':'关闭' }}</span></div><div>自动下发 <span :class="Number(s.row.auto_dispatch)?'on':'hint'">{{ Number(s.row.auto_dispatch)?'开启':'关闭' }}</span></div><p v-if="!Number(s.row.shop_enabled)" class="hint">店铺停用，自动流程暂停</p></template></el-table-column>
      <el-table-column label="自动处理退回仓" min-width="160"><template slot-scope="s"><el-tooltip v-if="s.row.warehouses.length" placement="top"><div slot="content"><div v-for="w in s.row.warehouses" :key="w.code">{{ w.name }} · {{ w.code }}</div></div><span>{{ s.row.warehouses[0].name }}<span v-if="s.row.warehouses.length>1"> 等 {{ s.row.warehouses.length }} 个仓</span></span></el-tooltip><span v-else class="hint">未指定自动处理仓</span></template></el-table-column>
      <el-table-column label="归集等待" width="105"><template slot-scope="s">{{ Number(s.row.collect_seconds)?s.row.collect_seconds+' 秒':'不等待' }}</template></el-table-column>
      <el-table-column label="实收分配" min-width="160"><template slot-scope="s">{{ s.row.allocation_mode==='MANUAL'?'人工确认':s.row.allocation_mode==='RULE'&&Number(s.row.allow_partial_allocation)?'按规则自动分配':'严格自动匹配' }}<p class="hint">{{ s.row.allocation_mode==='RULE'&&Number(s.row.allow_partial_allocation)?'部分实收 · 申请时间优先':'异常转人工核对' }}</p></template></el-table-column>
      <el-table-column label="配置情况" min-width="150"><template slot-scope="s"><el-tag size="small" :type="s.row.configured?'success':'info'">{{ s.row.configured?'已保存配置':'使用系统默认规则' }}</el-tag><p v-if="s.row.configured" class="hint">版本 {{ s.row.revision }} · {{ s.row.updated_by||'—' }}<br>{{ s.row.update_time }}</p><p v-else class="hint">默认规则已生效</p></template></el-table-column>
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
const initialQuery=()=>({keyword:'',channelId:'',autoEnabled:'',autoDispatch:'',configState:'',pageNum:1,pageSize:20})
export default {
  name:'RefundReturnRules',components:{RefundParcelSettings},
  data:()=>({query:initialQuery(),rows:[],total:0,summary:{shops:0,configured:0,auto_enabled:0},loading:false,sequence:0,busy:false,saving:false,error:'',contextError:'',warehouseNamesUnavailable:false,drawerVisible:false,activeShop:null,editorReadOnly:false}),
  computed:{returnRefundId(){const id=String(this.$route.query.refundId||'');return /^\d+$/.test(id)?id:''}},
  watch:{'$route.query.channelId'(){this.openRequestedShop()}},
  created(){this.openRequestedShop()},beforeDestroy(){this.sequence++},
  beforeRouteUpdate(to,from,next){if(this.saving){this.$message.warning('规则正在保存，请稍后切换');next(false)}else next()},
  beforeRouteLeave(to,from,next){if(this.saving){this.$message.warning('规则正在保存，请稍后离开');next(false)}else next()},
  methods:{
    async load(focus=false){const seq=++this.sequence;this.loading=true;this.error='';this.contextError='';try{const r=(await refundReturnRules({...this.query})).data;if(seq!==this.sequence)return;this.rows=r.rows;this.total=Number(r.total);this.summary=r.summary;this.warehouseNamesUnavailable=!!r.warehouse_names_unavailable;if(focus&&this.query.channelId){const shop=this.rows.find(s=>String(s.channel_id)===this.query.channelId);if(shop)this.openEditor(shop,!Number(shop.shop_enabled));else this.contextError='未找到当前公司下的指定店铺，请重新选择'}}catch(_){if(seq===this.sequence){this.rows=[];this.total=0;this.summary={shops:0,configured:0,auto_enabled:0};this.error='退货规则列表加载失败，请重试'}}finally{if(seq===this.sequence)this.loading=false}},
    openRequestedShop(){this.drawerVisible=false;this.activeShop=null;this.busy=false;this.query={...initialQuery(),channelId:String(this.$route.query.channelId||'')};return this.load(true)},
    search(){this.query.pageNum=1;return this.load()},
    reset(){this.query={...initialQuery(),channelId:this.query.channelId,pageSize:this.query.pageSize};return this.load()},
    preset(kind){this.query.configState=kind==='CUSTOM'?'CUSTOM':'';this.query.autoEnabled=kind==='AUTO'?'1':'';this.query.autoDispatch='';return this.search()},
    clearContext(){const query={...this.$route.query};delete query.channelId;this.$router.replace({path:'/oms-order/afterSaleRules/returns',query})},
    rowClass({row}){return String(row.channel_id)===this.query.channelId?'focused-shop':''},
    openEditor(shop,readOnly=false){if(this.saving)return;this.activeShop={channel_id:shop.channel_id,channel_name:shop.channel_name};this.editorReadOnly=readOnly||!Number(shop.shop_enabled);this.drawerVisible=true;this.$nextTick(()=>{if(this.$refs.rules)this.$refs.rules.open(this.activeShop)})},
    closeDrawer(done){if(this.saving)return this.$message.warning('规则正在保存，请稍后关闭');this.drawerVisible=false;this.busy=false;if(typeof done==='function')done()},
    saved(){return this.load()}
  }
}
</script>
<style scoped>.intro,.list-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:18px;color:#61728a;line-height:1.8}.intro a{color:#1677ff;font-size:13px}.rule-overview{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.rule-overview button{font:inherit;text-align:left;background:#f4f7fc;border:1px solid #e1e8f0;border-radius:8px;padding:16px 20px;cursor:pointer}.rule-overview span{display:block;color:#61728a;font-size:13px}.rule-overview b{display:block;font-size:26px;color:#263c57;margin-top:8px}.rule-overview button.active{border-color:#1677ff;background:#eef5ff}.hint{font-size:12px;color:#8290a4;line-height:1.8;margin:5px 0}.filters{margin:20px 0 4px}.filters .el-select{width:155px}.on{color:#198754}.drawer-content{padding:0 22px 24px}.close-drawer{margin-top:16px}.list-toolbar{margin-bottom:12px}.return-rules ::v-deep .focused-shop td{background:#f0f6ff}.el-alert{margin:12px 0}@media(max-width:760px){.rule-overview{gap:8px}.rule-overview button{padding:12px}.drawer-content{padding:0 12px 16px}}</style>
