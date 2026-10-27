<template>
  <configuration-panel :embedded="embedded" :read-only="readOnly" title="店铺平台发货回传配置" :visible.sync="visible" width="min(720px, 96vw)" append-to-body :close-on-click-modal="false">
    <el-alert title="本阶段仅开放模拟回传，真实天猫接口尚未启用。自动任务仅处理开启后新增的测试包裹；已有测试单据可手动生成任务。" type="info" :closable="false" show-icon /><el-form v-loading="loading" label-width="100px" class="config-form"><el-form-item label="店铺"><el-select v-model="channel" filterable placeholder="选择店铺" :disabled="saving||embedded" @change="load"><el-option v-for="shop in channels" :key="shop.channel_id" :value="shop.channel_id" :label="shop.channel_name" /></el-select></el-form-item><template v-if="config"><el-form-item label="回传模式"><el-radio-group v-model="config.mode"><el-radio label="OFF">关闭</el-radio><el-radio label="SIMULATION">模拟回传</el-radio></el-radio-group></el-form-item><el-form-item label="标准快递关联"><router-link to="/common-config/carriers/channels">前往公共配置 · 渠道快递配置</router-link><p class="hint">发货回传按实际包裹快递与“销售发货回传”用途转换，不在店铺重复维护编码。</p></el-form-item><p class="hint">关闭后暂停新的模拟发送，已有任务与日志保留；待确认任务仍可核对原回执。</p></template></el-form><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <span slot="footer"><el-button @click="visible=false" :disabled="saving">取消</el-button><el-button type="primary" :disabled="!config || loading" :loading="saving" @click="save">保存</el-button></span>
  </configuration-panel>
</template>
<script>
import configurationEditor from './configurationEditor'
import { platformShippingConfig, savePlatformShippingConfig } from '@/api/order'
export default { mixins: [configurationEditor],
  props: { channels: { type: Array, default: () => [] } }, data() { return { visible: false, loading: false, saving: false, channel: null, config: null, mappings: [], error: '', requestId: 0 } },
  computed: { editorState() { return this.config ? { config: this.config, mappings: this.mappings } : null } },
  watch: { visible(v) { if (!v) this.requestId++ } },
  methods: {
    open(channel) { this.channel = channel || null; this.config = null; this.mappings = []; this.error = ''; this.visible = true; if (this.channel) this.load() },
    async load() { const seq = ++this.requestId; this.loading = true; this.error = ''; this.config = null; try { const r = await platformShippingConfig(this.channel); if (seq !== this.requestId) return; this.config = r.data; this.mappings = [] } catch (_) { if (seq === this.requestId) this.error = '配置加载失败，请重新选择店铺' } finally { if (seq === this.requestId) this.loading = false } },
    async save() { if (this.saving || this.readOnly) return; this.saving = true; this.error = ''; try { await savePlatformShippingConfig(this.channel, { mode: this.config.mode, control_version: this.config.control_version }); this.$message.success('配置已保存'); this.visible = false; this.$emit('changed') } catch (_) { this.error = '配置未保存，请刷新配置后重试' } finally { this.saving = false } }
  }
}
</script>
<style scoped>.config-form{margin-top:20px}.mapping{display:flex;align-items:center;gap:8px;margin:8px 0}.hint{font-size:12px;color:#8490a2;line-height:1.7}</style>
