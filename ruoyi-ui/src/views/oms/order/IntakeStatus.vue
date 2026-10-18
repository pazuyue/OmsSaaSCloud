<template><div class="intake-status"><el-tag size="small" :type="status.tone">{{ status.label }}</el-tag><template v-if="config && allowed"><p>{{ kind==='EXCHANGE' ? '平台 API' : config.source_name || '推送库' }}</p><p>自动进度：{{ config.cursor_time || '尚未同步' }}</p><p>自动转单：{{ Number(config.auto_convert) ? '开启' : '关闭' }}</p><p v-if="kind==='EXCHANGE'">平台申请先保存，再按设置转业务单</p><el-tooltip v-if="config.last_error" :content="config.last_error"><p class="error">{{ config.last_error }}</p></el-tooltip><p v-else-if="kind!=='EXCHANGE' && !Number(config.source_enabled)" class="error">推送库已停用或不存在</p></template></div></template>
<script>
import { intakeStatus } from './intakePresentation'
export default { name: 'IntakeStatus', props: { config: Object, allowed: Boolean, kind: String }, computed: { status() { return intakeStatus(this.config, this.allowed, this.kind) } } }
</script>
<style scoped>.intake-status p{font-size:12px;color:#8492a6;line-height:1.7;margin:3px 0}.intake-status .error{color:#b77515;max-width:260px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}</style>
