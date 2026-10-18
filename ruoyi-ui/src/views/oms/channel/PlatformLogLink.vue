<template><el-button v-hasPermi="['channel:platform:log']" :size="size" type="text" @click="open">平台交互日志</el-button></template>
<script>
export default { props: { number: [String, Number], numberType: { type: String, default: 'PLATFORM_TID' }, channelId: [String, Number], category: String, size: { type: String, default: 'small' } }, methods: { open() { this.$router.push({ path: '/oms-channel/platformLogs', query: { numberType: this.numberType, number: this.number || undefined, channelId: this.channelId || undefined, category: this.category || undefined, allTime: this.number ? 'true' : undefined } }) } } }
</script>
