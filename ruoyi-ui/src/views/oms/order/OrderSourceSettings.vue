<template><section class="order-source-settings">
  <div class="toolbar"><span>订单和退款共用连接管理，按业务分别检查读取能力；密码加密保存。</span><div><el-button size="small" type="primary" @click="editSource()">新增推送库</el-button><el-button size="small" @click="loadSources">刷新</el-button></div></div>
  <el-table v-loading="sourceLoading" :data="sources"><el-table-column label="名称" prop="source_name" min-width="170" /><el-table-column label="地址" min-width="170"><template slot-scope="s">{{ s.row.host }}:{{ s.row.port }}</template></el-table-column><el-table-column label="数据库" prop="database_name" min-width="140" /><el-table-column label="账号" prop="username" width="120" /><el-table-column label="状态" width="90"><template slot-scope="s"><el-tag size="small" :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '已启用' : '已停用' }}</el-tag></template></el-table-column><el-table-column label="最近连接 / 订单读取检查" min-width="180"><template slot-scope="s"><span :class="s.row.last_test_status==='FAILED'?'danger':''">{{ s.row.last_test_message }}</span><div class="muted">{{ s.row.last_test_time || '—' }}</div></template></el-table-column><el-table-column label="引用店铺" min-width="150"><template slot-scope="s">{{ s.row.bound_shops }} 个<div class="muted">订单 {{ s.row.bound_orders }} · 退款 {{ s.row.bound_refunds }}</div></template></el-table-column><el-table-column label="操作" width="230" fixed="right"><template slot-scope="s"><el-button type="text" @click="testSaved(s.row)">检查订单读取</el-button><el-button type="text" @click="editSource(s.row)">编辑</el-button><el-button type="text" @click="toggleSource(s.row)">{{ s.row.enabled?'停用':'启用' }}</el-button><el-button type="text" class="danger" @click="removeSource(s.row)">删除</el-button></template></el-table-column></el-table>
  <el-dialog :title="source.source_key ? '编辑推送库' : '新增推送库'" :visible.sync="sourceVisible" :before-close="closeSource" width="640px" append-to-body :close-on-click-modal="false">
    <el-form ref="sourceForm" :model="source" :rules="sourceRules" label-width="120px">
      <el-form-item label="名称" prop="source_name"><el-input v-model.trim="source.source_name" maxlength="100" /></el-form-item>
      <el-form-item label="数据库地址" prop="host"><el-input v-model.trim="source.host" placeholder="IP或主机名" :disabled="source.bound_shops > 0" /></el-form-item>
      <el-form-item label="端口" prop="port"><el-input-number v-model="source.port" :min="1" :max="65535" :disabled="source.bound_shops > 0" /></el-form-item>
      <el-form-item label="数据库名" prop="database_name"><el-input v-model.trim="source.database_name" :disabled="source.bound_shops > 0" /></el-form-item>
      <el-form-item label="数据库账号" prop="username"><el-input v-model.trim="source.username" autocomplete="off" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="source.password" show-password autocomplete="new-password" :placeholder="source.source_key ? '留空保留原密码，输入新密码即可修改' : '填写数据库密码'" /></el-form-item>
      <el-form-item label="启用"><el-switch v-model="source.enabled" :active-value="1" :inactive-value="0" /><p class="muted">启用时会检查连接和订单表。已引用来源的地址需通过新增来源调整。</p></el-form-item>
    </el-form><span slot="footer"><el-button @click="closeSource()">取消</el-button><el-button :loading="testing" @click="testSource">测试连接</el-button><el-button type="primary" :loading="saving" @click="saveSource">保存</el-button></span>
  </el-dialog>
</section></template>
<script>
import configurationEditor from './configurationEditor'
import { pushSources, savePushSource, testPushSource, testSavedPushSource, deletePushSource } from '@/api/order'
const required = message => ({ required: true, message, trigger: 'change' })
export default {
  name: 'OrderSourceSettings', mixins: [configurationEditor],
  data: () => ({ loading: false, sourceLoading: false, saving: false, testing: false, sourceVisible: false, source: {}, sources: [], sourceRules: { source_name: [required('请填写名称')], host: [required('请填写地址')], port: [required('请填写端口')], database_name: [required('请填写数据库名')], username: [required('请填写账号')] }}),
  computed: { editorState() { return this.sourceVisible ? this.source : null } },
  watch: { sourceVisible(value) { if (!value) this.editorBaseline = null } },
  created() { this.load() },
  methods: {
    async load() { this.loading = this.sourceLoading = true; try { this.sources = (await pushSources()).data } finally { this.loading = this.sourceLoading = false } },
    loadSources() { return this.load() },
    async closeSource(done) { if (this.saving) return; if (this.dirty) { try { await this.$confirm('推送库有未保存修改，是否放弃？', '未保存修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' }) } catch (_) { return } } this.sourceVisible = false; if (typeof done === 'function') done() },
    editSource(row) { this.source = row ? { ...row, password: '' } : { source_name: '', host: '', port: 3306, database_name: 'sys_info', username: '', password: '', enabled: 1, revision: 0, bound_shops: 0 }; this.sourceVisible = true; this.$nextTick(() => { this.$refs.sourceForm.clearValidate(); this.acceptBaseline() }) },
    sourceValid(valid) { if (!valid) return false; if (!this.source.source_key && !this.source.password) { this.$message.warning('新增推送库请填写密码'); return false } return true },
    testSource() { this.$refs.sourceForm.validate(async valid => { if (!this.sourceValid(valid) || this.testing) return; this.testing = true; try { await testPushSource(this.source); this.$message.success('连接和订单表检查通过') } finally { this.testing = false } }) },
    saveSource() { this.$refs.sourceForm.validate(async valid => { if (!this.sourceValid(valid) || this.saving) return; this.saving = true; try { if (this.source.source_key && !this.source.enabled && this.source.bound_shops) await this.$confirm(`停用将影响 ${this.source.bound_shops} 个店铺的后续订单和退款同步，确认停用？`, '停用推送库'); await savePushSource(this.source); this.sourceVisible = false; this.$message.success('推送库已保存'); await this.load() } catch (e) { if (e !== 'cancel' && e !== 'close') throw e } finally { this.saving = false } }) },
    async testSaved(row) { await testSavedPushSource(row.source_key); this.$message.success('连接和订单表检查通过'); await this.load() },
    async toggleSource(row) { try { await this.$confirm(`${row.enabled ? '停用' : '启用'}“${row.source_name}”${row.bound_shops ? `，关联 ${row.bound_shops} 个店铺` : ''}？`, '推送库状态'); await savePushSource({ ...row, enabled: row.enabled ? 0 : 1, password: '' }); await this.load() } catch (e) { if (e !== 'cancel' && e !== 'close') throw e } },
    async removeSource(row) { if (row.bound_shops) return this.$message.warning(`已被 ${row.bound_shops} 个店铺引用，请先调整店铺配置`); try { await this.$confirm(`删除推送库“${row.source_name}”？`, '删除推送库'); await deletePushSource(row.source_key); await this.load() } catch (e) { if (e !== 'cancel' && e !== 'close') throw e } }
  }
}
</script>
<style scoped>.toolbar{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:20px}.muted{font-size:12px;color:#758397;line-height:1.8}.danger{color:#d35858}</style>
