<template>
  <configuration-panel title="订单数据接入" :visible.sync="visible" :embedded="embedded" :read-only="readOnly" width="700px" append-to-body :close-on-click-modal="false">
    <el-form ref="shopForm" :model="shop" :rules="shopRules" label-width="140px">
      <div class="section-title">订单接入 · 推送库</div>
      <el-form-item label="天猫店铺" prop="channel_id"><el-select v-model="shop.channel_id" disabled filterable><el-option v-for="s in catalog.channels" :key="s.channel_id" :value="s.channel_id" :label="s.channel_name" /></el-select></el-form-item>
      <el-form-item label="平台授权名称"><span>{{ selectedShop.seller_nick || '暂无授权名称' }}</span><p class="muted">用于核对商家身份，可与 OMS 店铺名称、推送库卖家账号不同。</p></el-form-item>
      <el-form-item label="推送库" prop="source_key"><el-select v-model="shop.source_key" @change="changeSource"><el-option v-for="s in catalog.sources" :key="s.key" :value="s.key" :label="s.label + (s.enabled ? '' : '（已停用）')" :disabled="!s.enabled" /></el-select><p v-if="!catalog.sources.some(s => s.enabled)" class="muted">请先在推送库配置中新增并启用来源。</p></el-form-item>
      <el-form-item label="推送库卖家账号" prop="seller_nick"><el-input v-model.trim="shop.seller_nick" placeholder="与源表 seller_nick 一致" @change="changeSource" /></el-form-item>
      <el-form-item v-if="sellerMismatch"><el-alert title="推送库卖家账号与平台授权名称不同，请核对映射。" type="warning" :closable="false" show-icon /><el-checkbox v-if="!shop.id || sourceChanged" v-model="shop.confirm_seller_mapping" :true-label="1" :false-label="0">确认该推送库账号的订单归属所选 OMS 店铺</el-checkbox><p v-else class="muted">当前映射已保存，修改开关或间隔无需重复确认。</p></el-form-item>
      <el-form-item label="来源验证时间"><el-date-picker v-model="verifyRange" type="datetimerange" value-format="yyyy-MM-dd HH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" style="width:100%" /><p class="muted">按推送更新时间查询，最多90天；验证不拉单、不改变进度。</p><el-button size="small" :loading="verifying" @click="verifySource">验证拉单来源</el-button>
        <template v-if="verification"><el-alert :title="verification.message" :type="verification.orders.length ? 'success' : 'info'" :closable="false" /><el-table v-if="verification.orders.length" :data="verification.orders" size="mini"><el-table-column label="平台订单号" prop="tid" min-width="170" /><el-table-column label="推送更新时间" prop="jdp_modified" min-width="155" /></el-table></template>
      </el-form-item>
      <el-form-item label="首次拉取起点" prop="initial_time"><el-date-picker v-model="shop.initial_time" :disabled="!!shop.id && !sourceChanged" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" /></el-form-item>
      <el-form-item v-if="sourceChanged"><el-checkbox v-model="shop.reset_cursor" :true-label="1" :false-label="0">确认切换来源或卖家，从上述起点重新拉取</el-checkbox><p class="muted">原单和业务订单保留；重复订单按原身份去重。</p></el-form-item>
      <el-form-item label="自动拉单"><el-switch v-model="shop.enabled" :active-value="1" :inactive-value="0" /><span class="spacer">间隔</span><el-input-number v-model="shop.interval_seconds" :min="10" :max="86400" size="small" controls-position="right" /> 秒</el-form-item>
      <el-form-item label="自动转单"><span>{{ shop.auto_convert ? '已开启' : '未开启' }}</span><el-button type="text" @click="$emit('navigate','conversion')">去转单与预占设置</el-button><p class="muted">本页只维护来源及同步方式，自动转单统一在“转单与预占”编辑。</p></el-form-item>
    </el-form><span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
  </configuration-panel>
