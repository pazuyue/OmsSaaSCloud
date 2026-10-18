<template>
  <el-table :data="rows" size="small" :max-height="maxHeight" empty-text="当前条件下暂无仓库交互日志">
    <el-table-column label="时间" prop="create_time" width="160" />
    <el-table-column label="业务 / 单据" min-width="240"><template slot-scope="s"><el-tag size="mini" type="info">{{ businesses[s.row.business_type] || s.row.business_type }}</el-tag><div class="code">{{ s.row.ticket_sn || '未关联单据' }} <el-button v-if="s.row.ticket_sn" v-clipboard:copy="s.row.ticket_sn" v-clipboard:success="copied" v-clipboard:error="copyFailed" type="text" size="mini" icon="el-icon-document-copy" title="复制完整单号" aria-label="复制完整单号"/></div><div v-if="s.row.receipt_sn" class="muted">到货：{{ s.row.receipt_sn }}</div><div v-if="s.row.purchase_sn" class="muted">采购：{{ s.row.purchase_sn }}</div><div v-if="s.row.exchange_id && s.row.business_owner_sn" class="muted">归属换货：{{ s.row.business_owner_sn }}</div><div v-if="s.row.refund_id && s.row.business_owner_sn" class="muted">归属退款：{{ s.row.business_owner_sn }}</div><div v-if="s.row.order_sn" class="muted">OMS：{{ s.row.order_sn }}</div><div v-if="s.row.platform_tid" class="muted">平台：{{ s.row.platform_tid }}</div></template></el-table-column>
    <el-table-column label="仓库 / 货主 / 连接" min-width="200"><template slot-scope="s"><div>{{ warehouse(s.row) }}</div><div class="muted">货主：{{ owner(s.row) }}</div><div class="muted">{{ s.row.connection_name || '连接已移除' }} · {{ providerName(s.row.provider) }}</div><el-tag size="mini" :type="s.row.environment === 'PRODUCTION' ? 'success' : 'info'">{{ s.row.environment === 'TEST' ? '测试' : s.row.environment === 'PRODUCTION' ? '正式' : '环境未知' }}</el-tag></template></el-table-column>
    <el-table-column label="动作 / 方向" min-width="125"><template slot-scope="s">{{ actions[s.row.action] || s.row.action }}<div class="muted">{{ s.row.direction === 'LOCAL' ? 'OMS 本地处理' : s.row.direction === 'IN' ? '仓库 → OMS' : 'OMS → 仓库' }}</div></template></el-table-column>
    <el-table-column label="本次交互结果（发生时）" min-width="230"><template slot-scope="s"><el-tag size="small" :type="resultTone(s.row.result)">{{ results[s.row.result] || s.row.result }}</el-tag><div class="muted">{{ s.row.duration_ms }} ms</div><div class="interaction-summary">{{ interactionSummary(s.row) }}</div></template></el-table-column>
    <el-table-column label="单据当前情况（实时）" min-width="210"><template slot-scope="s"><span :class="['progress', s.row.business_tone]">{{ s.row.business_progress }}</span><div v-if="s.row.warehouse_status" class="muted">仓库状态：{{ s.row.warehouse_status }}</div></template></el-table-column>
    <el-table-column label="本次原因摘要" min-width="200" show-overflow-tooltip><template slot-scope="s">{{ s.row.error_info || '—' }}</template></el-table-column>
    <el-table-column label="操作" width="90" fixed="right"><template slot-scope="s"><el-button type="text" @click="$emit('detail', s.row)">详情 / 链路</el-button></template></el-table-column>
  </el-table>
</template>
<script>
import { actions, results, businesses, resultTone, providerName } from './wmsLogLabels'
export default {
  props: { rows: { type: Array, default: () => [] }, options: { type: Object, default: () => ({}) }, maxHeight: { type: [Number, String], default: undefined } },
  data() { return { actions, results, businesses } },
  methods: {
    resultTone, providerName,
    copied(){this.$message.success("单号已复制")},copyFailed(){this.$message.warning("复制失败，请手工复制完整单号")},
    interactionSummary(row) { if(row.result_summary)return row.result_summary;if(row.error_info)return row.error_info;if(row.result!=='SUCCESS')return '';return row.action==='REFUND_RETURN_CANCEL'?'本次截单交互成功；具体范围查看当次报文':row.action==='REFUND_RETURN_RECEIPT'?'本次回传已接收；不等于库存已入账':'本次接口交互成功' },
    warehouse(row) { const found = (this.options.warehouses || []).find(w => w.code === row.real_store_code); return found ? `${found.name}（${found.code}）` : row.real_store_name || row.real_store_code || row.external_warehouse || '仓库待关联' },
    owner(row) { const found = (this.options.owners || []).find(o => o.code === row.owner_code); return found ? `${found.name}（${found.code}）` : row.owner_name || row.owner_code || row.external_owner || '待关联' }
  }
}
</script>
<style scoped>
.interaction-summary{font-size:12px;line-height:1.7;color:#52657c;margin-top:6px}.code{overflow-wrap:anywhere;line-height:1.7;margin-top:5px}.muted{font-size:12px;line-height:1.7;color:#7c899b;overflow-wrap:anywhere}.progress{line-height:1.7}.warning{color:#b57a13}.danger{color:#ce4949}.success{color:#278558}
</style>
