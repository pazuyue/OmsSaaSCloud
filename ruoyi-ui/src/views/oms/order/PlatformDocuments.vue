<template>
  <div class="app-container platform-documents" v-loading="loading">
    <el-form inline size="small" class="shared-filters" @submit.native.prevent="search">
      <el-form-item label="渠道 / 店铺"><el-select v-model="filters.channelId" clearable filterable placeholder="全部店铺"><el-option v-for="c in channels" :key="c.channel_id" :value="String(c.channel_id)" :label="c.channel_name" /></el-select></el-form-item>
      <el-form-item label="平台主单号"><el-input v-model.trim="filters.platformTid" clearable placeholder="填写完整平台主单号" @keyup.enter.native="search" /></el-form-item>
      <el-form-item label="平台子单号"><el-input v-model.trim="filters.platformOid" clearable placeholder="填写完整平台子单号" @keyup.enter.native="search" /></el-form-item>
      <el-form-item label="平台修改时间"><el-date-picker v-model="range" type="datetimerange" value-format="yyyy-MM-dd HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置公共条件</el-button><el-button :disabled="!filters.platformTid" @click="related()">查看原单关联</el-button></el-form-item>
    </el-form>
    <p class="hint">切换类型保留以上查询条件；各类单据的状态与转单结果分别展示。接入规则在<router-link to="/oms-order/businessSettings">业务设置</router-link>维护，补拉和进度在<router-link to="/oms-order/syncTasks">同步任务</router-link>查看。</p>
    <el-alert v-if="deniedType" title="没有所选类型的查看权限，已展示可访问的单据类型。" type="warning" :closable="false" />
    <el-tabs v-if="types.length" v-model="active" @tab-click="tabChanged"><el-tab-pane v-for="t in types" :key="t" :label="names[t]" :name="t" /></el-tabs>
    <keep-alive :max="3"><component v-if="active" :is="components[active]" :key="active" v-bind="childProps" ref="list" @related="related($event)" /></keep-alive>
    <platform-document-relations ref="relations" :channels="channels" @open="openRelated" />
  </div>
</template>
<script>
import { platformDocumentOptions } from '@/api/order/platformDocuments'
import OrderWorkspace from './index'
import RefundList from './RefundList'
import PlatformExchanges from './PlatformExchanges'
import PlatformDocumentRelations from './PlatformDocumentRelations'
import { documentKinds, documentLocation, sharedDocumentFilters } from './platformDocumentsPresentation'
export default { name:'PlatformDocuments',components:{PlatformDocumentRelations},data:()=>({loading:false,types:[],channels:[],active:'',deniedType:false,filters:sharedDocumentFilters(),applied:sharedDocumentFilters(),range:[],names:documentKinds,components:{ORDER:OrderWorkspace,REFUND:RefundList,EXCHANGE:PlatformExchanges}}),computed:{childProps(){return{embedded:true,sharedFilters:this.applied,shopOptions:this.channels,recordId:(this.$route.query.type||this.types[0])===this.active&&/^[1-9][0-9]*$/.test(String(this.$route.query.id||''))?Number(this.$route.query.id):0,...(this.active==='ORDER'?{viewOverride:'platform'}:{})}}},watch:{'$route.query'(){if(this.$route.path==='/oms-order/platformDocuments'&&this.types.length)this.readRoute()}},async created(){this.loading=true;try{const r=(await platformDocumentOptions()).data;this.types=r.types;this.channels=r.channels;this.readRoute()}finally{this.loading=false}},methods:{readRoute(){const q=this.$route.query,requested=q.type||this.types[0];this.deniedType=!this.types.includes(requested);this.active=this.types.includes(requested)?requested:this.types[0]||'';const next=sharedDocumentFilters(q);this.filters={...next};this.range=next.modifiedFrom&&next.modifiedTo?[next.modifiedFrom,next.modifiedTo]:[];if(JSON.stringify(next)!==JSON.stringify(this.applied))this.applied=next},location(type=this.active,extra={}){return documentLocation(type,{...this.filters,modifiedFrom:(this.range||[])[0]||'',modifiedTo:(this.range||[])[1]||'',...extra})},async navigate(location){if(this.$route.fullPath===this.$router.resolve(location).route.fullPath){if(this.$refs.list)this.$refs.list.load();return}await this.$router.replace(location)},search(){if(this.range&&this.range.length&&this.range[0]>this.range[1])return this.$message.warning('修改时间开始不能晚于结束');return this.navigate(this.location(this.active,{id:undefined}))},reset(){this.filters=sharedDocumentFilters();this.range=[];return this.search()},tabChanged(){return this.navigate(this.location(this.active,{id:undefined}))},related(row){const tid=row&&row.platform_tid||this.filters.platformTid,channel=row&&row.channel_id||this.filters.channelId;if(!tid)return this.$message.warning('请先填写完整平台主单号');this.$refs.relations.open(tid,channel)},openRelated(record){return this.navigate(documentLocation(record.kind,{...this.applied,channelId:record.channel_id?String(record.channel_id):'',platformTid:record.platform_tid,id:record.id}))}} }
</script>
<style scoped>.shared-filters{border-bottom:1px solid #e7edf5;margin-bottom:12px}.shared-filters .el-input{width:225px}.shared-filters .el-select{width:220px}.shared-filters .el-date-editor{width:365px}.hint{font-size:12px;line-height:1.8;color:#8290a4;margin:0 0 18px}.hint a{color:#1677ff}.platform-documents ::v-deep .app-container{padding:0}.platform-documents ::v-deep .el-tabs__header{margin-bottom:20px}</style>
