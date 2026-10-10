<template>
  <el-dialog title="仓库对接配置" :visible.sync="visible" width="960px" append-to-body :close-on-click-modal="false" @open="load">
    <el-alert title="配置可由多个虚仓复用；外部仓库、货主编码在虚仓维护。停用配置暂停新下发，已下发单据仍接收回传。" type="info" :closable="false" class="mb8" />
    <el-button size="small" type="primary" @click="edit()">新增对接配置</el-button>
    <el-table v-loading="loading" :data="rows" empty-text="尚未配置仓库平台">
      <el-table-column label="名称" prop="name" min-width="150" /><el-table-column label="平台" width="130"><template slot-scope="s">{{ s.row.provider === 'QIMEN' ? '奇门' : '京东虎符' }}</template></el-table-column><el-table-column label="环境" width="85"><template slot-scope="s">{{ s.row.environment === 'TEST' ? '测试' : '正式' }}</template></el-table-column><el-table-column label="状态" width="85"><template slot-scope="s">{{ s.row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="回调路径" min-width="290"><template slot-scope="s"><span class="callback-path">{{ callback(s.row) }}</span><el-button type="text" size="mini" @click="copy(callback(s.row))">复制路径</el-button></template></el-table-column><el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" @click="edit(s.row)">修改</el-button></template></el-table-column>
    </el-table>
    <div slot="footer"><el-button @click="visible = false">关闭</el-button></div>
    <el-dialog title="维护仓库对接配置" :visible.sync="editing" width="640px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" label-width="120px" size="small">
        <el-form-item label="配置名称" prop="name" :rules="required"><el-input v-model.trim="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="平台" prop="provider" :rules="required"><el-select v-model="form.provider" @change="form.api_version = form.provider === 'QIMEN' ? '2.0' : '1.0'"><el-option label="奇门" value="QIMEN" /><el-option label="京东虎符" value="JD_HUFU" /></el-select></el-form-item>
        <el-form-item label="协议版本" prop="api_version" :rules="required"><el-select v-model="form.api_version"><el-option v-if="form.provider === 'JD_HUFU'" label="1.0" value="1.0" /><el-option label="2.0" value="2.0" /></el-select></el-form-item>
        <el-form-item label="接口环境"><el-radio-group v-model="form.environment"><el-radio label="TEST">测试</el-radio><el-radio label="PRODUCTION">正式</el-radio></el-radio-group></el-form-item>
        <el-form-item label="接口地址" prop="endpoint" :rules="required"><el-input v-model.trim="form.endpoint" placeholder="由平台提供的接口地址" /><div class="hint">{{ form.provider === 'JD_HUFU' ? '填写 /router/service 基础地址，系统追加具体动作路径。' : '填写完整奇门网关地址，系统附加公共参数。' }}</div></el-form-item>
        <el-form-item label="AppKey" prop="app_key" :rules="required"><el-input v-model.trim="form.app_key" autocomplete="off" /></el-form-item>
        <el-form-item label="应用密钥" prop="secret" :rules="form.id ? [] : required"><el-input v-model="form.secret" type="password" show-password autocomplete="new-password" :placeholder="form.id ? '留空保留现有密钥' : '输入平台应用密钥'" /></el-form-item>
        <el-form-item label="customerId" prop="customer_id" :rules="required"><el-input v-model.trim="form.customer_id" placeholder="平台分配的商家 / 客户标识" /></el-form-item>
        <el-form-item label="允许下发"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="saving" @click="editing = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存配置</el-button></div>
    </el-dialog>
  </el-dialog>
</template>
<script>
import { connections, saveConnection } from '@/api/warehouse/integration'
export default {
  data() { return { visible: false, editing: false, loading: false, saving: false, rows: [], form: {}, required: [{ required: true, message: '请填写完整', trigger: 'blur' }] } },
  methods: {
    open() { this.visible = true },
    async load() { this.loading = true; try { this.rows = (await connections()).data } finally { this.loading = false } },
    edit(row) { this.form = row ? { ...row, secret: '' } : { name: '', provider: 'QIMEN', api_version: '2.0', environment: 'TEST', endpoint: '', app_key: '', secret: '', customer_id: '', enabled: 0 }; this.editing = true; this.$nextTick(() => this.$refs.form.clearValidate()) },
    save() { this.$refs.form.validate(async ok => { if (!ok || this.saving) return; this.saving = true; try { await saveConnection(this.form); this.editing = false; this.$modal.msgSuccess('配置已保存'); await this.load(); this.$emit('changed') } finally { this.saving = false } }) },
    callback(row) { return '/supplychain/wmsCallback/' + row.company_code.toLowerCase() + '/' + row.callback_key },
    async copy(value) { try { await navigator.clipboard.writeText(value); this.$modal.msgSuccess('已复制，请拼接实际公网网关域名') } catch (_) { this.$modal.msgError('复制失败，请手动复制路径') } }
  }
}
</script>
<style scoped>.callback-path{overflow-wrap:anywhere;font-size:12px}.hint{font-size:12px;color:#84909f}</style>