</template>
<script>
import configurationEditor from './configurationEditor'
import { savePullConfig, verifyPullSource } from '@/api/order'
import { parseTime } from '@/utils/ruoyi'
const required = message => ({ required: true, message, trigger: 'change' })
export default {
  name: 'OrderIntakeSettings', mixins: [configurationEditor],
  data: () => ({ visible: false, loading: false, saving: false, shop: {}, catalog: { channels: [], sources: [] }, selectedShop: {}, originalSource: '', originalSeller: '', originalTime: '', verification: null, verifyRange: [], verifying: false, epoch: 0, shopRules: { channel_id: [required('请选择店铺')], source_key: [required('请选择推送库')], seller_nick: [required('请填写卖家账号')], initial_time: [required('请选择首次同步时间')] }}),
  computed: {
    editorState() { return this.shop },
    sourceChanged() { return !!this.shop.id && (this.originalSource !== this.shop.source_key || this.originalSeller !== this.shop.seller_nick) },
    sellerMismatch() { return !!this.selectedShop.seller_nick && !!this.shop.seller_nick && this.selectedShop.seller_nick !== this.shop.seller_nick },
    mappingKey() { return JSON.stringify([this.shop.channel_id, this.shop.source_key, this.shop.seller_nick]) },
    verificationKey() { return JSON.stringify([this.mappingKey, this.verifyRange]) }
  },
  watch: { mappingKey() { this.$set(this.shop, 'confirm_seller_mapping', 0) }, verificationKey() { this.verification = null } },
  beforeDestroy() { this.epoch++ },
  methods: {
    async open(row) { const epoch = ++this.epoch; this.loading = true; this.visible = true; this.verification = null; this.selectedShop = row; this.catalog.channels = [row]; this.verifyRange = [parseTime(new Date(Date.now() - 86400000)), parseTime(new Date())]; try { if (epoch !== this.epoch) return; this.catalog.sources = row.sources || []; this.shop = { channel_id: row.channel_id, channel_name: row.channel_name, id: null, enabled: 0, auto_convert: 0, allow_public_stock: 0, allowed_trade_types: ['fixed', 'guarantee_trade', 'tmall_i18n'], interval_seconds: 30, initial_time: parseTime(new Date(Date.now() - 86400000)), source_key: '', seller_nick: row.seller_nick || '', revision: 0, ...row.order, reset_cursor: 0, confirm_seller_mapping: 0 }; this.originalSource = this.shop.source_key; this.originalSeller = this.shop.seller_nick; this.originalTime = this.shop.initial_time; this.$nextTick(() => this.$refs.shopForm.clearValidate()) } finally { if (epoch === this.epoch) this.loading = false } },
    changeSource() { if (!this.shop.id) return; this.shop.initial_time = this.sourceChanged ? '' : this.originalTime; this.shop.reset_cursor = 0 },
    save() { if (this.readOnly || this.saving) return; this.$refs.shopForm.validate(async valid => { if (!valid) return; if (this.sellerMismatch && (!this.shop.id || this.sourceChanged) && !this.shop.confirm_seller_mapping) return this.$message.warning('请确认推送库账号归属当前店铺'); if (this.sourceChanged && !this.shop.reset_cursor) return this.$message.warning('请确认新的同步起点'); this.saving = true; try { await savePullConfig(this.shop); this.$message.success('订单接入已保存'); this.$emit('changed') } finally { this.saving = false } }) },
    async verifySource() { if (!this.shop.source_key || !this.shop.seller_nick || !this.verifyRange || this.verifyRange.length !== 2) return this.$message.warning('请先填写来源、卖家账号及验证时间'); if (this.verifying) return; const key = this.verificationKey; this.verifying = true; try { const r = await verifyPullSource({ channel_id: this.shop.channel_id, source_key: this.shop.source_key, seller_nick: this.shop.seller_nick, start_time: this.verifyRange[0], end_time: this.verifyRange[1] }); if (key === this.verificationKey) this.verification = r.data } finally { this.verifying = false } }
  }
}
</script>
<style scoped>.muted{color:#758397;font-size:12px;line-height:1.8}.spacer{margin:0 12px}.el-select{width:100%}</style>
