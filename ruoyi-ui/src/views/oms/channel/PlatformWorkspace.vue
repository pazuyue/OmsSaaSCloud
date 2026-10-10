<template>
  <div>
    <el-drawer title="平台对接信息" :visible.sync="visible" size="min(960px, 96vw)" append-to-body :wrapper-closable="false" custom-class="channel-platform-drawer">
      <div class="platform-body" v-loading="loading">
        <template v-if="detail.channel_id">
          <h2>{{ detail.channel_name }} <el-tag size="small">天猫</el-tag> <el-tag v-if="detail.simulation" size="small" type="warning">模拟对接</el-tag></h2>
          <p class="muted">店铺 ID：{{ detail.channel_id }} · 平台授权用于调用店铺接口，内部操作权限沿用系统权限管理。</p>
          <el-alert v-if="detail.simulation" title="当前为本地模拟：授权、订购及店铺查询结果仅用于验收，不会请求真实天猫接口。" type="warning" :closable="false" show-icon />
          <el-alert :title="detail.availability" :type="['授权与服务均有效', '模拟对接成功（授权与服务有效）'].includes(detail.availability) ? 'success' : 'info'" :closable="false" show-icon />
          <section>
            <h3>对接配置</h3>
            <el-form label-position="top" :model="binding" class="binding-form">
              <el-form-item label="平台应用"><el-select v-model="binding.app_id" :disabled="detail.binding_locked || detail.busy" placeholder="请选择平台应用"><el-option v-for="a in apps" :key="a.id" :value="a.id" :label="a.name + (a.enabled ? '' : '（未启用）')" /></el-select></el-form-item>
              <el-form-item label="平台店铺 ID（sid）"><el-input v-model.trim="binding.expected_shop_id" :disabled="detail.binding_locked || detail.busy" maxlength="32" placeholder="填写淘宝店铺 sid，用于授权身份校验" /></el-form-item>
            </el-form>
            <p class="muted" v-if="!apps.length">尚未创建平台应用。可以先保存应用草稿，取得 AppKey 后再完善配置。</p>
            <p class="muted" v-if="detail.binding_locked">已有授权凭据。更换应用或店铺绑定前，请先停用本地授权。</p>
            <el-button v-hasPermi="['channel:platform:config']" size="small" :disabled="detail.binding_locked || detail.busy" :loading="busy" @click="saveBinding">保存对接配置</el-button>
            <el-button v-hasPermi="['channel:platform:config']" type="text" @click="manage('apps')">管理平台应用</el-button>
          </section>
          <div class="status-grid">
            <section>
              <h3>服务订购 <el-tag size="small" :type="statusType(detail.service_label)">{{ detail.service_label }}</el-tag></h3>
              <p>服务到期：{{ date(detail.service_expires_at) }}</p>
              <p class="muted">最后核验：{{ date(detail.service_checked_at) }}</p>
              <p class="muted" v-if="detail.service_stale">未核验或结果已超过 24 小时，请重新查询。</p>
              <el-button v-hasPermi="['channel:platform:query']" size="small" :disabled="!detail.can_query_subscription" :loading="busy" @click="act('query/subscription')">核验服务订购</el-button>
              <el-button size="small" :disabled="!detail.renewal_url" @click="renew">订购 / 续费</el-button>
              <p class="muted">到期时间以平台返回为准。续费后请重新核验订购；授权失效时需重新授权。</p>
            </section>
            <section>
              <h3>店铺授权 <el-tag size="small" :type="statusType(detail.auth_label)">{{ detail.auth_label }}</el-tag></h3>
              <p>授权到期：{{ date(detail.token_expires_at) }}</p>
              <p class="muted">授权账号：{{ detail.seller_nick || '—' }}</p>
              <p class="muted">核验店铺：{{ detail.shop_title || '—' }}</p>
              <el-button v-if="detail.simulation" v-hasPermi="['channel:platform:authorize']" type="primary" size="small" :disabled="!detail.can_simulate" :loading="busy" @click="act('simulate')">模拟授权成功</el-button>
              <el-button v-else v-hasPermi="['channel:platform:authorize']" type="primary" size="small" :disabled="!detail.can_authorize" :loading="busy" @click="authorize">{{ detail.auth_label === '未授权' || detail.auth_label === '待配置' ? '前往授权' : '重新授权' }}</el-button>
              <el-button v-hasPermi="['channel:platform:authorize']" size="small" :disabled="!detail.can_refresh" :loading="busy" @click="act('refresh')">刷新令牌</el-button>
              <p class="muted">刷新按钮仅在平台返回有效刷新令牌时可用。请使用对应店铺主账号授权。</p>
            </section>
          </div>
          <section v-if="detail.app_id">
            <h3>到期提醒</h3>
            <el-switch v-model="reminder.enabled" active-text="站内提醒" />
            <span class="reminder-label">提前</span><el-input-number v-model="reminder.days" :min="1" :max="90" size="small" /><span> 天</span>
            <el-button v-hasPermi="['channel:platform:config']" size="small" :loading="busy" @click="saveReminder">保存提醒设置</el-button>
            <p class="muted">在“到期提醒”集中显示；服务续费或授权更新后自动按新到期时间判断。</p>
          </section>
          <section v-if="detail.app_id">
            <el-button v-hasPermi="['channel:platform:query']" size="small" :disabled="!detail.can_query" :loading="busy" @click="act('query/shop')">查询平台店铺</el-button>
            <el-button v-hasPermi="['channel:platform:log']" size="small" @click="manage('logs', detail.channel_id)">查看交互日志</el-button>
            <el-button v-hasPermi="['channel:platform:authorize']" size="small" type="danger" plain :disabled="detail.busy" @click="disable">停用本地授权</el-button>
          </section>
        </template>
      </div>
      <div class="platform-footer"><el-button @click="visible = false">关闭详情</el-button></div>
    </el-drawer>

    <el-dialog title="平台服务管理" :visible.sync="management" width="min(1180px, 96vw)" append-to-body :close-on-click-modal="false">
      <el-tabs v-model="tab" @tab-click="loadManagement">
        <el-tab-pane label="平台应用" name="apps" v-if="allowed('channel:platform:query')">
          <el-alert title="首期接入天猫。没有平台应用时可以先保存草稿；配置完整并启用后才开放真实授权。" type="info" :closable="false" />
          <div class="toolbar"><el-button v-hasPermi="['channel:platform:config']" type="primary" size="small" @click="editApp()">新增应用</el-button></div>
          <el-table :data="apps" v-loading="manageLoading">
            <el-table-column label="应用名称" prop="name" min-width="150" />
            <el-table-column label="平台" width="80"><template>天猫</template></el-table-column>
            <el-table-column label="对接模式" width="110"><template slot-scope="s"><el-tag size="small" :type="s.row.simulation_mode ? 'warning' : 'info'">{{ s.row.simulation_mode ? '本地模拟' : '真实平台' }}</el-tag></template></el-table-column>
            <el-table-column label="AppKey" prop="app_key" min-width="120"><template slot-scope="s">{{ s.row.simulation_mode ? '模拟无需配置' : s.row.app_key || '待配置' }}</template></el-table-column>
            <el-table-column label="密钥" width="100"><template slot-scope="s">{{ s.row.simulation_mode ? '模拟无需配置' : s.row.secret_configured ? '已配置' : '未配置' }}</template></el-table-column>
            <el-table-column label="状态" width="100"><template slot-scope="s"><el-tag :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '已启用' : '未启用' }}</el-tag></template></el-table-column>
            <el-table-column label="授权回调地址" prop="redirect_uri" min-width="230" show-overflow-tooltip />
            <el-table-column label="操作" width="90"><template slot-scope="s"><span v-if="s.row.simulation_mode">本地演示</span><el-button v-else v-hasPermi="['channel:platform:config']" type="text" @click="editApp(s.row)">配置</el-button></template></el-table-column>
          </el-table>
          <el-empty v-if="!apps.length && !manageLoading" description="尚未配置平台应用，店铺基础资料仍可正常维护" :image-size="70" />
        </el-tab-pane>
        <el-tab-pane label="交互日志" name="logs" v-if="allowed('channel:platform:log')">
          <div class="toolbar"><el-input v-model="logQuery.channelId" placeholder="店铺 ID（可选）" clearable style="width:180px" /><el-select v-model="logQuery.result" clearable placeholder="全部结果"><el-option v-for="(label,key) in results" :key="key" :value="key" :label="label" /></el-select><el-button type="primary" @click="searchLogs">查询</el-button><el-button @click="logQuery.channelId = ''; logQuery.result = ''; searchLogs()">重置</el-button></div>
          <el-table :data="logs" v-loading="manageLoading" @row-click="logDetail = $event">
            <el-table-column label="时间" width="175"><template slot-scope="s">{{ date(s.row.create_time) }}</template></el-table-column>
            <el-table-column label="店铺 ID" prop="channel_id" width="85" />
            <el-table-column label="对接模式" width="110"><template slot-scope="s"><el-tag size="small" :type="isSimulationLog(s.row) ? 'warning' : 'info'">{{ isSimulationLog(s.row) ? '本地模拟' : '真实平台 / 配置' }}</el-tag></template></el-table-column>
            <el-table-column label="操作 / 接口" prop="action" min-width="220"><template slot-scope="s">{{ actions[s.row.action] || s.row.action }}</template></el-table-column>
            <el-table-column label="结果" width="100"><template slot-scope="s"><el-tag :type="s.row.result === 'SUCCESS' ? 'success' : s.row.result === 'RUNNING' ? '' : 'warning'">{{ results[s.row.result] }}</el-tag></template></el-table-column>
            <el-table-column label="错误码" prop="error_code" min-width="160" show-overflow-tooltip />
            <el-table-column label="耗时" width="100"><template slot-scope="s">{{ s.row.duration_ms }} ms</template></el-table-column>
            <el-table-column label="操作人" prop="operator" width="100" />
            <el-table-column label="详情" width="70"><template slot-scope="s"><el-button type="text" @click="logDetail = s.row">查看</el-button></template></el-table-column>
          </el-table>
          <pagination v-show="logTotal > 0" :total="logTotal" :page.sync="logQuery.pageNum" :limit.sync="logQuery.pageSize" @pagination="loadManagement" />
          <p class="muted">请求与结果仅保留必要业务摘要，凭据和授权码不会写入日志。</p>
        </el-tab-pane>
        <el-tab-pane label="到期提醒" name="reminders" v-if="allowed('channel:platform:query')">
          <el-table :data="reminders" v-loading="manageLoading"><el-table-column label="店铺" prop="channel_name" /><el-table-column label="服务状态" prop="service_label" /><el-table-column label="服务到期"><template slot-scope="s">{{ date(s.row.service_expires_at) }}</template></el-table-column><el-table-column label="授权状态" prop="auth_label" /><el-table-column label="授权到期"><template slot-scope="s">{{ date(s.row.token_expires_at) }}</template></el-table-column><el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" @click="management = false; open(s.row.channel_id)">去处理</el-button></template></el-table-column></el-table>
          <p class="muted">按店铺设置的提前天数实时计算，已到期记录持续展示。未授权或未核验的店铺请在列表中查看。</p>
        </el-tab-pane>
      </el-tabs>
      <div slot="footer"><el-button @click="management = false">关闭</el-button></div>
    </el-dialog>

    <el-dialog title="天猫平台应用配置" :visible.sync="appVisible" width="660px" append-to-body :close-on-click-modal="false" @closed="appForm.secret = ''">
      <el-form label-position="top" :model="appForm">
        <el-form-item label="应用名称（必填）"><el-input v-model.trim="appForm.name" maxlength="100" /></el-form-item>
        <div class="binding-form"><el-form-item label="AppKey"><el-input v-model.trim="appForm.app_key" maxlength="100" autocomplete="off" /></el-form-item><el-form-item label="AppSecret"><el-input v-model="appForm.secret" type="password" show-password autocomplete="new-password" :placeholder="appForm.secret_configured ? '已配置，留空保持原值' : '暂未申请可留空'" maxlength="500" /></el-form-item></div>
        <el-form-item label="授权回调地址"><el-input v-model.trim="appForm.redirect_uri" placeholder="https://你的 OMS 域名/channel-authorize" maxlength="500" /><div class="muted">需与平台应用配置一致，使用可访问的 HTTPS 地址。</div></el-form-item>
        <div class="binding-form"><el-form-item label="服务商品编码（article_code）"><el-input v-model.trim="appForm.article_code" placeholder="服务市场商品编码" maxlength="100" /></el-form-item><el-form-item label="收费项目编码（item_code）"><el-input v-model.trim="appForm.item_code" placeholder="核验的具体收费项目" maxlength="100" /></el-form-item></div>
        <el-form-item label="订购 / 续费地址"><el-input v-model.trim="appForm.renewal_url" placeholder="https://fuwu.taobao.com/…" maxlength="500" /></el-form-item>
        <el-form-item label="应用状态"><el-switch v-model="appForm.enabled" :active-value="1" :inactive-value="0" active-text="启用真实授权" inactive-text="草稿 / 停用" /></el-form-item>
        <el-alert title="服务商品及收费项目用于独立核验订购。已绑定店铺后，应用凭据与收费项目不可直接改写；更换应用请创建新配置并重新授权。" :closable="false" type="info" />
      </el-form>
      <div slot="footer"><el-button @click="appVisible = false">取消</el-button><el-button type="primary" :loading="busy" @click="saveApp">保存配置</el-button></div>
    </el-dialog>
    <el-dialog title="交互摘要" :visible="!!logDetail" @close="logDetail = null" width="680px" append-to-body><template v-if="logDetail"><p>请求摘要</p><pre>{{ pretty(logDetail.request_summary) }}</pre><p>结果摘要</p><pre>{{ pretty(logDetail.response_summary) }}</pre><p>错误码：{{ logDetail.error_code || '—' }}</p></template><div slot="footer"><el-button @click="logDetail = null">关闭</el-button></div></el-dialog>
  </div>
