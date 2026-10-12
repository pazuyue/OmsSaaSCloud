<template>
  <div v-loading="loading" class="carrier-settings">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="kind === 'catalog'">
      <div class="toolbar"><p class="hint">维护快递及各货主实体仓的配送范围；仓库快递编码用于后续下发仓库。</p><div><el-button size="small" @click="load">刷新</el-button><el-button size="small" type="primary" icon="el-icon-plus" :disabled="loading || !!error" @click="editCarrier()">新增快递</el-button></div></div>
      <el-table :data="carriers" empty-text="尚无快递资料，请新增并配置仓库服务范围">
        <el-table-column label="快递名称 / 编码" min-width="180"><template slot-scope="s">{{ s.row.carrier_name }}<div class="hint">{{ s.row.carrier_code }}</div></template></el-table-column>
        <el-table-column label="状态" width="110"><template slot-scope="s"><el-tag size="small" :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="货主实体仓 / 配送范围" min-width="400"><template slot-scope="s"><div v-for="service in s.row.services" :key="warehouseKey(service)" class="coverage">{{ warehouseLabel(warehouseKey(service)) }} · {{ service.warehouse_carrier_code }}<div class="hint">{{ service.provinces.length ? service.provinces.join('、') : '全部省份' }}{{ service.excluded_provinces.length ? '；排除：' + service.excluded_provinces.join('、') : '' }}</div></div></template></el-table-column>
        <el-table-column label="更新人 / 时间" min-width="180"><template slot-scope="s">{{ s.row.updated_by }}<div class="hint">{{ s.row.update_time }}</div></template></el-table-column>
        <el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" @click="editCarrier(s.row)">编辑资料</el-button></template></el-table-column>
      </el-table>
    </template>
    <template v-else>
      <div class="toolbar"><p class="hint">按发货单的实体仓和收货省份匹配；手工选择也须在店铺允许快递和仓库配送范围内。</p><el-button size="small" @click="load">刷新</el-button></div>
      <el-table :data="configs" empty-text="暂无已接入店铺">
        <el-table-column label="OMS 店铺" min-width="180"><template slot-scope="s">{{ s.row.channel_name }}<div class="hint">渠道 ID：{{ s.row.channel_id }}</div></template></el-table-column>
        <el-table-column label="自动匹配" width="130"><template slot-scope="s"><el-tag size="small" :type="s.row.policy.enabled ? 'success' : 'info'">{{ !s.row.configured ? '未配置' : s.row.policy.enabled ? '开启' : '关闭' }}</el-tag></template></el-table-column>
        <el-table-column label="允许快递 / 默认顺序" min-width="300"><template slot-scope="s">{{ s.row.policy.carrier_order.map(carrierLabel).join(' → ') || '—' }}</template></el-table-column>
        <el-table-column label="仓库 / 省份优先规则" width="180"><template slot-scope="s">{{ s.row.policy.routes.length }} 条</template></el-table-column>
        <el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" @click="editRule(s.row)">配置规则</el-button></template></el-table-column>
      </el-table>
    </template>
    <el-dialog title="快递资料与仓库服务范围" :visible.sync="carrierVisible" width="min(980px, 96vw)" append-to-body :close-on-click-modal="false">
      <el-form v-if="carrier" label-width="120px">
        <el-form-item label="快递编码"><el-input v-model.trim="carrier.carrier_code" :disabled="carrier.revision > 0" maxlength="64" placeholder="1 至 64 位字母、数字、下划线或连字符" /></el-form-item>
        <el-form-item label="快递名称"><el-input v-model.trim="carrier.carrier_name" maxlength="100" placeholder="填写业务使用的快递名称" /></el-form-item>
        <el-form-item label="启用快递"><el-switch v-model="carrier.enabled" :active-value="1" :inactive-value="0" /><span class="hint">停用后不能再被自动或手工选择；已有单据保留历史选择。</span></el-form-item>
        <el-form-item label="仓库服务范围">
          <div v-for="(service, index) in carrier.services" :key="index" class="service-card">
            <div class="service-head"><el-select :value="warehouseKey(service)" filterable placeholder="选择货主实体仓" @change="value => setWarehouse(service, value)"><el-option v-for="store in warehouses" :key="warehouseKey(store)" :value="warehouseKey(store)" :label="warehouseLabel(warehouseKey(store))" /></el-select><el-input v-model.trim="service.warehouse_carrier_code" placeholder="仓库快递编码" maxlength="64" /><el-button type="text" @click="carrier.services.splice(index, 1)">删除</el-button></div>
            <div class="region-row"><span>配送省份</span><el-select v-model="service.provinces" multiple filterable clearable placeholder="留空表示全部省份"><el-option v-for="province in provinces" :key="province" :value="province" :label="province" /></el-select></div>
            <div class="region-row"><span>排除省份</span><el-select v-model="service.excluded_provinces" multiple filterable clearable placeholder="该仓不可配送的省份"><el-option v-for="province in provinces" :key="province" :value="province" :label="province" /></el-select></div>
          </div>
          <el-button size="small" icon="el-icon-plus" @click="carrier.services.push({ owner_code: '', real_store_code: '', warehouse_carrier_code: '', provinces: [], excluded_provinces: [] })">添加仓库服务</el-button>
        </el-form-item>
      </el-form>
      <span slot="footer"><el-button :disabled="saving" @click="carrierVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMaster">保存快递资料</el-button></span>
    </el-dialog>
    <el-dialog title="店铺快递匹配规则" :visible.sync="ruleVisible" width="min(980px, 96vw)" append-to-body :close-on-click-modal="false">
      <el-form v-if="rule" label-width="140px">
        <el-form-item label="店铺">{{ rule.channel_name }}</el-form-item>
        <el-form-item label="自动匹配"><el-switch v-model="rule.policy.enabled" :active-value="1" :inactive-value="0" /><p class="hint">开启后，新生成的发货单自动尝试匹配。修改规则后，待匹配单据可在发货单列表重新匹配。</p></el-form-item>
        <el-form-item label="允许快递 / 顺序"><el-select v-model="rule.policy.carrier_order" multiple filterable placeholder="按优先顺序选择快递" @change="cleanRoutes"><el-option v-for="c in carriers" :key="c.carrier_code" :value="c.carrier_code" :label="carrierLabel(c.carrier_code)" :disabled="!c.enabled" /></el-select><div v-for="(code, index) in rule.policy.carrier_order" :key="code" class="rank-row"><span>{{ index + 1 }}. {{ carrierLabel(code) }}</span><div><el-button type="text" :disabled="index === 0" @click="move(rule.policy.carrier_order, index, -1)">上移</el-button><el-button type="text" :disabled="index === rule.policy.carrier_order.length - 1" @click="move(rule.policy.carrier_order, index, 1)">下移</el-button></div></div></el-form-item>
        <el-form-item label="仓库 / 省份优先">
          <div v-for="(route, index) in rule.policy.routes" :key="index" class="service-card">
            <div class="service-head"><el-select :value="warehouseKey(route)" clearable filterable placeholder="全部允许仓" @change="value => setWarehouse(route, value)"><el-option v-for="store in warehouses" :key="warehouseKey(store)" :value="warehouseKey(store)" :label="warehouseLabel(warehouseKey(store))" /></el-select><el-button type="text" @click="rule.policy.routes.splice(index, 1)">删除</el-button></div>
            <div class="region-row"><span>收货省份</span><el-select v-model="route.provinces" multiple filterable clearable placeholder="留空表示该仓全部省份"><el-option v-for="province in provinces" :key="province" :value="province" :label="province" /></el-select></div>
            <div class="region-row"><span>快递优先</span><el-select v-model="route.carrier_order" multiple filterable placeholder="按优先顺序选择店铺允许快递"><el-option v-for="code in rule.policy.carrier_order" :key="code" :value="code" :label="carrierLabel(code)" /></el-select></div>
            <div v-for="(code, rank) in route.carrier_order" :key="code" class="rank-row"><span>{{ rank + 1 }}. {{ carrierLabel(code) }}</span><div><el-button type="text" :disabled="rank === 0" @click="move(route.carrier_order, rank, -1)">上移</el-button><el-button type="text" :disabled="rank === route.carrier_order.length - 1" @click="move(route.carrier_order, rank, 1)">下移</el-button></div></div>
          </div>
          <el-button size="small" icon="el-icon-plus" @click="rule.policy.routes.push({ owner_code: '', real_store_code: '', provinces: [], carrier_order: [] })">添加优先规则</el-button>
          <p class="hint">依次采用：仓库与省份规则 → 仓库默认规则 → 全仓省份规则 → 店铺默认顺序。优先快递不可用时继续尝试默认顺序；停用或不配送区域的快递不会被选中。</p>
        </el-form-item>
      </el-form>
      <span slot="footer"><el-button :disabled="saving" @click="ruleVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRule">保存匹配规则</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { carrierCatalog, carrierConfigs, saveCarrier, saveCarrierConfig } from '@/api/order'
