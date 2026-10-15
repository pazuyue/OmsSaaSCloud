<template>
  <el-dialog title="店铺分仓规则" :visible.sync="visible" width="min(960px, 94vw)" append-to-body :close-on-click-modal="false">
    <div v-loading="loading">
      <p>{{ shop.channel_name }} · {{ shop.channel_id }}</p>
      <el-form v-if="policy" label-width="140px">
        <el-form-item label="启用分仓"><el-switch v-model="policy.enabled" :active-value="1" :inactive-value="0" /><span class="hint">允许按下方执行方式进行分仓</span></el-form-item>
        <el-form-item label="执行方式"><el-radio-group v-model="policy.execution_mode"><el-radio label="MANUAL">手动分仓</el-radio><el-radio label="AUTO">自动分仓</el-radio></el-radio-group><p class="hint">自动模式在审核通过、逻辑预占更新或解除阻断后检查执行；分仓只生成发货单，不自动下发仓库。新店铺默认手动。</p></el-form-item>
        <el-form-item v-if="policy.execution_mode === 'AUTO'" label="自动补充分仓"><el-switch v-model="policy.auto_replenish" :active-value="1" :inactive-value="0" /><p class="hint">开启后，部分分仓订单的余量可在补货后继续；关闭时已有分仓单的余量需人工处理。已分仓订单仍沿用首次分仓规则。</p></el-form-item>
        <el-form-item label="货主"><el-select v-model="policy.owner_code" filterable @change="ownerChanged"><el-option v-for="owner in owners" :key="owner.owner_code" :value="owner.owner_code" :label="owner.owner_name + '（' + owner.owner_code + '）'" /></el-select></el-form-item>
        <el-form-item label="允许仓 / 默认顺序">
          <el-select v-model="policy.warehouse_order" multiple filterable placeholder="选择该店铺允许的虚拟仓" @change="cleanPriorities"><el-option v-for="store in available" :key="store.wms_simulation_code" :value="store.wms_simulation_code" :label="storeLabel(store)" /></el-select>
          <div v-for="(code, index) in policy.warehouse_order" :key="code" class="rank-row"><span>{{ index + 1 }}. {{ label(code) }}</span><div><el-button type="text" :disabled="index === 0" aria-label="仓库上移" @click="move(policy.warehouse_order, index, -1)">上移</el-button><el-button type="text" :disabled="index === policy.warehouse_order.length - 1" aria-label="仓库下移" @click="move(policy.warehouse_order, index, 1)">下移</el-button></div></div>
          <p class="hint">以虚拟仓为分仓单位；不同虚拟仓分别生成发货单并下发，即使属于同一实体仓也不合并。</p>
        </el-form-item>
        <el-form-item label="省份优先仓">
          <div v-for="(row, index) in priorities" :key="index" class="province-row"><el-select v-model="row.province" filterable placeholder="收货省份"><el-option v-for="province in provinces" :key="province" :label="province" :value="province" /></el-select><el-select v-model="row.stores" multiple filterable placeholder="按选择顺序设置优先仓"><el-option v-for="code in policy.warehouse_order" :key="code" :value="code" :label="label(code)" /></el-select><el-button type="text" @click="priorities.splice(index, 1)">删除</el-button></div>
          <el-button size="small" icon="el-icon-plus" @click="priorities.push({ province: '', stores: [] })">添加省份</el-button><p class="hint">先按省份优先顺序，再按默认顺序尝试；收货省份缺失时使用默认顺序。</p>
        </el-form-item>
        <el-form-item label="分仓策略"><el-radio-group v-model="policy.strategy"><el-radio label="FEWEST_SHIPMENTS">优先整单</el-radio><el-radio label="REGION_FIRST">优先省份配置仓</el-radio></el-radio-group><p class="hint">{{ policy.strategy === 'REGION_FIRST' ? '先按收货省份匹配优先仓，不足时按默认仓顺序补足；仍受多仓、同 SKU 拆分和数量上限限制。' : '优先一个虚拟仓满足整单；组合分仓时优先较少的新增发货单，顺序用于同等方案择优。' }}</p></el-form-item>
        <el-form-item label="最多虚拟仓数"><el-input-number v-model="policy.max_virtual_warehouses" :min="0" :max="100" :precision="0" controls-position="right" /><span class="hint"> 0 为不额外限制；关闭多仓时仍限一个虚拟仓，包含已分仓使用的仓。</span></el-form-item>
        <el-form-item label="最多发货单数"><el-input-number v-model="policy.max_shipments" :min="0" :max="100" :precision="0" controls-position="right" /><span class="hint"> 0 为不额外限制；按订单累计计算，包含后续补发；不同于实际包裹数。</span></el-form-item>
        <el-form-item label="允许多虚拟仓发货"><el-switch v-model="policy.allow_multi" :active-value="1" :inactive-value="0" @change="value => { if (!value) policy.allow_split_sku = 0 }" /><p class="hint">允许在所选策略和拆分上限内，组合店铺允许的多个虚拟仓。</p></el-form-item>
        <el-form-item label="允许部分发货"><el-switch v-model="policy.allow_partial" :active-value="1" :inactive-value="0" /><p class="hint">已逻辑预占的数量可先分仓；单个 SKU 是否可拆到不同虚拟仓，由下方开关控制。原单金额和销售数量保持完整。</p></el-form-item>
        <el-form-item v-if="policy.allow_partial" label="首次最低满足比例"><el-input-number v-model="policy.min_first_allocation_percent" :min="0" :max="100" :precision="0" controls-position="right" /> %<p class="hint">按首次可分仓件数 / 原单总件数判断；低于比例时不新增仓库占用。0 为不限制，后续补发不受此比例限制。</p></el-form-item>
        <el-form-item label="同 SKU 跨虚拟仓拆分"><el-switch v-model="policy.allow_split_sku" :active-value="1" :inactive-value="0" :disabled="!policy.allow_multi" /><p class="hint">关闭时，同一 SKU 固定由一个虚拟仓承担；部分发货后的余量仍从原虚拟仓分仓。开启后可跨虚拟仓拆分。</p></el-form-item>
        <el-alert title="已有渠道锁库沿用原仓、原批次；原仓不在允许仓内时会阻止分仓。已有分仓订单继续使用首次分仓规则，重试仅处理余量。" type="info" :closable="false" />
      </el-form>
    </div>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">取消</el-button><el-button type="primary" :disabled="loading || !policy" :loading="saving" @click="save">保存分仓规则</el-button></span>
  </el-dialog>
