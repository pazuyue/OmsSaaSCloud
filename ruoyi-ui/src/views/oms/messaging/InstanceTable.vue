<template><el-table :data="rows" empty-text="尚无消费实例"><el-table-column label="实例 / 队列" min-width="240"><template slot-scope="s"><div class="mono">{{s.row.instance_id}}</div><small>{{s.row.business_type}}</small></template></el-table-column><el-table-column label="状态" width="140"><template slot-scope="s"><el-tag size="small" :type="s.row.retired?'info':!s.row.online?'danger':s.row.state==='RUNNING'?'success':'info'">{{s.row.retired?'已退出':!s.row.online?'心跳过期':state(s.row.state)}}</el-tag></template></el-table-column><el-table-column prop="applied_version" label="生效版本" width="95"/><el-table-column prop="actual_consumers" label="消费者" width="85"/><el-table-column prop="active_work" label="在途" width="65"/><el-table-column label="心跳 / 说明" min-width="210"><template slot-scope="s"><div>{{s.row.last_seen}}</div><small>{{s.row.last_error||'—'}}</small></template></el-table-column></el-table></template>
<script>
import { stateNames } from './queuePresentation'
export default {props:['rows'],methods:{state(s){return stateNames[s]||s}}}
</script>

<style scoped>.mono{font-family:Consolas,monospace;word-break:break-all}small{color:#8492a6;line-height:1.8}</style>
