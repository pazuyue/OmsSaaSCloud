<template>
  <div class="app-container company-page">
    <div class="heading"><div><h2>公司管理</h2><p>维护公司资料、服务接入与启用状态</p></div><el-button type="primary" icon="el-icon-plus" @click="create">新增公司</el-button></div>
    <el-form inline size="small" @submit.native.prevent="search">
      <el-form-item label="公司"><el-input v-model="query.query" placeholder="编码或名称前缀" clearable maxlength="100" @keyup.enter.native="search" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="query.status" clearable placeholder="全部"><el-option v-for="(name,key) in statuses" :key="key" :label="name" :value="key" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="resetSearch">重置</el-button></el-form-item>
    </el-form>
    <el-table :data="rows" v-loading="loading">
      <el-table-column label="公司" min-width="230"><template slot-scope="s"><el-link type="primary" @click="open(s.row.company_code)">{{ s.row.company_name }}</el-link><div class="muted">{{ s.row.company_code }}</div></template></el-table-column>
      <el-table-column label="公司状态" width="130"><template slot-scope="s"><el-tag size="small" :type="statusType(s.row.status)">{{ statuses[s.row.status] }}</el-tag></template></el-table-column>
      <el-table-column label="服务配置" width="120"><template slot-scope="s">{{ s.row.route_count }} / 5</template></el-table-column>
      <el-table-column label="最近生效检测" min-width="240"><template slot-scope="s"><span :class="s.row.runtime_ready ? 'success' : 'muted'">{{ s.row.runtime_ready ? '全部实例已生效' : s.row.release_id ? '待检测 / 待生效' : s.row.status === 'ENABLED' ? '既有接入（尚未纳管）' : '尚未发布' }}</span><div class="muted">{{ time(s.row.checked_at) }}</div></template></el-table-column>
      <el-table-column prop="updated_at" label="更新时间" width="180"><template slot-scope="s">{{ time(s.row.updated_at) }}</template></el-table-column>
      <el-table-column label="操作" width="120"><template slot-scope="s"><el-button type="text" @click="open(s.row.company_code)">配置与记录</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />

    <el-drawer :visible.sync="visible" :wrapper-closable="!busy" :before-close="close" size="min(1120px, 96vw)" :title="form.revision ? '公司接入 · '+form.company_code : '新增公司'" append-to-body>
      <div class="drawer-body" v-loading="detailLoading">
        <el-alert v-if="detail && detail.execution_state!==detail.status" type="warning" :closable="false" title="公司状态与执行开关尚未同步，请同步后确认结果。" show-icon class="gap" />
        <el-steps :active="step" finish-status="success" simple class="gap"><el-step title="资料与服务"/><el-step title="校验连接"/><el-step title="发布并生效"/><el-step title="启用公司"/></el-steps>
        <el-tabs v-model="tab">
          <el-tab-pane label="接入配置" name="config">
            <el-form ref="form" :model="form" :rules="rules" label-width="100px" size="small" :disabled="!!busy">
              <h3>公司资料 <el-tag v-if="detail" size="small" :type="statusType(detail.status)">{{ statuses[detail.status] }}</el-tag></h3>
              <el-row :gutter="20"><el-col :span="12"><el-form-item label="公司编码" prop="company_code"><el-input v-model="form.company_code" :disabled="!!form.revision" maxlength="64" placeholder="字母、数字、下划线或短横线" /></el-form-item></el-col><el-col :span="12"><el-form-item label="公司名称" prop="company_name"><el-input v-model="form.company_name" maxlength="100" /></el-form-item></el-col></el-row>
              <el-row :gutter="20"><el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contact_name" maxlength="50" /></el-form-item></el-col><el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.contact_phone" maxlength="32" /></el-form-item></el-col></el-row>
              <el-form-item label="备注"><el-input type="textarea" v-model="form.remark" maxlength="500" show-word-limit /></el-form-item>
              <div class="section-title"><h3>服务配置</h3><el-button :loading="catalogLoading" :disabled="!!busy" @click="discover" size="small" icon="el-icon-refresh">获取可用连接</el-button></div>
              <p class="muted">默认使用各服务已准备的共享连接。独立部署时，先由运维准备连接，再在这里选择。已启用过的公司需通过迁移流程换库。</p>
              <el-table :data="services" class="gap">
                <el-table-column prop="name" label="业务服务" width="120" />
                <el-table-column label="服务内连接" min-width="170"><template slot-scope="s"><el-select v-model="form.routes[s.row.key]" :disabled="!!(detail && detail.ever_enabled) || busy" placeholder="选择已准备的连接"><el-option v-for="alias in aliases(s.row.key)" :key="alias" :value="alias" :label="alias==='master' ? '共享连接（master）' : alias" /></el-select></template></el-table-column>
                <el-table-column label="连接校验" min-width="150"><template slot-scope="s"><span>{{ reportService('last_check',s.row.key).message || '尚未校验' }}</span></template></el-table-column>
                <el-table-column label="实际生效" min-width="170"><template slot-scope="s"><el-tag size="small" :type="reportService('last_detect',s.row.key).ready ? 'success' : 'info'">{{ reportService('last_detect',s.row.key).ready ? '全部实例已生效' : '待检测 / 待生效' }}</el-tag></template></el-table-column>
              </el-table>
              <el-button type="primary" :loading="busy==='save'" @click="save">保存草稿</el-button>
            </el-form>
            <div v-if="detail" class="workflow">
              <h3>接入检查与启用</h3>
              <el-alert :title="guidance" :type="detail.status==='ENABLED' ? 'success' : 'info'" :closable="false" show-icon />
              <div class="actions">
                <el-button :disabled="!!busy || dirty" :loading="busy==='check'" @click="act('check')">1. 校验连接</el-button>
                <el-button :disabled="!!busy || dirty" :loading="busy==='publish'" @click="publish">2. 发布配置</el-button>
                <el-button :disabled="!!busy || dirty" :loading="busy==='detect'" @click="act('detect')">3. 检测生效</el-button>
                <el-button type="primary" v-if="detail.status!=='ENABLED'" :disabled="!!busy || dirty || !ready" :loading="busy==='enable'" @click="confirmStatus('enable')">4. 启用公司</el-button>
                <el-button type="danger" plain v-else :disabled="!!busy || dirty || !detail.release_id || !ready" @click="confirmStatus('disable')">停用公司</el-button>
              </div>
              <p v-if="detail.status==='ENABLED' && !detail.release_id" class="muted">既有接入尚未纳管。发布并检测生效后，才能使用公司停用功能。</p>
              <p v-if="dirty" class="warning">配置已修改，请先保存。</p>
              <p class="muted">发布后以实际检测结果为准。尚未加载新配置的实例需部署 / 重启；发布成功不代表已经生效。</p>
              <p v-if="detail.release_id" class="muted">当前发布版本：{{ detail.release_id }}</p>
              <el-button v-if="detail.execution_state!==detail.status" size="small" :disabled="!!busy" @click="act('sync-state')">同步执行开关</el-button>
              <el-button type="text" :disabled="!!busy" @click="users">管理本公司用户</el-button>
            </div>
          </el-tab-pane>
          <el-tab-pane label="实例检测" name="instances">
            <p class="muted">{{ detail && detail.last_detect.checked_at ? '最近检测：'+time(detail.last_detect.checked_at) : '尚未检测。请在接入配置中点击“检测生效”。' }}</p>
            <div v-for="s in detectedServices" :key="s.key" class="instance-group"><h3>{{ s.name }} <el-tag size="small" :type="s.ready?'success':'warning'">{{ s.ready?'已就绪':'待处理' }}</el-tag></h3><p>{{ s.message }}</p><el-table :data="s.instances || []"><el-table-column prop="instance" label="实例" min-width="150"/><el-table-column prop="loaded_alias" label="实际连接"/><el-table-column prop="database_name" label="实际数据库" min-width="180"/><el-table-column prop="loaded_version" label="加载版本" min-width="220"/><el-table-column prop="message" label="结果" min-width="220"/></el-table></div>
          </el-tab-pane>
          <el-tab-pane label="发布记录" name="releases"><el-table :data="detail ? detail.releases : []"><el-table-column prop="id" label="发布版本" min-width="280"/><el-table-column label="结果" width="150"><template slot-scope="s">{{ releaseName(s.row.state) }}</template></el-table-column><el-table-column prop="created_by" label="操作人"/><el-table-column label="时间" width="170"><template slot-scope="s">{{ time(s.row.updated_at) }}</template></el-table-column><el-table-column label="操作" width="120"><template slot-scope="s"><el-button v-if="['PUBLISHING','ROLLBACK_FAILED'].includes(s.row.state)" type="text" :disabled="!!busy" @click="recover(s.row)">恢复本次发布</el-button></template></el-table-column></el-table><p class="muted">保留最近 20 次发布。不完整发布需先恢复，恢复仅处理该公司的本次配置。</p></el-tab-pane>
          <el-tab-pane label="操作记录" name="events"><el-table :data="detail ? detail.events : []"><el-table-column prop="message" label="说明" min-width="320"/><el-table-column prop="result" label="结果" width="140"/><el-table-column prop="operator_name" label="操作人" width="120"/><el-table-column label="时间" width="170"><template slot-scope="s">{{ time(s.row.created_at) }}</template></el-table-column></el-table><p class="muted">最近 50 条记录</p></el-tab-pane>
        </el-tabs>
      </div>
      <div class="drawer-footer"><el-button :disabled="!!busy" @click="refresh">刷新详情</el-button><el-button :disabled="!!busy" @click="close">关闭</el-button></div>
    </el-drawer>
  </div>