const clone = value => JSON.parse(JSON.stringify(value))
export default {
  props: { kind: { type: String, default: 'catalog' } },
  data() { return { loading: false, saving: false, error: '', carriers: [], configs: [], warehouses: [], provinces: [], carrierVisible: false, ruleVisible: false, carrier: null, rule: null, requestId: 0 } },
  created() { this.load() },
  methods: {
    async load() { const id = ++this.requestId; this.loading = true; this.error = ''; try { const [catalog, configs] = await Promise.all([carrierCatalog(), carrierConfigs()]); if (id !== this.requestId) return; this.carriers = catalog.data.carriers; this.configs = configs.data; this.provinces = catalog.data.provinces; this.warehouses = [...new Map(catalog.data.warehouses.map(s => [this.warehouseKey(s), s])).values()] } catch (_) { if (id === this.requestId) this.error = '快递设置加载失败，请刷新重试' } finally { if (id === this.requestId) this.loading = false } },
    warehouseKey(row) { return row.real_store_code ? JSON.stringify([row.owner_code, row.real_store_code]) : '' },
    setWarehouse(row, key) { const [owner, store] = key ? JSON.parse(key) : ['', '']; row.owner_code = owner; row.real_store_code = store },
    warehouseLabel(key) { const store = this.warehouses.find(s => this.warehouseKey(s) === key); if (store) return `${store.real_store_name || store.real_store_code}（${store.real_store_code}） / ${store.owner_name || store.owner_code}`; if (!key) return '全部允许仓'; const [owner, code] = JSON.parse(key); return `${code} / ${owner}（已停用或映射异常）` },
    carrierLabel(code) { const c = this.carriers.find(c => c.carrier_code === code); return c ? `${c.carrier_name}（${code}）${c.enabled ? '' : ' · 已停用'}` : `${code}（资料缺失）` },
    editCarrier(row) { this.carrier = row ? clone(row) : { carrier_code: '', carrier_name: '', enabled: 1, services: [], revision: 0 }; this.carrierVisible = true },
    editRule(row) { this.rule = clone(row); this.ruleVisible = true },
    cleanRoutes() { this.rule.policy.routes.forEach(r => { r.carrier_order = r.carrier_order.filter(c => this.rule.policy.carrier_order.includes(c)) }) },
    move(rows, index, step) { const a = rows[index]; this.$set(rows, index, rows[index + step]); this.$set(rows, index + step, a) },
    async saveMaster() { if (this.saving) return; if (!this.carrier.carrier_code || !this.carrier.carrier_name) return this.$message.warning('请填写快递编码和名称'); this.saving = true; try { await saveCarrier(this.carrier); this.carrierVisible = false; this.$message.success('快递资料已保存'); await this.load() } finally { this.saving = false } },
    async saveRule() { if (this.saving) return; this.saving = true; try { await saveCarrierConfig({ channel_id: this.rule.channel_id, revision: this.rule.revision, policy: this.rule.policy }); this.ruleVisible = false; this.$message.success('快递规则已保存'); await this.load() } finally { this.saving = false } }
  }
}
</script>
<style scoped>
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:12px 0 20px}.hint{font-size:13px;color:#8490a2;margin:6px 0}.coverage{margin:8px 0}.service-card{border:1px solid #e7ebf0;border-radius:6px;padding:14px;margin-bottom:12px}.service-head,.region-row,.rank-row{display:flex;align-items:center;gap:12px;margin-bottom:10px}.service-head .el-select{flex:1;min-width:280px}.service-head .el-input{width:190px}.region-row span{width:70px;flex:none}.region-row .el-select{flex:1}.rank-row{justify-content:space-between;border-bottom:1px solid #edf0f5}.el-form-item>.el-select,.el-form-item__content>.el-select{width:100%}
</style>
