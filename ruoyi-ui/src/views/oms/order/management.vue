<template><div /></template>
<script>
import { settingsLocation, businessSettingsPath, syncTasksPath } from './orderBusinessPresentation'
export default { name: 'OrderManagement', created() { const q=this.$route.query; if(q.tab==='sources') this.$router.replace({path:businessSettingsPath,query:{scope:'sources'}}); else if(q.tab==='tasks'||q.action==='backfill') this.$router.replace({path:syncTasksPath,query:q}); else this.$router.replace(settingsLocation(q.kind==='REFUND'?'refund-intake':q.kind==='EXCHANGE'?'exchange-intake':'order-intake',q.channelId)) } }
</script>
