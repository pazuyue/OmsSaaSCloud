<template>
  <section v-if="embedded" v-show="visible" class="configuration-panel">
    <div :inert="readOnly?'':null" :class="{'read-only':readOnly}"><slot /></div>
    <div class="panel-footer" :inert="readOnly?'':null"><slot name="footer" /></div>
  </section>
  <el-dialog v-else v-bind="$attrs" :visible="visible" append-to-body :close-on-click-modal="false" @update:visible="$emit('update:visible',$event)"><slot /><template slot="footer"><slot name="footer" /></template></el-dialog>
</template>
<script>
export default { name: 'ConfigurationPanel', inheritAttrs: false, props: { embedded: Boolean, visible: Boolean, readOnly: Boolean }}
</script>
<style scoped>
.configuration-panel{padding:8px 0}.panel-footer{position:sticky;bottom:0;background:#fff;border-top:1px solid #e7edf4;padding:16px 0;margin-top:20px;text-align:right;z-index:2}.panel-footer ::v-deep .el-button:first-child:not(:last-child){display:none}.configuration-panel ::v-deep .review-layout,.configuration-panel ::v-deep .allocation-layout{max-height:none;overflow:visible;padding:0}.read-only{opacity:.8}
</style>