</template>
<script>
import { platformGet, platformPost } from '@/api/channel/platform'
export default {
  name: 'PlatformWorkspace',
  data: () => ({ visible: false, management: false, appVisible: false, loading: false, manageLoading: false, busy: false, tab: 'apps', detail: {}, binding: {}, apps: [], appForm: {}, reminders: [], reminder: { days: 7, enabled: true }, logs: [], logTotal: 0, logQuery: { pageNum: 1, pageSize: 20, channelId: '', result: '' }, logDetail: null,
    results: { SUCCESS: '成功', FAILED: '失败', UNKNOWN: '结果未确认', RUNNING: '处理中' }, actions: { APP_SAVE: '保存平台应用', BIND: '绑定店铺', AUTHORIZE_START: '发起授权', OAUTH_EXCHANGE: '授权换取令牌并核验店铺', SIMULATED_AUTHORIZATION: '模拟授权和服务订购成功', TOKEN_REFRESH: '刷新授权令牌', LOCAL_DISABLE: '停用本地授权', REMINDER_SAVE: '设置到期提醒' } }),
  methods: {
    allowed(permission) { return this.$auth.hasPermi(permission) },
    date(value) { return value ? String(value).replace('T', ' ').replace(/\.\d+(Z)?$/, '').slice(0, 19) : '—' },
    pretty(value) { try { return JSON.stringify(JSON.parse(value), null, 2) } catch (e) { return value || '—' } },
    isSimulationLog(row) { try { return JSON.parse(row.request_summary || '{}').simulation === true } catch (e) { return false } },
    statusType(label) { return ['已授权', '服务有效'].includes(label) ? 'success' : ['即将到期', '已过期', '已到期', '授权失效'].includes(label) ? 'warning' : 'info' },
    async open(id) {
      this.visible = true; this.loading = true; this.detail = {}
      try { const [d, a] = await Promise.all([platformGet('/shops/' + id), platformGet('/apps')]); this.detail = d.data; this.apps = a.data; this.binding = { app_id: d.data.app_id, expected_shop_id: d.data.expected_shop_id || '' }; this.reminder = { days: d.data.reminder_days || 7, enabled: d.data.reminder_enabled !== 0 } } finally { this.loading = false }
    },
    async reload() { const r = await platformGet('/shops/' + this.detail.channel_id); this.detail = r.data; this.$emit('changed') },
    async manage(tab, channelId) { this.tab = tab; this.management = true; this.logQuery.channelId = channelId || ''; this.logQuery.pageNum = 1; await this.loadManagement() },
    async loadManagement() {
      this.manageLoading = true
      try {
        if (this.tab === 'apps') this.apps = (await platformGet('/apps')).data
        if (this.tab === 'reminders') this.reminders = (await platformGet('/reminders')).data
        if (this.tab === 'logs') { if (this.logQuery.channelId && !/^\d+$/.test(this.logQuery.channelId)) { this.$message.warning('请输入数字店铺 ID'); return }; const r = await platformGet('/logs', this.logQuery); this.logs = r.data.rows; this.logTotal = r.data.total }
      } finally { this.manageLoading = false }
    },
    searchLogs() { this.logQuery.pageNum = 1; this.loadManagement() },
    editApp(row) { this.appForm = row ? { ...row, secret: '' } : { name: '', app_key: '', secret: '', redirect_uri: '', article_code: '', item_code: '', renewal_url: '', enabled: 0 }; this.appVisible = true },
    async saveApp() { if (!this.appForm.name) return this.$message.warning('请填写应用名称'); this.busy = true; try { await platformPost('/apps', this.appForm); this.appForm.secret = ''; this.appVisible = false; this.apps = (await platformGet('/apps')).data; this.$message.success('应用配置已保存'); this.$emit('changed') } finally { this.busy = false } },
    async saveBinding() { if (!this.binding.app_id || !/^\d{1,32}$/.test(this.binding.expected_shop_id)) return this.$message.warning('请选择应用并填写正确的平台店铺 sid'); await this.act('binding', this.binding) },
    async saveReminder() { await this.act('reminder', this.reminder) },
    async act(action, data) { this.busy = true; try { await platformPost('/shops/' + this.detail.channel_id + '/' + action, data); this.$message.success('操作完成'); await this.reload() } finally { this.busy = false } },
    async authorize() { this.busy = true; try { const r = await platformPost('/shops/' + this.detail.channel_id + '/authorize'); window.location.assign(r.data.url) } finally { this.busy = false } },
    renew() { window.open(this.detail.renewal_url, '_blank', 'noopener,noreferrer') },
    async disable() { try { await this.$confirm('将清除 OMS 保存的令牌并停止使用此授权。平台侧撤销授权请前往淘宝操作。是否继续？', '停用本地授权', { type: 'warning' }) } catch (e) { return }; await this.act('disable') }
  }
}
</script>
<style scoped>
.platform-body{padding:0 28px 24px;overflow:auto;flex:1}.platform-body h2{margin-top:4px}.platform-body section{margin-top:22px;padding:18px;border:1px solid #e5eaf1;border-radius:8px}.platform-body h3{margin:0 0 18px;font-size:16px}.muted{color:#8490a2;font-size:13px;line-height:1.7}.binding-form{display:grid;grid-template-columns:1fr 1fr;gap:18px}.binding-form .el-select{width:100%}.status-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.status-grid p{font-size:14px}.platform-footer{padding:16px 28px;text-align:right;border-top:1px solid #e5eaf1;background:#fff}.toolbar{display:flex;gap:12px;margin:18px 0}.reminder-label{margin:0 10px 0 24px}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f7fa;padding:16px;border-radius:6px}.platform-body .el-input-number{width:115px}@media(max-width:750px){.status-grid,.binding-form{grid-template-columns:1fr}.platform-body{padding:0 16px 16px}}
</style>
<style>.channel-platform-drawer .el-drawer__body{display:flex;flex-direction:column;min-height:0;overflow:hidden}.channel-platform-drawer .el-drawer__header{margin-bottom:18px}</style>
