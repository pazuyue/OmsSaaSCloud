<template>
  <div class="app-container order-management">
    <p class="intro">维护订单接入、推送库连接与拉单任务。</p>
    <el-tabs v-model="tab">
      <el-tab-pane v-if="can('order:pull:config')" label="店铺接入" name="shops">
        <div class="toolbar"><span>每个店铺独立维护拉取进度</span><div><el-button size="small" type="primary" @click="editShop()">新增店铺接入</el-button><el-button size="small" @click="load">刷新</el-button></div></div>
        <el-table v-loading="loading" :data="configs"><el-table-column label="OMS 店铺" prop="channel_name" min-width="180" /><el-table-column label="推送库卖家账号" prop="seller_nick" min-width="180" show-overflow-tooltip /><el-table-column label="推送库" min-width="170"><template slot-scope="s">{{ s.row.source_name || s.row.source_key }}<el-tag v-if="!s.row.source_enabled" size="mini" type="danger">已停用</el-tag></template></el-table-column><el-table-column label="自动拉单" width="100"><template slot-scope="s"><el-tag size="small" :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '已启用' : '已暂停' }}</el-tag></template></el-table-column><el-table-column label="拉取间隔" width="100"><template slot-scope="s">{{ s.row.interval_seconds }} 秒</template></el-table-column><el-table-column label="拉取进度" prop="cursor_time" width="170" /><el-table-column label="最近说明" prop="last_error" min-width="200" show-overflow-tooltip /><el-table-column label="操作" width="100" fixed="right"><template slot-scope="s"><el-button type="text" @click="editShop(s.row)">接入设置</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane v-if="can('order:source:config')" label="推送库配置" name="sources">
        <div class="toolbar"><span>连接密码加密保存，拉取只读取源库</span><div><el-button size="small" type="primary" @click="editSource()">新增推送库</el-button><el-button size="small" @click="load">刷新</el-button></div></div>
        <el-table v-loading="loading" :data="sources"><el-table-column label="名称" prop="source_name" min-width="170" /><el-table-column label="地址" min-width="170"><template slot-scope="s">{{ s.row.host }}:{{ s.row.port }}</template></el-table-column><el-table-column label="数据库" prop="database_name" min-width="140" /><el-table-column label="账号" prop="username" width="120" /><el-table-column label="状态" width="90"><template slot-scope="s"><el-tag size="small" :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '已启用' : '已停用' }}</el-tag></template></el-table-column><el-table-column label="最近连接测试" min-width="180"><template slot-scope="s"><span :class="s.row.last_test_status === 'FAILED' ? 'danger' : ''">{{ s.row.last_test_message }}</span><div class="muted">{{ s.row.last_test_time || '—' }}</div></template></el-table-column><el-table-column label="引用店铺" prop="bound_shops" width="90" /><el-table-column label="操作" width="210" fixed="right"><template slot-scope="s"><el-button type="text" @click="testSaved(s.row)">测试</el-button><el-button type="text" @click="editSource(s.row)">编辑</el-button><el-button type="text" @click="toggleSource(s.row)">{{ s.row.enabled ? '停用' : '启用' }}</el-button><el-button type="text" class="danger" @click="removeSource(s.row)">删除</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="拉单任务" name="tasks"><order-workspace v-if="tab === 'tasks'" view-override="tasks" embedded /></el-tab-pane>
    </el-tabs>
    <el-dialog title="店铺接入配置" :visible.sync="shopVisible" width="700px" append-to-body :close-on-click-modal="false">
      <el-form ref="shopForm" :model="shop" :rules="shopRules" label-width="140px">
        <div class="section-title">拉单设置</div>
        <el-form-item label="天猫店铺" prop="channel_id"><el-select v-model="shop.channel_id" :disabled="!!shop.id" filterable @change="selectShop"><el-option v-for="s in catalog.channels.filter(s => s.enabled)" :key="s.channel_id" :value="s.channel_id" :label="s.channel_name" /></el-select></el-form-item>
        <el-form-item label="平台授权名称"><span>{{ selectedShop.seller_nick || '暂无授权名称' }}</span><p class="muted">用于核对商家身份，可与 OMS 店铺名称、推送库卖家账号不同。</p></el-form-item>
        <el-form-item label="推送库" prop="source_key"><el-select v-model="shop.source_key" @change="changeSource"><el-option v-for="s in catalog.sources" :key="s.key" :value="s.key" :label="s.label + (s.enabled ? '' : '（已停用）')" :disabled="!s.enabled" /></el-select><p v-if="!catalog.sources.some(s => s.enabled)" class="muted">请先在推送库配置中新增并启用来源。</p></el-form-item>
        <el-form-item label="推送库卖家账号" prop="seller_nick"><el-input v-model.trim="shop.seller_nick" @change="changeSource" placeholder="与源表 seller_nick 一致" /></el-form-item>
        <el-form-item v-if="sellerMismatch"><el-alert title="推送库卖家账号与平台授权名称不同，请核对映射。" type="warning" :closable="false" show-icon /><el-checkbox v-if="!shop.id || sourceChanged" v-model="shop.confirm_seller_mapping" :true-label="1" :false-label="0">确认该推送库账号的订单归属所选 OMS 店铺</el-checkbox><p v-else class="muted">当前映射已保存，修改开关或间隔无需重复确认。</p></el-form-item>
        <el-form-item label="来源验证时间"><el-date-picker v-model="verifyRange" type="datetimerange" value-format="yyyy-MM-dd HH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" style="width:100%" /><p class="muted">按推送更新时间查询，最多90天；验证不拉单、不改变进度。</p><el-button size="small" :loading="verifying" @click="verifySource">验证拉单来源</el-button>
          <template v-if="verification"><el-alert :title="verification.message" :type="verification.orders.length ? 'success' : 'info'" :closable="false" /><el-table v-if="verification.orders.length" :data="verification.orders" size="mini"><el-table-column label="平台订单号" prop="tid" min-width="170" /><el-table-column label="推送更新时间" prop="jdp_modified" min-width="155" /></el-table></template>
        </el-form-item>
        <el-form-item label="首次拉取起点" prop="initial_time"><el-date-picker v-model="shop.initial_time" :disabled="!!shop.id && !sourceChanged" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" /></el-form-item>
        <el-form-item v-if="sourceChanged"><el-checkbox v-model="shop.reset_cursor" :true-label="1" :false-label="0">确认切换来源或卖家，从上述起点重新拉取</el-checkbox><p class="muted">原单和业务订单保留；重复订单按原身份去重。</p></el-form-item>
        <el-form-item label="自动拉单"><el-switch v-model="shop.enabled" :active-value="1" :inactive-value="0" /><span class="spacer">间隔</span><el-input-number v-model="shop.interval_seconds" :min="10" :max="86400" size="small" controls-position="right" /> 秒</el-form-item>
      </el-form><span slot="footer"><el-button @click="shopVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveShop">保存</el-button></span>
    </el-dialog>
    <el-dialog :title="source.source_key ? '编辑推送库' : '新增推送库'" :visible.sync="sourceVisible" width="640px" append-to-body :close-on-click-modal="false">
      <el-form ref="sourceForm" :model="source" :rules="sourceRules" label-width="120px">
        <el-form-item label="名称" prop="source_name"><el-input v-model.trim="source.source_name" maxlength="100" /></el-form-item>
        <el-form-item label="数据库地址" prop="host"><el-input v-model.trim="source.host" placeholder="IP或主机名" :disabled="source.bound_shops > 0" /></el-form-item>
        <el-form-item label="端口" prop="port"><el-input-number v-model="source.port" :min="1" :max="65535" :disabled="source.bound_shops > 0" /></el-form-item>
        <el-form-item label="数据库名" prop="database_name"><el-input v-model.trim="source.database_name" :disabled="source.bound_shops > 0" /></el-form-item>
        <el-form-item label="数据库账号" prop="username"><el-input v-model.trim="source.username" autocomplete="off" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="source.password" show-password autocomplete="new-password" :placeholder="source.source_key ? '留空保留原密码，输入新密码即可修改' : '填写数据库密码'" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="source.enabled" :active-value="1" :inactive-value="0" /><p class="muted">启用时会检查连接和订单表。已引用来源的地址需通过新增来源调整。</p></el-form-item>
      </el-form><span slot="footer"><el-button @click="sourceVisible=false">取消</el-button><el-button :loading="testing" @click="testSource">测试连接</el-button><el-button type="primary" :loading="saving" @click="saveSource">保存</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import OrderWorkspace from './index'
