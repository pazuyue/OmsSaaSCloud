<template>
 <div v-loading="loading">
  <el-alert title="死信已留存在核查台账，代理中的副本在留痕成功后确认移除。不会直接重放；处理前以当前业务任务结果为准。" type="info" show-icon :closable="false"/>
  <p class="muted">业务结果每轮最多核对 20 条，约 20 秒一轮；下方显示最近一次核对结果。刷新可重新读取。</p>
  <el-button size="small" icon="el-icon-refresh" @click="load">刷新死信台账</el-button>
  <el-table :data="rows" empty-text="暂无已留痕死信">
   <el-table-column label="来源 / 业务任务" min-width="240"><template slot-scope="s"><strong>{{s.row.business_number || '身份未通过校验'}}</strong><div class="muted">{{s.row.company_code || '不关联公司'}} · {{reason(s.row.death_reason)}}</div><div class="mono muted">{{s.row.event_id || '仅保留消息指纹，不保存原始报文'}}</div></template></el-table-column>
   <el-table-column label="当前业务结果" min-width="210"><template slot-scope="s"><el-tag size="small" :type="s.row.business_state==='SUCCESS'?'success':'warning'">{{states[s.row.business_state]}}</el-tag><div class="muted">{{s.row.business_state==='SUCCESS'?'业务已完成，无需重试':Number(s.row.verified)?'打开任务核对条件及最新结果':'请核对消息生产方与队列路由'}}</div><div class="muted">核对 {{s.row.checked_at}}</div></template></el-table-column>
   <el-table-column label="核查记录" min-width="200"><template slot-scope="s"><div>{{s.row.review_reason || '尚未登记人工核查'}}</div><div class="muted" v-if="s.row.reviewed_at">{{s.row.reviewed_by}} · {{s.row.reviewed_at}}</div><el-button type="text" @click="history(s.row)">核查历史</el-button></template></el-table-column>
   <el-table-column label="操作" width="125"><template slot-scope="s"><el-button v-if="Number(s.row.verified)" type="text" @click="task(s.row)">核对业务任务</el-button><el-button type="text" v-hasPermi="['oms:queue:edit']" @click="review(s.row)">登记核查</el-button></template></el-table-column>
  </el-table>
  <pagination v-show="total>0" :total="total" :page.sync="page" :limit.sync="size" @pagination="load"/>
  <el-dialog title="死信核查历史" :visible.sync="historyVisible" append-to-body width="650px"><el-table :data="reviews"><el-table-column prop="create_time" label="时间" width="175"/><el-table-column prop="actor" label="核查人" width="90"/><el-table-column prop="reason" label="核查依据"/></el-table></el-dialog>
 </div>
</template>
<script>
import request from '@/utils/request'
import { taskStates } from './messagePresentation'
export default { props: ['type', 'service'], data() { return { rows: [], total: 0, page: 1, size: 20, loading: false, states: taskStates, reviews: [], historyVisible: false, seq: 0 } }, watch: { type: { immediate: true, handler() { this.page = 1; this.load() } } }, methods: {
 async load() { const seq = ++this.seq; this.loading = true; try { const r = await request({ url: `/system/mqQueues/${this.type}/dead-letters`, params: { pageNum: this.page, pageSize: this.size } }); if (seq === this.seq) { this.rows = r.data.rows; this.total = r.data.total } } finally { if (seq === this.seq) this.loading = false } },
 reason(v) { return { rejected: '消费者拒收', expired: '消息过期', maxlen: '队列容量限制', delivery_limit: '代理投递次数超限', UNVERIFIED_SOURCE: '来源未通过校验', INVALID_ENVELOPE: '消息身份或格式不一致' }[v] || v },
 async task(row) { await request({ url: `/${this.service}/messages/${row.latest_event_id || row.event_id}` }); this.$emit('task', { service_code: this.service, business_type: this.type, event: row.latest_event_id || row.event_id }) },
 async history(row) { const r = await request({ url: `/system/mqQueues/dead-letters/${row.id}/reviews` }); this.reviews = r.data; this.historyVisible = true },
 async review(row) { try { const { value } = await this.$prompt('登记核查发现及处理依据。此操作仅留痕，不会执行业务或标记业务成功。', '登记死信核查', { inputType: 'textarea', inputValidator: v => !!v && !!v.trim() && v.length <= 255, inputErrorMessage: '请填写255字以内的依据' }); await request({ url: `/system/mqQueues/dead-letters/${row.id}/review`, method: 'post', data: { reason: value.trim() } }); this.$message.success('核查已留痕'); await this.load() } catch (e) { if (e !== 'cancel' && e !== 'close') console.error('Dead letter review unavailable') } }
} }
</script>
<style scoped>.muted{font-size:12px;color:#8492a6;line-height:1.8}.mono{font-family:Consolas,monospace;word-break:break-all}</style>
