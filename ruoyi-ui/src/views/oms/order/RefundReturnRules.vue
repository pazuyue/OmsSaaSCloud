<template><div /></template>
<script>
import { settingsLocation } from './orderBusinessPresentation'
export default { name: 'RefundReturnRules', created() { this.$router.replace(settingsLocation('refund-returns',this.$route.query.channelId,this.$route.query)) } }
</script>
