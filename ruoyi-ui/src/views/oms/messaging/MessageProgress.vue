<template><div class="message-progress"><span>后台任务：{{states[message.process_state] || message.process_state}}</span><div v-if="active && message.next_process_at">下次处理 {{message.next_process_at}}</div><div v-if="message.last_error && message.process_state!=='SUCCESS'">{{message.last_error}}</div><el-button type="text" size="mini" v-hasPermi="['oms:message:query']" @click="open">查看消息链路</el-button></div></template>
<script>
import { taskStates } from './messagePresentation'
export default {props:{message:{type:Object,required:true},type:{type:String,required:true}},data:()=>({states:taskStates}),computed:{active(){return ['PENDING','WAITING','PROCESSING'].includes(this.message.process_state)}},methods:{open(){this.$router.push({path:'/monitor/messages',query:{service:'order',type:this.type,event:this.message.event_id}})}}}
</script>
<style scoped>.message-progress{font-size:12px;line-height:1.7;color:#64748b;margin:6px 0}.message-progress .el-button{padding:2px 0;margin-left:8px}</style>
