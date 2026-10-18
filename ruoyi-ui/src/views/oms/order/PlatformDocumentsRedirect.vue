<template><div class="app-container">正在打开平台单据…</div></template>
<script>
import { legacyDocumentKinds, documentLocation } from './platformDocumentsPresentation'
export default { name:'PlatformDocumentsRedirect', created(){this.redirect()},watch:{'$route.fullPath'(){this.redirect()}},methods:{redirect(){const kind=legacyDocumentKinds[this.$route.path];if(kind)this.$router.replace(documentLocation(kind,this.$route.query))}} }
</script>
