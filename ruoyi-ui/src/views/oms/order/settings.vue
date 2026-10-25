<template><div /></template>
<script>
import { legacySettingsLocation } from './orderBusinessPresentation'
export default { name: 'OrderSettings', created() { this.$router.replace(legacySettingsLocation(this.$route)) } }
</script>