import { orderCatalog, pullConfigs, savePullConfig, verifyPullSource, pushSources, savePushSource, testPushSource, testSavedPushSource, deletePushSource } from '@/api/order'
import { parseTime } from '@/utils/ruoyi'
const required = message => ({ required: true, message, trigger: 'change' })
export default {
  name: 'OrderManagement', components: { OrderWorkspace },
  data() { return { tab: 'shops', loading: false, saving: false, testing: false, verifying: false, verification: null, verifyRange: [], catalog: { channels: [], sources: [] }, configs: [], sources: [], shopVisible: false, sourceVisible: false, shop: {}, source: {}, originalSource: '', originalSeller: '', originalTime: '', shopRules: { channel_id: [required('请选择店铺')], source_key: [required('请选择推送库')], seller_nick: [required('请填写卖家账号')], initial_time: [required('请选择首次拉取起点')] }, sourceRules: { source_name: [required('请填写名称')], host: [required('请填写地址')], port: [required('请填写端口')], database_name: [required('请填写数据库名')], username: [required('请填写账号')] } } },
  computed: { selectedShop() { return this.catalog.channels.find(s => s.channel_id === this.shop.channel_id) || {} }, sellerMismatch() { return !!this.selectedShop.seller_nick && !!this.shop.seller_nick && this.selectedShop.seller_nick !== this.shop.seller_nick }, mappingKey() { return JSON.stringify([this.shop.channel_id, this.shop.source_key, this.shop.seller_nick]) }, verificationKey() { return JSON.stringify([this.mappingKey, this.verifyRange]) }, sourceChanged() { return !!this.shop.id && (this.originalSource !== this.shop.source_key || this.originalSeller !== this.shop.seller_nick) } },
  watch: { '$route.query.channelId'() { this.openRequestedShop() }, mappingKey() { this.$set(this.shop, 'confirm_seller_mapping', 0) }, verificationKey() { this.verification = null } },
  created() { if (!this.can('order:pull:config')) this.tab = this.can('order:source:config') ? 'sources' : 'tasks'; this.load().then(() => this.openRequestedShop()) },
  methods: {
    openRequestedShop() { if (!this.can('order:pull:config')) return; const id = this.$route.query.channelId; const row = this.configs.find(c => String(c.channel_id) === String(id)); if (row) { this.tab = 'shops'; this.editShop(row) } },
    can(permission) { const p = this.$store.getters.permissions || []; return p.includes('*:*:*') || p.includes(permission) },
    async load() { this.loading = true; try { const [catalog, configs, sources] = await Promise.all([orderCatalog(), this.can('order:pull:config') ? pullConfigs() : Promise.resolve({ data: [] }), this.can('order:source:config') ? pushSources() : Promise.resolve({ data: [] })]); this.catalog = catalog.data; this.configs = configs.data; this.sources = sources.data } finally { this.loading = false } },
    editShop(row) { this.verification = null; this.verifyRange = [parseTime(new Date(Date.now() - 86400000)), parseTime(new Date())]; this.shop = row ? { ...row, allowed_trade_types: [...row.allowed_trade_types], reset_cursor: 0, confirm_seller_mapping: 0 } : { channel_id: null, source_key: '', seller_nick: '', enabled: 0, auto_convert: 0, allowed_trade_types: ['fixed', 'guarantee_trade', 'tmall_i18n'], allow_public_stock: 0, interval_seconds: 30, initial_time: '', revision: 0, confirm_seller_mapping: 0 }; this.originalSource = this.shop.source_key; this.originalSeller = this.shop.seller_nick; this.originalTime = this.shop.initial_time; this.shopVisible = true; this.$nextTick(() => this.$refs.shopForm.clearValidate()) },
    selectShop(id) { const s = this.catalog.channels.find(s => s.channel_id === id); this.shop.seller_nick = s && s.seller_nick || '' },
    changeSource() { if (!this.shop.id) return; this.shop.initial_time = this.sourceChanged ? '' : this.originalTime; this.shop.reset_cursor = 0 },
    saveShop() { this.$refs.shopForm.validate(async valid => { if (!valid || this.saving) return; if (this.sellerMismatch && (!this.shop.id || this.sourceChanged) && !this.shop.confirm_seller_mapping) return this.$message.warning('请确认推送库账号与 OMS 店铺的映射'); if (this.sourceChanged && !this.shop.reset_cursor) return this.$message.warning('请确认新的拉取起点'); this.saving = true; try { await savePullConfig(this.shop); this.shopVisible = false; this.$message.success('店铺配置已保存'); await this.load() } finally { this.saving = false } }) },
    async verifySource() {
      if (!this.shop.channel_id || !this.shop.source_key || !this.shop.seller_nick) return this.$message.warning('请先选择店铺、推送库并填写卖家账号');
      if (!this.verifyRange || this.verifyRange.length !== 2) return this.$message.warning('请选择验证时间范围');
      if (this.verifying) return;
      const key = this.verificationKey; this.verifying = true; this.verification = null;
      try { const response = await verifyPullSource({ channel_id: this.shop.channel_id, source_key: this.shop.source_key, seller_nick: this.shop.seller_nick, start_time: this.verifyRange[0], end_time: this.verifyRange[1] }); if (key === this.verificationKey) this.verification = response.data } finally { this.verifying = false }
    },
    editSource(row) { this.source = row ? { ...row, password: '' } : { source_name: '', host: '', port: 3306, database_name: 'sys_info', username: '', password: '', enabled: 1, revision: 0, bound_shops: 0 }; this.sourceVisible = true; this.$nextTick(() => this.$refs.sourceForm.clearValidate()) },
    sourceValid(valid) { if (!valid) return false; if (!this.source.source_key && !this.source.password) { this.$message.warning('新增推送库请填写密码'); return false } return true },
    testSource() { this.$refs.sourceForm.validate(async valid => { if (!this.sourceValid(valid) || this.testing) return; this.testing = true; try { await testPushSource(this.source); this.$message.success('连接和订单表检查通过') } finally { this.testing = false } }) },
    saveSource() { this.$refs.sourceForm.validate(async valid => { if (!this.sourceValid(valid) || this.saving) return; this.saving = true; try { if (this.source.source_key && !this.source.enabled && this.source.bound_shops) await this.$confirm(`停用将影响 ${this.source.bound_shops} 个店铺的后续拉单，确认停用？`, '停用推送库'); await savePushSource(this.source); this.sourceVisible = false; this.$message.success('推送库已保存'); await this.load() } catch (e) { if (e !== 'cancel' && e !== 'close') throw e } finally { this.saving = false } }) },
    async testSaved(row) { await testSavedPushSource(row.source_key); this.$message.success('连接和订单表检查通过'); await this.load() },
    async toggleSource(row) { try { await this.$confirm(`${row.enabled ? '停用' : '启用'}“${row.source_name}”${row.bound_shops ? `，关联 ${row.bound_shops} 个店铺` : ''}？`, '推送库状态'); await savePushSource({ ...row, enabled: row.enabled ? 0 : 1, password: '' }); await this.load() } catch (e) { if (e !== 'cancel' && e !== 'close') throw e } },
    async removeSource(row) { if (row.bound_shops) return this.$message.warning(`已被 ${row.bound_shops} 个店铺引用，请先调整店铺配置`); try { await this.$confirm(`删除推送库“${row.source_name}”？`, '删除推送库'); await deletePushSource(row.source_key); await this.load() } catch (e) { if (e !== 'cancel' && e !== 'close') throw e } }
  }
}
</script>
<style scoped>
.intro,.muted{color:#8490a2;font-size:13px}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:12px 0 20px}.section-title{font-weight:600;border-bottom:1px solid #e8edf3;padding:0 0 12px;margin:16px 0}.spacer{margin:0 10px 0 18px}.danger{color:#f56c6c}.muted{margin:6px 0 0}.order-management ::v-deep .order-workspace{padding:0}.order-management ::v-deep .el-form-item__content .el-select{width:100%}
</style>
