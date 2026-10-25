<template>
  <div class="money-authorizations">
    <el-button size="mini" :disabled="disabled" @click="open">登记 / 查看差价业务授权</el-button>
    <el-dialog title="差价业务授权" :visible.sync="visible" width="900px" append-to-body :close-on-click-modal="false">
      <el-alert title="授权必须有明确业务凭证；仅匹配指定退款号和金额。登记后仍需系统自动审核及平台确认退款。" type="info" :closable="false" />
      <p v-if="error" class="error">{{ error }}</p>
      <el-table :data="rows" size="mini" max-height="240" v-loading="loading"><el-table-column prop="refund_id" label="平台退款号" min-width="160"/><el-table-column prop="amount" label="差价金额" width="90"/><el-table-column prop="reference_sn" label="业务凭证" min-width="160"/><el-table-column label="状态" width="90"><template slot-scope="{row}">{{ stateLabel(row) }}</template></el-table-column><el-table-column prop="authorized_by" label="授权登记人" width="110"/><el-table-column prop="expires_at" label="到期时间" width="165"/><el-table-column label="操作" width="90"><template slot-scope="{row}"><el-button v-if="row.state==='ACTIVE'" type="text" :disabled="disabled||saving" @click="revoke(row)">撤销</el-button></template></el-table-column></el-table>
      <el-divider>登记一份明确授权</el-divider>
      <div class="authorization-fields"><label v-for="f in fields" :key="f.key">{{ f.label }}<el-input v-model="form[f.key]" :maxlength="f.max" :disabled="disabled||saving" /></label><label>差价金额（元）<el-input-number v-model="form.amount" :min="0.01" :precision="2" :disabled="disabled||saving" /></label><label>到期时间<el-date-picker v-model="form.expires_at" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" :disabled="disabled||saving" /></label></div>
      <p class="hint">最长有效期30天。已采用的授权保留原记录，不能撤销或修改成另一笔退款。</p>
      <el-checkbox v-model="form.confirmed" :disabled="disabled||saving">我确认上述差价有明确业务依据，并授权系统匹配后采用</el-checkbox>
      <span slot="footer"><el-button @click="visible=false">关闭</el-button><el-button type="primary" :loading="saving" :disabled="disabled||!form.confirmed" @click="save">登记授权</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import request from '@/utils/request'
const blank = () => ({ platform_tid: '', platform_oid: '', refund_id: '', amount: 0.01, reference_sn: '', evidence: '', expires_at: '', confirmed: false })
export default {
  props: { channelId: { type: [Number, String], required: true }, disabled: Boolean },
  data: () => ({ visible: false, rows: [], loading: false, saving: false, error: '', form: blank(), fields: [{key:'platform_tid',label:'原平台主订单号',max:64},{key:'platform_oid',label:'原平台子订单号',max:64},{key:'refund_id',label:'指定平台退款号',max:64},{key:'reference_sn',label:'差价协议 / 业务凭证编号',max:100},{key:'evidence',label:'明确的差价业务依据',max:1000}] }),
  watch: { channelId() { this.visible=false;this.rows=[];this.form=blank();this.loading=false;this.saving=false;this.error='' } },
  methods: {
    stateLabel(row) { if(row.state==='ACTIVE' && new Date(String(row.expires_at).replace(' ','T')).getTime()<=Date.now())return '已过期';return {ACTIVE:'待采用',USED:'已采用',REVOKED:'已撤销'}[row.state] || row.state },
    endpoint() { return `/order/refunds/flow-configs/${this.channelId}/money-authorizations` },
    async open() { this.visible=true;this.error='';await this.load() },
    async load() { const channel=this.channelId;this.loading=true;try {const reply=await request({url:this.endpoint()});if(channel===this.channelId)this.rows=reply.data || []} catch(e) {if(channel===this.channelId)this.error=e.message || '授权记录暂未读取'} finally {if(channel===this.channelId)this.loading=false} },
    async save() {const channel=this.channelId;this.saving=true;this.error='';try {await request({url:this.endpoint(),method:'post',data:this.form});if(channel===this.channelId){this.form=blank();await this.load()}} catch(e) {if(channel===this.channelId)this.error=e.message || '授权登记失败'} finally {if(channel===this.channelId)this.saving=false}},
    async revoke(row) {const channel=this.channelId;this.saving=true;this.error='';try {await request({url:this.endpoint()+`/${row.id}/revoke`,method:'post',data:{control_version:row.control_version}});if(channel===this.channelId)await this.load()} catch(e) {if(channel===this.channelId)this.error=e.message || '授权撤销失败'} finally {if(channel===this.channelId)this.saving=false}}
  }
}
</script>
<style scoped>
.authorization-fields{display:grid;grid-template-columns:1fr 1fr;gap:12px}.authorization-fields label{display:flex;flex-direction:column;gap:5px;color:#53667e;font-size:12px}.authorization-fields .el-input-number,.authorization-fields .el-date-editor{width:100%}.hint{color:#758397;font-size:12px}.error{color:#c45656}.money-authorizations{margin-top:10px}@media(max-width:700px){.authorization-fields{grid-template-columns:1fr}}
</style>
