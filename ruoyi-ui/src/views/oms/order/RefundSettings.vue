<template><div /></template>
<script>
import { settingsPath } from './refundConfigurationPresentation'
export default { name: 'RefundSettings', created() { this.$router.replace({ path: settingsPath, query: { ...this.$route.query, tab: 'intake' }}) } }
</script>
