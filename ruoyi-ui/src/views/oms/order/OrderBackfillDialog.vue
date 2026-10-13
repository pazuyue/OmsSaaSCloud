<template>
  <el-dialog title="补拉订单" :visible.sync="visible" width="min(700px, 96vw)" append-to-body :close-on-click-modal="false" :before-close="close">
    <el-alert v-if="clockError" title="服务器时间加载失败，请重试" type="error" :closable="false"><el-button type="text" @click="loadClock">重新加载</el-button></el-alert>
    <el-form ref="form" :model="form" label-width="110px" :rules="rules" :disabled="saving">
      <el-form-item label="补拉方式"><el-radio-group v-model="form.mode" @change="changeMode"><el-radio-button label="TIME">按时间</el-radio-button><el-radio-button label="TID">按平台单号</el-radio-button></el-radio-group></el-form-item>
      <el-form-item label="店铺" prop="channel_ids"><el-select :value="form.mode === 'TIME' ? form.channel_ids : form.channel_ids[0]" :multiple="form.mode === 'TIME'" filterable clearable style="width:100%" placeholder="选择已配置拉单的店铺" @input="selectShop"><el-option v-for="shop in channels.filter(s => s.pull_configured)" :key="shop.channel_id" :value="shop.channel_id" :label="shop.channel_name" /></el-select></el-form-item>
      <template v-if="form.mode === 'TIME'">
        <el-form-item label="推送更新时间" prop="range"><div class="shortcuts"><el-button v-for="s in shortcuts" :key="s.key" size="mini" @click="shortcut(s.key)">{{ s.label }}</el-button></div><el-date-picker v-model="form.range" type="datetimerange" value-format="yyyy-MM-dd HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间（不含）" style="width:100%" /></el-form-item>
        <el-alert v-if="willClamp" :title="'结束时间晚于当前时间，提交时将自动调整到服务器当前时间。'" type="info" show-icon :closable="false" />
        <p class="note">按推送库更新时间查询。大范围按天分段、分页执行；自动拉单进度保持独立。</p><p class="note server-clock">服务器时间：{{ clockText || '加载中' }}</p>
      </template>
      <template v-else>
        <el-form-item label="平台单号 TID" prop="tids"><el-input v-model="form.tids" type="textarea" :rows="6" maxlength="10000" show-word-limit placeholder="每行一个，或用逗号分隔；最多100个单号" /></el-form-item>
        <div class="tid-summary">有效 {{ parsed.valid.length }} 个 <span>重复 {{ parsed.duplicates }} 个</span><span :class="{ invalid: parsed.invalid.length }">格式错误 {{ parsed.invalid.length }} 个</span></div>
        <el-alert v-if="parsed.invalid.length" :title="'请修正单号：' + parsed.invalid.slice(0, 5).join('、')" type="error" :closable="false" />
        <p class="note">只查询所选店铺的推送库，不限制时间。未找到的单号单独列出；不会自动查询其他店铺或天猫接口。</p>
      </template>
      <p class="note">已有订单沿用正常同步规则，不重复建单或预占；影响履约的数据变化进入变更处理。</p>
    </el-form>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="clockError || !clockText" @click="submit">提交补拉</el-button></span>
  </el-dialog>
