<template>
  <div>
    <el-form-item v-if="channel" label="渠道平台"><el-select :value="value.provider" @input="set('provider', $event, ['connection_id','shop_id'])"><el-option v-for="p in platforms" :key="p.value" :value="p.value" :label="p.label" :disabled="p.value!=='TMALL'" /></el-select></el-form-item>
    <el-form-item v-if="!capability" label="业务用途"><el-tag v-if="fixedPurpose" size="small">{{labels[value.purpose]}}</el-tag><el-select v-else :value="value.purpose" @input="set('purpose',$event)"><el-option v-for="p in purposes" :key="p" :value="p" :label="labels[p] || p" /></el-select><p v-if="rule" class="purpose-help">{{rule.description}}</p></el-form-item>
    <el-form-item v-if="!channel" label="WMS 连接" required><el-select :value="value.connection_id" placeholder="选择实际连接实例" @input="connection"><el-option v-for="c in metadata.connections || []" :key="c.id" :value="String(c.id)" :label="`${c.name} · ${c.provider}${Number(c.enabled)===1?'':'（已停用）'}`" /></el-select></el-form-item>
    <template v-if="channel"><el-form-item label="适用范围"><el-radio-group v-model="level"><el-radio label="PLATFORM">整个平台</el-radio><el-radio label="CONNECTION">指定接入应用</el-radio><el-radio label="SHOP">指定店铺</el-radio></el-radio-group></el-form-item><el-form-item v-if="level==='CONNECTION'" label="接入应用"><el-select :value="value.connection_id" @input="set('connection_id',$event)"><el-option v-for="c in channelConnections" :key="c.connection_id" :value="String(c.connection_id)" :label="c.connection_name || String(c.connection_id)" /></el-select></el-form-item><el-form-item v-if="level==='SHOP'" label="店铺"><el-select :value="value.shop_id" filterable @input="set('shop_id',$event)"><el-option v-for="s in shops" :key="s.channel_id" :value="String(s.channel_id)" :label="s.channel_name" /></el-select></el-form-item></template>
    <template v-else><el-form-item v-if="!capability" label="适用范围"><el-radio-group v-model="level"><el-radio label="CONNECTION">整个连接</el-radio><el-radio label="OWNER">指定货主</el-radio><el-radio label="WAREHOUSE">货主与实仓</el-radio></el-radio-group></el-form-item><el-form-item v-if="capability || level!=='CONNECTION'" label="货主" required><el-select :value="value.owner_code" filterable @input="set('owner_code',$event,['real_store_code'])"><el-option v-for="s in owners" :key="s.owner_code" :value="s.owner_code" :label="`${s.owner_name} · ${s.owner_code}`" /></el-select></el-form-item><el-form-item v-if="capability || level==='WAREHOUSE'" label="实体仓" required><el-select :value="value.real_store_code" filterable @input="set('real_store_code',$event)"><el-option v-for="s in stores" :key="s.real_store_code" :value="s.real_store_code" :label="`${s.real_store_name} · ${s.real_store_code}`" /></el-select></el-form-item></template>
  </div>
</template>
<script>
import { carrierMappingRule } from '@/utils/carrierMappingPurpose'
export const purposeLabels = { CHANNEL_SHIPPING: '销售发货回传', CHANNEL_RETURN_IDENTIFY: '识别买家寄回物流', CHANNEL_RETURN_BACKFILL: '向平台回填寄回物流', CHANNEL_EXCHANGE_LOGISTICS: '换货发出物流回传', WMS_OUTBOUND_CREATE: 'WMS 出库下发', WMS_OUTBOUND_ACTUAL: 'WMS 实际出库回传', WMS_RETURN_CREATE: 'WMS 退回入库下发', WMS_RETURN_ACTUAL: 'WMS 实际入库回传' }
export default {
  props: { value: { type: Object, required: true }, metadata: { type: Object, required: true }, capability: Boolean, conversionDirection: String, fixedPurpose: Boolean },
  data: () => ({ labels: purposeLabels, platforms: [{ value: 'TMALL', label: '天猫' }, { value: 'JD', label: '京东（后续扩展）' }, { value: 'DOUYIN', label: '抖音（后续扩展）' }] }),
  computed: {
    rule() { return carrierMappingRule(this.value) },
    channel() { return this.value.system_type === 'CHANNEL' },
    level: { get() { return this.value.scope_level || (this.channel ? this.value.shop_id ? 'SHOP' : this.value.connection_id ? 'CONNECTION' : 'PLATFORM' : this.value.real_store_code ? 'WAREHOUSE' : this.value.owner_code ? 'OWNER' : 'CONNECTION') }, set(level) { const row = { ...this.value, scope_level: level }; if (this.channel) { row.shop_id = ''; row.connection_id = '' } else { if (level === 'CONNECTION') row.owner_code = ''; row.real_store_code = '' } this.$emit('input', row) } },
    purposes() { return (this.metadata.purposes || []).filter(p => p.startsWith(this.value.system_type + '_') && (!this.conversionDirection || (p==='CHANNEL_RETURN_IDENTIFY'||p.endsWith('_ACTUAL')?'IN':'OUT')===this.conversionDirection)) },
    shops() { return (this.metadata.shops || []).filter(s => ({ TM: 'TMALL', DY: 'DOUYIN' }[s.channel_type] || s.channel_type) === this.value.provider) },
    channelConnections() { return this.shops.filter(s => s.connection_id).filter((s,i,a) => a.findIndex(x=>x.connection_id===s.connection_id)===i) },
    pool() { return (this.metadata.stores || []).filter(s => String(s.connection_id) === this.value.connection_id) },
    owners() { return this.pool.filter((s,i,a) => a.findIndex(x => x.owner_code === s.owner_code) === i) },
    stores() { return this.pool.filter(s => s.owner_code === this.value.owner_code).filter((s,i,a) => a.findIndex(x => x.real_store_code === s.real_store_code) === i) }
  },
  methods: { set(key, value, clear = []) { const row = { ...this.value, [key]: value }; clear.forEach(k => { row[k] = '' }); this.$emit('input', row) }, connection(id) { const c = (this.metadata.connections || []).find(c => String(c.id) === id); this.$emit('input', { ...this.value, connection_id: id, provider: c ? c.provider : '', owner_code: '', real_store_code: '' }) } }
}
</script>
<style scoped>.purpose-help{font-size:12px;color:#748399;line-height:1.7;margin:6px 0 0}</style>
