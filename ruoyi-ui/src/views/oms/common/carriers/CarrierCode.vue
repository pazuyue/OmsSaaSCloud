<template>
  <span class="carrier-code"><code :title="String(value || '')">{{ value || '—' }}</code><el-button v-if="value" type="text" icon="el-icon-copy-document" :aria-label="'复制'+label" :title="'复制'+label" @click.stop="copy" /></span>
</template>
<script>
export default {
  props: { value: { type: [String, Number], default: '' }, label: { type: String, default: '编码' } },
  methods: { async copy() { try { if (navigator.clipboard) await navigator.clipboard.writeText(String(this.value)); else { const input = document.createElement('textarea'); input.value = String(this.value); input.style.position = 'fixed'; input.style.opacity = '0'; document.body.appendChild(input); try { input.select(); if (!document.execCommand('copy')) throw new Error('copy') } finally { input.remove() } } this.$message.success(this.label+'已复制') } catch (_) { this.$message.warning('复制失败，请选中编码后复制') } } }
}
</script>
<style scoped>.carrier-code{display:flex;gap:5px;align-items:center;min-width:0;max-width:100%;color:#718198}.carrier-code code{font:12px/1.7 Consolas,monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;user-select:text}.carrier-code .el-button{padding:4px;flex-shrink:0}</style>
