<template>
  <div class="shared-carrier-select">
    <el-select :value="value" filterable remote clearable :remote-method="search" :loading="loading" :disabled="disabled" placeholder="搜索标准快递名称、编码或别名" @focus="search('')" @input="select">
      <el-option v-for="row in rows" :key="row.id" :value="row.id" :label="row.standard_name" :disabled="!row.available">
        <div class="carrier-option"><strong>{{ row.standard_name }}</strong><span>OMS {{ row.standard_code }}</span></div>
        <div class="carrier-option-note">{{ row.available ? target(row) : row.reason }}</div>
      </el-option>
    </el-select>
    <p v-if="error" role="alert" class="carrier-error">{{ error }} <el-button type="text" @click="search(keyword)">重试查询</el-button></p>
    <p v-else-if="!loading && loaded && !rows.some(r => r.available)" class="carrier-note">{{ rows.length ? '当前条件暂无可用快递，请查看各选项的具体原因。' : '未查到标准快递，请调整搜索条件或联系配置人员。' }}</p>
    <p v-if="selected" class="carrier-note">OMS {{ selected.standard_code }} · {{ target(selected) }}</p>
    <router-link v-if="hasConfig && !(selected && (selected.conversion||{}).virtual)" class="carrier-note carrier-link" :to="configLink">{{standardOnly?'查看标准快递资料':'查看当前用途的快递关联'}}</router-link>
  </div>
</template>
<script>
import { carrierOptions, standardSearch, standardDetail } from '@/api/common/carriers'
export default {
  name: 'CarrierSelect',
  props: { value: { type: [Number, String], default: '' }, context: { type: Object, default: () => ({}) }, standardOnly: Boolean, disabled: Boolean, loadOptions: Function },
  data: () => ({ rows: [], loading: false, loaded: false, error: '', keyword: '', epoch: 0, timer: null, lastKey: '' }),
  computed: {
    key() { return JSON.stringify(this.context) + this.standardOnly },
    selected() { return this.rows.find(r => String(r.id) === String(this.value)) },
    hasConfig() { const p = this.$store.getters.permissions || []; return p.includes('*:*:*') || p.includes('common:carrier:query') },
    configLink() { return { path: '/common-config/carriers/' + (this.standardOnly ? 'standards' : this.context.system_type === 'WMS' ? 'wms' : 'channels'), query: { ...this.context, standard_id: this.value || undefined } } }
  },
  watch: { value(value) { if(value && this.loaded && !this.rows.some(row=>String(row.id)===String(value)))this.search('') }, key: { immediate: true, handler() { if(this.lastKey && this.lastKey!==this.key) { this.$emit('input',''); this.$emit('change',null) } this.lastKey=this.key; this.epoch++; this.rows = []; this.loaded = false; this.search('') } } },
  beforeDestroy() { this.epoch++; clearTimeout(this.timer) },
  methods: {
    select(value) { this.$emit('input', value || ''); this.$emit('change', this.rows.find(r => r.id === value) || null) },
    target(row) { return this.standardOnly ? '标准快递身份' : (row.conversion || {}).virtual ? '虚拟执行，无需 WMS 编码' : `目标编码 ${(row.conversion || {}).external_code || '待确认'}` },
    search(keyword) { clearTimeout(this.timer); this.keyword = keyword; const epoch = ++this.epoch; this.loading = true; this.error = ''; this.timer = setTimeout(async() => { try { const r = this.standardOnly ? await standardSearch(keyword) : this.loadOptions ? await this.loadOptions(keyword) : await carrierOptions({ ...this.context, keyword }); if (epoch !== this.epoch) return; this.rows = (r.data.rows || []).map(row => this.standardOnly ? { ...row, available: Number(row.enabled) === 1, reason: row.disabled_reason || '标准快递已停用' } : row); if(this.value && !this.rows.some(row=>String(row.id)===String(this.value))) {const selected=(await standardDetail(this.value)).data;if(epoch!==this.epoch)return;this.rows.push({...selected,available:false,reason:'当前选择不在本次可用结果中，请搜索名称重新核对'})} this.loaded = true } catch (e) { if (epoch === this.epoch) { this.rows = []; this.error = e.message || '快递选项读取失败，请重试'; this.loaded = false } } finally { if (epoch === this.epoch) this.loading = false } }, 250) }
  }
}
</script>
<style scoped>
.shared-carrier-select .el-select{width:100%}.carrier-note,.carrier-error{font-size:12px;line-height:1.6;margin:6px 0;color:#748399}.carrier-error{color:#d94a4a}.carrier-link{color:#1677dd}.carrier-option{display:flex;justify-content:space-between;gap:20px}.carrier-option span,.carrier-option-note{font-size:12px;color:#75849a}.el-select-dropdown__item{height:auto;min-height:58px;line-height:26px}.carrier-option-note{white-space:normal;line-height:20px;padding-bottom:6px}
</style>
