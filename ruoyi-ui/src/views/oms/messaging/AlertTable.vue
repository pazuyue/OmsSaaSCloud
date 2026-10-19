<template><el-table :data="rows" empty-text="暂无告警"><el-table-column prop="business_type" label="业务队列" width="155"/><el-table-column label="状态" width="110"><template slot-scope="s"><el-tag size="small" :type="s.row.state==='FIRING'?'danger':s.row.state==='RECOVERED'?'success':'warning'">{{state(s.row.state)}}</el-tag></template></el-table-column><el-table-column prop="message" label="告警说明" min-width="250"/><el-table-column label="时间" width="195"><template slot-scope="s">{{s.row.create_time||s.row.last_seen}}</template></el-table-column><el-table-column label="处理入口" width="135"><template slot-scope="s"><el-button v-if="target(s.row)" type="text" @click="$emit('task',s.row)">{{target(s.row).dead?'核查死信':'查看相关任务'}}</el-button><span v-else>核对运行配置</span></template></el-table-column></el-table></template>
<script>
import { stateNames } from './queuePresentation'
import { alertTarget } from './messagePresentation'
export default {props:['rows'],methods:{target:alertTarget,state(s){return stateNames[s]||s}}}
</script>
