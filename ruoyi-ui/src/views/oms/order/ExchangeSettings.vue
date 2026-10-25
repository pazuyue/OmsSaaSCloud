<template>
  <div v-loading="loading">
    <template v-if="!embedded"><div class="toolbar"><p class="hint">自动同步仅创建处理单；自动处理在“换货处理”配置；平台权限与实际库存分别核对。统一调度：天猫换货申请同步与履约扫描。</p><el-button size="small" @click="load">刷新</el-button></div>
    <el-table :data="rows">
      <el-table-column label="店铺" min-width="200"><template slot-scope="s">{{ s.row.channel_name }}<div class="hint">渠道 ID：{{ s.row.channel_id }}</div></template></el-table-column>
      <el-table-column label="自动同步" width="120"><template slot-scope="s"><el-tag :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '已开启' : '未开启' }}</el-tag></template></el-table-column>
      <el-table-column label="平台写操作" width="140"><template slot-scope="s">{{ s.row.write_enabled ? '允许提交平台操作' : '仅查询' }}</template></el-table-column>
      <el-table-column label="同步进度" min-width="220"><template slot-scope="s">{{ s.row.cursor_time || '尚未同步' }}<div class="error">{{ s.row.last_error }}</div></template></el-table-column>
      <el-table-column label="操作" width="130"><template slot-scope="s"><el-button type="text" @click="open(s.row)">配置换货接入</el-button></template></el-table-column>
    </el-table>
    </template><configuration-panel :embedded="embedded" :read-only="readOnly" title="换货接入设置" :visible.sync="visible" width="670px" append-to-body :close-on-click-modal="false">
      <template v-if="form">
        <el-alert :title="form.channel_name" :description="cap.availability || cap.auth_label || '请先完成店铺平台授权'" :type="cap.can_query ? 'info' : 'warning'" :closable="false" />
        <p v-if="cap.simulation" class="hint">当前为本地模拟授权，不代表真实天猫换货接口已获权限。</p>
        <el-form v-if="mode!=='authorization'" label-width="140px" style="margin-top:20px">
          <el-form-item v-if="mode!=='business'" label="自动同步"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
          <el-form-item v-if="mode!=='business'" label="自动转业务换货"><el-switch v-model="form.auto_convert" :active-value="1" :inactive-value="0" /><div class="hint">先保存平台申请，再转 HH；原单未匹配时保留来源并重试关联，自动审核或同意须在“换货处理”另行配置。</div></el-form-item>
          <el-form-item v-if="mode!=='business'" label="首次同步时间"><el-date-picker v-model="form.initial_time" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" :disabled="!!form.revision" /><div class="hint">首次保存后固定；补拉使用独立任务，不回退自动游标。</div></el-form-item>
          <el-form-item v-if="mode!=='intake'" label="允许平台写操作"><el-switch v-model="form.write_enabled" :active-value="1" :inactive-value="0" /><div class="hint">授权OMS提交平台写操作；自动同意、确认收货和回传运单由“换货处理”分别控制。</div></el-form-item>
        </el-form>
        <el-divider v-if="mode==='all'||mode==='authorization'">渠道授权能力</el-divider>
        <p v-if="mode==='all'||mode==='authorization'" class="hint">这里只控制 OMS 使用接口；平台真实权限以调用结果为准。</p>
        <div v-if="mode==='all'||mode==='authorization'" class="access-controls"><el-checkbox v-model="read" :disabled="!canEditAccess">启用换货查询</el-checkbox><el-checkbox v-model="write" :disabled="!read||!canEditAccess">启用换货写操作</el-checkbox>
        <el-button v-hasPermi="['channel:platform:config']" type="text" @click="saveAccess">保存渠道能力</el-button></div>
      </template>
      <span v-if="mode!=='authorization'" slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存店铺设置</el-button></span>
    </configuration-panel>
  </div>
</template>
<script>
import configurationEditor from './configurationEditor'
import { exchangeConfigs, saveExchangeConfig, exchangeCapability, exchangeAccess } from '@/api/order/exchange'
export default { mixins: [configurationEditor], name: 'ExchangeSettings', props: { mode: { type: String, default: 'all' } }, data: () => ({ loading: false, saving: false, rows: [], visible: false, form: null, cap: {}, read: false, write: false }), computed: { canEditAccess() { const p = this.$store.getters.permissions || []; return !this.readOnly && (p.includes('*:*:*') || p.includes('channel:platform:config')) }, editorState() { return this.form ? { form: this.form, read: this.read, write: this.write } : null } }, created() { if(!this.embedded)this.load() }, methods: {
  async load() { this.loading = true; try { this.rows = (await exchangeConfigs()).data } finally { this.loading = false } },
  async open(row) { this.loading = true; this.form = null; this.visible = true; this.editorBaseline = null; try { this.cap = (await exchangeCapability(row.channel_id)).data; this.form = { auto_convert: 0, ...row }; this.read = !!Number(this.cap.read_enabled); this.write = !!Number(this.cap.write_enabled); this.visible = true } finally { this.loading = false } },
  async saveAccess() { if (this.readOnly || this.saving) return; this.saving = true; try { await exchangeAccess(this.form.channel_id, { revision: this.cap.revision, read_enabled: this.read ? 1 : 0, write_enabled: this.read && this.write ? 1 : 0 }); this.cap = (await exchangeCapability(this.form.channel_id)).data; this.editorBaseline = JSON.stringify({ ...JSON.parse(this.editorBaseline || '{}'), read: this.read, write: this.write }); this.$message.success('渠道能力已保存') } finally { this.saving = false } },
  async save() { if (this.readOnly || this.saving) return; this.saving = true; try { await saveExchangeConfig(this.form); this.$message.success('换货接入设置已保存'); this.visible = false; this.$emit('changed'); if(!this.embedded)await this.load() } finally { this.saving = false } }
} }
</script>
<style scoped>.access-controls{display:flex;align-items:center;gap:18px;flex-wrap:wrap}.access-controls .el-checkbox{margin-right:0}.toolbar{display:flex;justify-content:space-between;align-items:center}.hint{color:#8492a6;font-size:12px;line-height:1.7}.error{color:#e6a23c}</style>
