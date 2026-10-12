<template>
  <el-dialog title="店铺分仓规则" :visible.sync="visible" width="min(860px, 94vw)" append-to-body :close-on-click-modal="false">
    <div v-loading="loading">
      <p>{{ shop.channel_name }} · {{ shop.channel_id }}</p>
      <el-form v-if="policy" label-width="140px">
        <el-form-item label="启用分仓"><el-switch v-model="policy.enabled" :active-value="1" :inactive-value="0" /><span class="hint">审核通过后，由订单列表手动发起分仓</span></el-form-item>
        <el-form-item label="货主"><el-select v-model="policy.owner_code" filterable @change="ownerChanged"><el-option v-for="owner in owners" :key="owner.owner_code" :value="owner.owner_code" :label="owner.owner_name + '（' + owner.owner_code + '）'" /></el-select></el-form-item>
        <el-form-item label="允许仓 / 默认顺序">
          <el-select v-model="policy.warehouse_order" multiple filterable placeholder="选择该店铺允许的虚拟仓" @change="cleanPriorities"><el-option v-for="store in available" :key="store.wms_simulation_code" :value="store.wms_simulation_code" :label="storeLabel(store)" /></el-select>
          <div v-for="(code, index) in policy.warehouse_order" :key="code" class="rank-row"><span>{{ index + 1 }}. {{ label(code) }}</span><div><el-button type="text" :disabled="index === 0" aria-label="仓库上移" @click="move(policy.warehouse_order, index, -1)">上移</el-button><el-button type="text" :disabled="index === policy.warehouse_order.length - 1" aria-label="仓库下移" @click="move(policy.warehouse_order, index, 1)">下移</el-button></div></div>
          <p class="hint">虚拟仓确定货主与实体仓；同一实体仓的多个虚拟仓按一个发货仓判断。</p>
        </el-form-item>
        <el-form-item label="省份优先仓">
          <div v-for="(row, index) in priorities" :key="index" class="province-row"><el-select v-model="row.province" filterable placeholder="收货省份"><el-option v-for="province in provinces" :key="province" :label="province" :value="province" /></el-select><el-select v-model="row.stores" multiple filterable placeholder="按选择顺序设置优先仓"><el-option v-for="code in policy.warehouse_order" :key="code" :value="code" :label="label(code)" /></el-select><el-button type="text" @click="priorities.splice(index, 1)">删除</el-button></div>
          <el-button size="small" icon="el-icon-plus" @click="priorities.push({ province: '', stores: [] })">添加省份</el-button><p class="hint">先按省份优先顺序，再按默认顺序尝试；收货省份缺失时使用默认顺序。</p>
        </el-form-item>
        <el-form-item label="允许多仓发货"><el-switch v-model="policy.allow_multi" :active-value="1" :inactive-value="0" @change="value => { if (!value) policy.allow_split_sku = 0 }" /><p class="hint">优先尝试整单单仓履约；单仓无法满足时允许组合多个实体仓。</p></el-form-item>
        <el-form-item label="允许部分发货"><el-switch v-model="policy.allow_partial" :active-value="1" :inactive-value="0" /><p class="hint">足量逻辑预占的 SKU 可先分仓，余量保留待补货；原单金额和销售数量保持完整。</p></el-form-item>
        <el-form-item label="同 SKU 跨仓拆分"><el-switch v-model="policy.allow_split_sku" :active-value="1" :inactive-value="0" :disabled="!policy.allow_multi" /><p class="hint">关闭时，同一 SKU 固定由一个实体仓承担；允许部分发货时，后续余量仍从原仓分仓。开启后可跨仓拆分。</p></el-form-item>
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
    async open(shop) { this.shop = shop; this.visible = true; this.loading = true; this.policy = null; try { const r = (await allocationConfig(shop.channel_id)).data; this.policy = r.policy; this.stores = r.warehouses; this.revision = r.revision; this.priorities = Object.entries(r.policy.province_priorities).map(([province, stores]) => ({ province, stores: [...stores] })) } finally { this.loading = false } },
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
