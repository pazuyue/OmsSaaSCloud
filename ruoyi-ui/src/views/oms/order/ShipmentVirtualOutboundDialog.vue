<template>
  <el-dialog :title="action === 'QUERY' ? '核对虚拟出库结果' : action === 'RETRY' ? '重试原虚拟出库任务' : '发货单虚拟出库'" :visible.sync="visible" width="min(960px, 96vw)" append-to-body :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
    <div v-loading="loading">
      <el-alert :title="action === 'SUBMIT' ? '按仓库、批次和预占来源记账；库存确认后更新已发货数量。未出库数量继续占用。' : action === 'RETRY' ? '使用原任务和原数量重试，不创建新的出库指令。' : '仅核对原库存回执，不重新提交出库。'" type="info" show-icon :closable="false" />
      <template v-if="!results.length">
        <el-table v-if="action === 'SUBMIT' && items.length === 1 && !checked" key="quantity" :data="lines" max-height="360" class="summary">
          <el-table-column label="商品 / SKU" min-width="180"><template slot-scope="s">{{ s.row.goods_name }}<div class="hint">{{ s.row.sku_sn }}</div></template></el-table-column>
          <el-table-column label="虚拟仓 / 批次" min-width="160"><template slot-scope="s">{{ s.row.store_code }}<div class="hint">批次 {{ s.row.batch_id }}</div></template></el-table-column>
          <el-table-column label="单据 / 已出库 / 剩余" min-width="170"><template slot-scope="s">{{ s.row.quantity }} / {{ s.row.shipped_quantity || 0 }} / {{ s.row.remaining }} 件</template></el-table-column>
          <el-table-column label="本次出库数量" width="190"><template slot-scope="s"><el-input-number v-model="s.row.take" :min="0" :max="s.row.remaining" :precision="0" :disabled="!s.row.remaining" size="small" /></template></el-table-column>
        </el-table>
        <el-table v-else key="summary" :data="checked ? preview : items" max-height="360" class="summary">
          <el-table-column label="发货单 / 实体仓" min-width="280"><template slot-scope="s">{{ original(s.row.id).shipment_sn }}<div class="hint">{{ original(s.row.id).real_store_name || original(s.row.id).real_store_code }}</div></template></el-table-column>
          <el-table-column label="本次数量" width="120"><template slot-scope="s">{{ checked ? s.row.quantity || '—' : action === 'SUBMIT' ? remaining(s.row) : s.row.virtual_outbound_quantity || 0 }} 件</template></el-table-column>
          <el-table-column label="处理说明" min-width="300"><template slot-scope="s"><span :class="checked && !s.row.success ? 'failure' : ''">{{ s.row.message || s.row.virtual_outbound_error || (action === 'SUBMIT' ? '全部剩余数量虚拟出库' : '核对原虚拟出库任务') }}</span></template></el-table-column>
        </el-table>
        <p v-if="action === 'SUBMIT'" class="hint">本次提交 {{ totalQuantity }} 件。部分出库需店铺规则允许部分发货；下次可继续处理剩余数量。</p>
      </template>
      <el-table v-else key="results" :data="results" class="summary" max-height="380"><el-table-column label="发货单" min-width="260"><template slot-scope="s">{{ original(s.row.id).shipment_sn }}</template></el-table-column><el-table-column label="结果" width="110"><template slot-scope="s"><el-tag size="small" :type="s.row.success ? 'success' : 'warning'">{{ s.row.success ? action === 'QUERY' ? '已确认' : '已提交' : '待处理' }}</el-tag></template></el-table-column><el-table-column label="说明" prop="message" min-width="310" /></el-table>
    </div>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">{{ results.length ? '关闭' : '取消' }}</el-button><el-button v-if="checked && action === 'SUBMIT' && !results.length" :disabled="saving" @click="checked=false">修改数量</el-button><el-button v-if="!results.length" type="primary" :loading="saving" :disabled="loading || (action === 'SUBMIT' && !totalQuantity)" @click="next">{{ action === 'QUERY' ? '核对出库回执' : action === 'RETRY' ? '确认重试原任务' : checked ? `确认虚拟出库（${totalQuantity} 件）` : '核对出库数量' }}</el-button></span>
  </el-dialog>