</template>
<script>
import { listCompanies, companyDetail, companyCatalog, saveCompany, companyAction } from '@/api/system/company'
const services = [{ key: 'order', name: '订单' }, { key: 'inventory', name: '库存' }, { key: 'supplychain', name: '供应链' }, { key: 'goods', name: '商品' }, { key: 'channel', name: '渠道' }]
const blank = () => ({ company_code: '', company_name: '', contact_name: '', contact_phone: '', remark: '', revision: 0, routes: Object.fromEntries(services.map(s => [s.key, 'master'])) })
export default {
  name: 'Company',
  data: () => ({ services, statuses: { DRAFT: '未启用', ENABLED: '已启用', DISABLED: '已停用' }, query: { query: '', status: '', pageNum: 1, pageSize: 10 }, rows: [], total: 0, loading: false, visible: false, detailLoading: false, catalogLoading: false, busy: '', tab: 'config', detail: null, form: blank(), saved: '', catalog: [], requestId: '', rules: { company_code: [{ required: true, pattern: /^[A-Za-z0-9_-]{1,64}$/, message: '填写有效公司编码', trigger: 'blur' }], company_name: [{ required: true, whitespace: true, message: '填写公司名称', trigger: 'blur' }] } }),
  computed: {
    dirty() { return this.saved !== JSON.stringify(this.form) },
    ready() { return !!(this.detail && this.detail.release_id && this.detail.last_detect.ready && this.detail.last_detect.version === this.detail.release_id) },
    step() { return !this.detail ? 0 : this.detail.status === 'ENABLED' ? 4 : this.ready ? 3 : this.detail.release_id ? 2 : this.detail.last_check.ready ? 2 : 1 },
    detectedServices() { return this.detail && this.detail.last_detect.services || [] },
    guidance() { return this.detail.status === 'ENABLED' ? '公司已启用。修改资料不影响现有业务。' : this.ready ? '所有服务实例已加载本次配置，可以启用公司。' : this.detail.release_id ? '配置已发布，请检测实际生效情况；未加载新配置的实例需部署 / 重启。' : '先校验五个服务的可用连接与必要结构，再发布配置。' }
  },
  created() { this.load() },
  methods: {
    time(value) { return value ? this.parseTime(value) : '—' },
    statusType(state) { return state === 'ENABLED' ? 'success' : state === 'DISABLED' ? 'danger' : 'info' },
    releaseName(state) { return { PUBLISHING: '发布中 / 待恢复', PUBLISHED: '已发布', ROLLED_BACK: '已恢复', ROLLBACK_FAILED: '恢复待处理' }[state] || state },
    async load() { this.loading = true; try { const r = await listCompanies(this.query); this.rows = r.rows; this.total = r.total } finally { this.loading = false } },
    search() { this.query.pageNum = 1; this.load() },
    resetSearch() { this.query.query = ''; this.query.status = ''; this.search() },
    create() { this.detail = null; this.form = blank(); this.saved = JSON.stringify(this.form); this.requestId = ''; this.tab = 'config'; this.visible = true; this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate()) },
    hydrate(detail) { this.detail = detail; this.form = Object.assign(blank(), ...['company_code', 'company_name', 'contact_name', 'contact_phone', 'remark', 'revision', 'routes'].map(k => ({ [k]: detail[k] }))); this.saved = JSON.stringify(this.form) },
    async open(code) { this.visible = true; this.detailLoading = true; this.tab = 'config'; this.requestId = ''; try { this.hydrate((await companyDetail(code)).data) } finally { this.detailLoading = false } },
    async refresh() { if (this.form.revision) this.hydrate((await companyDetail(this.form.company_code)).data) },
    async close(done) { if (this.busy) return; if (this.dirty) { try { await this.$confirm('有未保存的修改，确认关闭？', '提示') } catch (_) { return } } this.visible = false; if (typeof done === 'function') done() },
    aliases(key) { const entry = this.catalog.find(s => s.key === key); return [...new Set([this.form.routes[key], ...(entry && entry.available_aliases || [])])].filter(Boolean) },
    async discover() { this.catalogLoading = true; try { this.catalog = (await companyCatalog()).data.services; this.$message.success('已获取各服务全部实例共有的连接') } finally { this.catalogLoading = false } },
    reportService(report, key) { return this.detail && (this.detail[report].services || []).find(s => s.key === key) || {} },
    async save() { try { await this.$refs.form.validate() } catch (_) { return } this.busy = 'save'; try { this.hydrate((await saveCompany(this.form)).data); this.requestId = ''; this.$message.success('草稿已保存'); await this.load() } finally { this.busy = '' } },
    async act(action, extra = {}) { if (this.busy || this.dirty) return; this.busy = action; try { await companyAction(this.form.company_code, action, { revision: this.form.revision, ...extra }); if (action === 'publish') this.requestId = ''; this.$message.success(['check', 'detect'].includes(action) ? '检测已完成，请查看结果' : '操作已完成') } finally { try { await this.refresh(); await this.load() } finally { this.busy = '' } } },
    async publish() { try { await this.$confirm('将发布公司 '+this.form.company_code+' 的五个服务连接映射。发布后需检测实际生效，才能启用。尚未加载新配置的实例需部署 / 重启。', '发布接入配置', { type: 'warning' }) } catch (_) { return } if (!this.requestId) this.requestId = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join(''); return this.act('publish', { request_id: this.requestId }) },
    async confirmStatus(action) { try { await this.$confirm(action === 'enable' ? '启用后允许该公司的用户登录及新增业务，确认启用？' : '停用将停止新增业务，已有单据的仓库回传、记账与人工核对继续处理，确认停用？', action === 'enable' ? '启用公司' : '停用公司', { type: 'warning' }) } catch (_) { return } return this.act(action) },
    async recover(row) { try { await this.$confirm('将恢复该发布前的公司映射。已被其他操作修改的配置会保留并提示人工处理。', '恢复本次发布', { type: 'warning' }) } catch (_) { return } return this.act('releases/'+row.id+'/recover') },
    users() { this.$router.push({ path: '/system/user', query: { company_code: this.form.company_code } }) }
  }
}
</script>
<style scoped>
.heading,.section-title{display:flex;align-items:center;justify-content:space-between}.heading h2{margin:0 0 8px}.heading p,.muted{color:#8492a6;font-size:13px;line-height:1.7}.heading{margin-bottom:24px}.drawer-body{padding:0 28px 90px;overflow:auto;height:calc(100vh - 100px)}.gap{margin-bottom:20px}.workflow{margin-top:30px;padding-top:12px;border-top:1px solid #e5eaf1}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:20px}.actions .el-button{margin-left:0}.drawer-footer{position:absolute;bottom:0;right:0;left:0;background:white;border-top:1px solid #e5eaf1;padding:16px 28px;text-align:right}.success{color:#23855a}.warning{color:#b57a13}.instance-group{margin:20px 0 30px}h3{font-size:16px;margin:20px 0}.company-page{max-width:1600px}
</style>
