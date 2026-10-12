<template>
  <el-dialog :title="querying ? '核对仓库下发结果' : '下发发货单至仓库'" :visible.sync="visible" width="min(1000px, 96vw)" append-to-body :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
    <div v-loading="loading">
      <el-alert :title="querying ? '查询仓库受理结果，不重新创建仓库单据；实际出库数量由后续回传更新。' : '下发后交由仓库履约，快递和商品明细将固定；仅提交校验通过的发货单。'" type="info" :closable="false" show-icon />
      <el-table v-if="!results.length" key="preview" :data="preview" :max-height="380" class="summary">
        <el-table-column label="发货单 / 实体仓" min-width="280"><template slot-scope="s">{{ original(s.row.id).shipment_sn }}<div class="hint">{{ original(s.row.id).real_store_name || original(s.row.id).real_store_code }}</div></template></el-table-column>
        <el-table-column label="数量 / 快递" min-width="150"><template slot-scope="s">{{ original(s.row.id).quantity }} 件<div class="hint">{{ original(s.row.id).carrier_name || '尚未匹配' }}</div></template></el-table-column>
        <el-table-column v-if="!querying" label="仓库连接 / 环境" min-width="190"><template slot-scope="s"><template v-if="s.row.route">{{ s.row.route.connection_name }}<div class="hint">{{ s.row.route.provider === 'QIMEN' ? '奇门' : '京东虎符' }} · {{ s.row.route.environment === 'PRODUCTION' ? '正式环境' : '测试环境' }}</div><div class="hint">仓库 {{ s.row.route.external_warehouse }} / 货主 {{ s.row.route.external_owner }}</div></template><span v-else>—</span></template></el-table-column>
        <el-table-column label="当前说明" min-width="270"><template slot-scope="s"><span :class="s.row.success ? '' : 'failure'">{{ s.row.message }}</span></template></el-table-column>
      </el-table>
      <p v-if="!querying && !loading && !eligible.length && !results.length" class="failure">没有可下发的发货单，请先处理上述原因。</p>
      <el-table v-if="results.length" key="results" :data="results" :max-height="400" class="summary"><el-table-column label="发货单" min-width="280"><template slot-scope="s">{{ original(s.row.id).shipment_sn }}</template></el-table-column><el-table-column label="结果" width="120"><template slot-scope="s"><el-tag size="small" :type="s.row.success ? 'success' : s.row.pending ? 'warning' : 'danger'">{{ s.row.success ? querying ? '已确认' : '已提交' : s.row.pending ? '待核对' : '未完成' }}</el-tag></template></el-table-column><el-table-column label="说明" prop="message" min-width="320" /></el-table>
      <p v-if="saving" class="hint">正在处理，请等待本次结果。</p>
    </div>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">{{ results.length ? '关闭' : '取消' }}</el-button><el-button v-if="!results.length" type="primary" :disabled="loading || !eligible.length" :loading="saving" @click="submit">{{ querying ? '查询仓库结果' : `确认下发（${eligible.length} 张）` }}</el-button></span>
  </el-dialog>
</template>
<script>
import { previewShipmentDispatch, dispatchShipments, queryShipmentWarehouse } from '@/api/order'
export default {
  data() { return { visible: false, loading: false, saving: false, querying: false, items: [], preview: [], results: [], requestId: 0 } },
  computed: { eligible() { return this.items.filter(s => this.preview.some(p => p.id === s.id && p.success)) } },
  methods: {
    original(id) { return this.items.find(s => s.id === id) || {} },
    async open(rows, action = 'SUBMIT') {
      if (this.saving || !rows.length) return; const id = ++this.requestId; this.visible = true; this.items = rows.map(s => ({ ...s })); this.querying = action === 'QUERY'; this.results = []; this.preview = []; this.loading = !this.querying;
      if (this.querying) { this.preview = this.items.map(s => ({ id: s.id, success: true, message: s.wms_error || s.wms_query_message || '查询最新仓库受理结果' })); return }
      try { for (let offset = 0; offset < this.items.length; offset += 20) { const r = await previewShipmentDispatch({ items: this.items.slice(offset, offset + 20).map(s => ({ id: s.id, control_version: s.control_version })) }); if (id !== this.requestId) return; this.preview.push(...r.data) } }
      catch (_) { if (id === this.requestId) this.preview = this.items.map(s => ({ id: s.id, success: false, message: '下发条件暂未确认，请关闭后刷新重试' })) }
      finally { if (id === this.requestId) this.loading = false }
    },
    async submit() {
      if (this.saving) return; this.saving = true; const rows = [...this.eligible];
      try {
        if (this.querying) { for (const row of rows) { try { const data = (await queryShipmentWarehouse(row.id)).data; this.results.push({ ...data, id: row.id, success: data.query_success === undefined ? data.success === true || data.dispatch_state === 'ACCEPTED' : data.query_success, pending: ['PENDING', 'SENDING', 'UNKNOWN', 'PROCESSING'].includes(data.dispatch_state), message: data.query_message || data.message || data.last_error || '结果暂未确认，请刷新后核对' }) } catch (_) { this.results.push({ id: row.id, success: false, pending: true, message: '仓库查询未确认，请稍后核对' }) } } }
        else { for (let offset = 0; offset < rows.length; offset += 20) { const chunk = rows.slice(offset, offset + 20); try { const r = await dispatchShipments({ items: chunk.map(s => ({ id: s.id, control_version: s.control_version })) }); this.results.push(...r.data) } catch (_) { this.results.push(...chunk.map(s => ({ id: s.id, success: false, pending: true, message: '提交结果未确认，请刷新后查询仓库结果' }))); this.results.push(...rows.slice(offset + 20).map(s => ({ id: s.id, success: false, message: '尚未提交，请刷新后重新选择' }))); break } } }
      } finally { this.saving = false; this.$emit('changed') }
    }
  }
}
</script>
<style scoped>.summary{margin:18px 0}.hint{font-size:13px;color:#8490a2;margin:6px 0;line-height:1.5}.failure{color:#c27b12;line-height:1.5;overflow-wrap:anywhere}</style>