</template>
<script>
import { shipmentDetail, previewVirtualOutbound, submitVirtualOutbound, retryVirtualOutbound, queryVirtualOutbound } from '@/api/order'
export default {
  data() { return { visible: false, loading: false, saving: false, action: 'SUBMIT', items: [], lines: [], preview: [], checked: false, results: [], requestId: 0 } },
  computed: {
    eligible() { return this.items.filter(s => this.preview.some(p => p.id === s.id && p.success)) },
    totalQuantity() { return this.checked ? this.preview.filter(p => p.success).reduce((n, p) => n + Number(p.quantity || 0), 0) : this.items.length === 1 ? this.lines.reduce((n, l) => n + Number(l.take || 0), 0) : this.items.reduce((n, s) => n + this.remaining(s), 0) }
  },
  methods: {
    original(id) { return this.items.find(s => s.id === id) || {} },
    remaining(s) { return Math.max(0, Number(s.quantity) - Number(s.shipped_quantity || 0)) },
    payload(rows) { return { items: rows.map(s => ({ id: s.id, control_version: s.control_version, ...(this.items.length === 1 ? { lines: this.lines.map(l => ({ id: l.id, quantity: l.take || 0 })) } : {}) })) } },
    async open(rows, action = 'SUBMIT') {
      if (this.saving || !rows.length) return; const request = ++this.requestId; this.visible = true; this.action = action; this.items = rows.map(s => ({ ...s })); this.lines = []; this.preview = []; this.checked = false; this.results = []; this.loading = action === 'SUBMIT' && rows.length === 1;
      if (this.loading) { try { const r = await shipmentDetail(rows[0].id); if (request !== this.requestId) return; this.items = [r.data]; this.lines = r.data.lines.map(l => ({ ...l, remaining: this.remaining(l), take: this.remaining(l) })) } catch (_) { if (request === this.requestId) this.results = [{ id: rows[0].id, success: false, message: '出库明细暂未加载，请刷新后重试' }] } finally { if (request === this.requestId) this.loading = false } }
    },
    async next() {
      if (this.saving) return; this.saving = true;
      try {
        if (this.action === 'SUBMIT' && !this.checked) { this.preview = []; for (let offset = 0; offset < this.items.length; offset += 20) { const r = await previewVirtualOutbound(this.payload(this.items.slice(offset, offset + 20))); this.preview.push(...r.data) } this.checked = true; return }
        if (this.action !== 'SUBMIT') { for (const row of this.items) { try { const r = await (this.action === 'QUERY' ? queryVirtualOutbound(row.id) : retryVirtualOutbound(row.id, { control_version: row.control_version })); this.results.push({ ...r.data, id: row.id }) } catch (_) { this.results.push({ id: row.id, success: false, message: '原任务结果暂未确认，请刷新后核对' }) } } }
        else { const rows = [...this.eligible]; for (let offset = 0; offset < rows.length; offset += 20) { const chunk = rows.slice(offset, offset + 20); try { const r = await submitVirtualOutbound(this.payload(chunk)); this.results.push(...r.data) } catch (_) { this.results.push(...chunk.map(s => ({ id: s.id, success: false, message: '提交结果未确认，请刷新后核对原出库任务' }))); this.results.push(...rows.slice(offset + 20).map(s => ({ id: s.id, success: false, message: '尚未提交，请刷新后重新选择' }))); break } } }
        this.$emit('changed');
      } catch (_) { this.preview = this.items.map(s => ({ id: s.id, success: false, message: '出库条件暂未确认，请刷新后重试' })); this.checked = true }
      finally { this.saving = false }
    }
  }
}
</script>
<style scoped>.summary{margin:18px 0}.hint{font-size:13px;color:#8490a2;margin:6px 0;line-height:1.6}.failure{color:#c27b12;overflow-wrap:anywhere}</style>