</template>
<script>
import { allocationConfig, saveAllocationConfig } from '@/api/order'
export default {
  data() { return { visible: false, loading: false, saving: false, shop: {}, policy: null, revision: 0, stores: [], priorities: [], provinces: ['北京', '天津', '河北', '山西', '内蒙古', '辽宁', '吉林', '黑龙江', '上海', '江苏', '浙江', '安徽', '福建', '江西', '山东', '河南', '湖北', '湖南', '广东', '广西', '海南', '重庆', '四川', '贵州', '云南', '西藏', '陕西', '甘肃', '青海', '宁夏', '新疆', '香港', '澳门', '台湾'] } },
  computed: { owners() { return [...new Map(this.stores.map(s => [s.owner_code, s])).values()] }, available() { return this.stores.filter(s => s.owner_code === this.policy.owner_code) } },
  methods: {
    async open(shop) { this.shop = shop; this.visible = true; this.loading = true; this.policy = null; try { const r = (await allocationConfig(shop.channel_id)).data; this.policy = { execution_mode: 'MANUAL', auto_replenish: 0, strategy: 'FEWEST_SHIPMENTS', max_virtual_warehouses: 0, max_shipments: 0, min_first_allocation_percent: 0, ...r.policy }; this.stores = r.warehouses; this.revision = r.revision; this.priorities = Object.entries(r.policy.province_priorities).map(([province, stores]) => ({ province, stores: [...stores] })) } finally { this.loading = false } },
    storeLabel(store) { return `${store.wms_simulation_name}（${store.wms_simulation_code}） / ${store.real_store_name}（${store.real_store_code}）` },
    label(code) { const s = this.stores.find(s => s.wms_simulation_code === code); return s ? this.storeLabel(s) : `${code}（已停用或映射异常，请移除）` },
    ownerChanged() { this.policy.warehouse_order = []; this.priorities = [] },
    cleanPriorities() { this.priorities.forEach(row => { row.stores = row.stores.filter(code => this.policy.warehouse_order.includes(code)) }) },
    move(rows, index, step) { const copy = [...rows]; [copy[index], copy[index + step]] = [copy[index + step], copy[index]]; this.policy.warehouse_order = copy },
    async save() { if (this.saving) return; const priorities = {}; for (const row of this.priorities) { if (!row.province || !row.stores.length) return this.$message.warning('请完整填写省份优先仓'); if (priorities[row.province]) return this.$message.warning('省份不能重复'); priorities[row.province] = row.stores } this.saving = true; try { await saveAllocationConfig({ channel_id: this.shop.channel_id, revision: this.revision, policy: { ...this.policy, province_priorities: priorities } }); this.visible = false; this.$message.success('分仓规则已保存'); this.$emit('changed') } finally { this.saving = false } }
  }
}
</script>
<style scoped>
.hint{font-size:13px;color:#8490a2;margin:6px 0}.rank-row{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #edf0f5;gap:12px}.province-row{display:flex;gap:8px;margin-bottom:10px}.province-row .el-select:first-child{width:140px;flex:none}.province-row .el-select:nth-child(2){flex:1}.el-select{width:100%}
</style>
