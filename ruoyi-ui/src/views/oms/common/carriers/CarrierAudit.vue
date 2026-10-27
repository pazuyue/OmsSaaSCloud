<template>
  <el-dialog title="配置操作记录" :visible.sync="visible" width="1100px" append-to-body :close-on-click-modal="false">
    <p class="hint">{{ query.object_id ? '当前配置的变更记录' : '当前公司全部快递配置的操作记录' }}，配置来源与确认说明随变更保留。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <div class="audit-filters"><el-select v-model="query.object_type" clearable placeholder="全部配置类型" @change="search"><el-option v-for="(label,key) in objects" :key="key" :label="label" :value="key"/></el-select><el-input v-model="query.keyword" placeholder="搜索操作人" clearable @keyup.enter.native="search"/><el-button size="small" @click="search">查询</el-button><el-button v-if="query.object_id" size="small" @click="all">查看全部记录</el-button></div>
    <el-table v-loading="loading" :data="rows" size="small" row-key="id" max-height="520">
      <el-table-column type="expand"><template slot-scope="{row}"><div class="audit-details"><p v-if="details(row).evidence"><strong>配置来源与确认说明：</strong>{{details(row).evidence}}</p><el-table :data="details(row).changes" size="small" empty-text="本记录为流程或批量操作，可展开技术详情核对原始内容"><el-table-column prop="field" label="变更字段" width="180"/><el-table-column prop="before" label="修改前" min-width="220"/><el-table-column prop="after" label="修改后" min-width="220"/></el-table><el-collapse><el-collapse-item title="技术详情"><pre>变更前：{{JSON.stringify(details(row).before,null,2)}}
变更后：{{JSON.stringify(details(row).after,null,2)}}</pre></el-collapse-item></el-collapse></div></template></el-table-column>
      <el-table-column prop="create_time" label="时间" width="170"/><el-table-column prop="actor" label="操作人" width="120"/><el-table-column label="操作内容" min-width="400"><template slot-scope="{row}">{{details(row).summary}}</template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load"/>
    <span slot="footer"><el-button @click="visible=false">关闭</el-button></span>
  </el-dialog>
</template>
<script>
import { carrierPage } from '@/api/common/carriers'
import { auditDetails, objectLabels } from '@/utils/carrierManagement'
import { purposeLabels } from './CarrierScope'
export default {
  data: () => ({ visible:false, loading:false, error:'', rows:[], total:0, epoch:0, objects:objectLabels, query:{pageNum:1,pageSize:20,keyword:'',object_type:'',object_id:''} }),
  beforeDestroy(){this.epoch++},
  methods: {
    details(row){return row.display||auditDetails(row,purposeLabels)},
    open(type,id){this.query={pageNum:1,pageSize:20,keyword:'',object_type:type?type.toUpperCase():'',object_id:id||''};this.visible=true;this.load()},
    search(){this.query.pageNum=1;this.load()},all(){this.open()},
    async load(){const epoch=++this.epoch;this.loading=true;this.error='';try{const result=(await carrierPage('audit',this.query)).data;if(epoch!==this.epoch)return;this.rows=result.rows.map(row=>({...row,display:auditDetails(row,purposeLabels)}));this.total=Number(result.total)}catch(e){if(epoch===this.epoch){this.rows=[];this.error=e.message||'操作记录读取失败'}}finally{if(epoch===this.epoch)this.loading=false}}
  }
}
</script>
<style scoped>.hint{color:#748399;font-size:12px}.audit-filters{display:flex;gap:10px;margin:15px 0}.audit-filters .el-input{width:220px}.audit-details{padding:8px 20px}.audit-details pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:350px;overflow:auto}.audit-details p{white-space:pre-wrap;overflow-wrap:anywhere}</style>