</template>
<script>
import { backfillOptions, backfillOrders } from '@/api/order'
const defaults = () => ({ mode: 'TIME', channel_ids: [], range: [], tids: '' })
const key = () => 'BF_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 14)
const format = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
export default {
  props: { channels: { type: Array, default: () => [] } },
  data() { return { visible: false, saving: false, form: defaults(), requestKey: key(), clockText: '', clockAt: 0, clockError: false, shortcuts: [{ key: 0, label: '今天' }, { key: -1, label: '昨天' }, { key: 3, label: '近3天' }, { key: 7, label: '近7天' }] } },
  computed: {
    parsed() { const values = this.form.tids.split(/[\s,，;；]+/).filter(Boolean); const valid = [], invalid = [], seen = new Set(); let duplicates = 0; values.forEach(tid => { if (!/^[1-9]\d{0,18}$/.test(tid) || (tid.length === 19 && tid > '9223372036854775807')) invalid.push(tid.slice(0, 30)); else if (seen.has(tid)) duplicates++; else { seen.add(tid); valid.push(tid) } }); return { valid, invalid, duplicates } },
    willClamp() { return this.clockText && this.form.range && this.form.range.length === 2 && this.form.range[1] > format(this.now()) },
    rules() { return { channel_ids: [{ validator: (_, value, done) => value.length > 0 && (this.form.mode === 'TIME' || value.length === 1) ? done() : done(new Error('请选择店铺；按单号补拉仅选一个店铺')), trigger: 'change' }], range: [{ validator: (_, value, done) => this.form.mode !== 'TIME' || (value && value.length === 2 && value[0] < value[1]) ? done() : done(new Error('请选择开始早于结束的时间范围')), trigger: 'change' }], tids: [{ validator: (_, value, done) => this.form.mode !== 'TID' || (!this.parsed.invalid.length && this.parsed.valid.length >= 1 && this.parsed.valid.length <= 100) ? done() : done(new Error('请输入1至100个格式正确的平台单号')), trigger: ['blur', 'change'] }] } }
  },
  watch: { form: { deep: true, handler() { if (!this.saving) this.requestKey = key() } } },
  methods: {
    async open(channel) { this.form = defaults(); if (channel) this.form.channel_ids = [channel]; this.requestKey = key(); this.visible = true; await this.loadClock(); if (this.clockText) this.shortcut(0); this.$nextTick(() => this.$refs.form.clearValidate()) },
    async loadClock() { this.clockError = false; try { const r = await backfillOptions(); this.clockText = r.data.server_time; this.clockAt = Date.now() } catch (_) { this.clockError = true } },
    now() { return new Date(new Date(this.clockText.replace(' ', 'T')).getTime() + Date.now() - this.clockAt) },
    shortcut(days) { if (!this.clockText) return; const end = this.now(), start = new Date(end); if (days === -1) { end.setHours(0, 0, 0, 0); start.setDate(start.getDate() - 1); start.setHours(0, 0, 0, 0) } else if (days === 0) start.setHours(0, 0, 0, 0); else start.setDate(start.getDate() - days); this.form.range = [format(start), format(end)] },
    selectShop(value) { this.form.channel_ids = this.form.mode === 'TIME' ? value || [] : value ? [value] : [] },
    changeMode() { if (this.form.mode === 'TID' && this.form.channel_ids.length > 1) this.form.channel_ids = []; this.$nextTick(() => this.$refs.form.clearValidate()) },
    close(done) { if (!this.saving) done() },
    submit() { this.$refs.form.validate(async valid => { if (!valid || this.saving) return; this.saving = true; try { const body = { mode: this.form.mode, channel_ids: this.form.channel_ids, request_key: this.requestKey }; if (body.mode === 'TID') body.tids = this.parsed.valid; else { body.start_time = this.form.range[0]; body.end_time = this.form.range[1] } const r = await backfillOrders(body); this.visible = false; this.$message.success({ message: r.data.range_clamped ? `补拉已提交，实际截止时间调整为 ${r.data.end_time}` : '补拉任务已提交', duration: r.data.range_clamped ? 7000 : 3000 }); this.$emit('submitted', r.data) } finally { this.saving = false } }) }
  }
}
</script>
<style scoped>.shortcuts{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}.shortcuts .el-button{margin:0}.note{font-size:13px;color:#64748b;line-height:1.7}.tid-summary{display:flex;gap:20px;flex-wrap:wrap;margin:12px 0;color:#64748b}.invalid{color:#d84b4b}.server-clock{white-space:nowrap}</style>
