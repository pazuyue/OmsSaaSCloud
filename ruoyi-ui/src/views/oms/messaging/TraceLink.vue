<template>
 <div v-if="traceId" class="trace-link">
  <span class="trace-id">{{ traceId }}</span>
  <el-button type="text" size="mini" @click="copy">复制追踪号</el-button>
  <el-link v-if="safeUrl" :href="safeUrl" target="_blank" rel="noopener noreferrer" type="primary">打开链路平台</el-link>
 </div>
 <span v-else class="muted">未记录</span>
</template>
<script>
import Clipboard from 'clipboard'
export default {
 name:'TraceLink',
 props:{traceId:{type:String,default:''},uiUrl:{type:String,default:''}},
 computed:{safeUrl(){try{const url=new URL(this.uiUrl);return ['http:','https:'].includes(url.protocol)?url.href:''}catch(e){return ''}}},
 methods:{copy(event){const clipboard=new Clipboard(event.currentTarget,{text:()=>this.traceId});clipboard.on('success',()=>{this.$message.success('追踪号已复制，可在链路平台按 Trace ID 查询');clipboard.destroy()});clipboard.on('error',()=>{this.$message.warning('复制失败，请选中追踪号手动复制');clipboard.destroy()});clipboard.onClick(event)}}
}
</script>
<style scoped>
.trace-link{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:12px}.trace-id{font-family:Consolas,monospace;word-break:break-all;user-select:all}.trace-link .el-link{font-size:12px}.muted{color:#8492a6}
</style>
