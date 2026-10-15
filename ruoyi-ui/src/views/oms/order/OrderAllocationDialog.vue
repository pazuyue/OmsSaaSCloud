<template>
  <el-dialog title="订单分仓预览" :visible.sync="visible" width="min(1200px, 96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-alert title="预览不占用库存、不生成发货单。确认时重新校验；规则或分配方案变化时需重新预览。实际包裹由仓库回传确认。" type="info" :closable="false" show-icon />
    <div class="preview-toolbar"><span>{{ completed ? '本次分仓结果' : '可确认 '+eligible.length+' / '+orders.length+' 单' }}</span><el-button v-if="!completed" size="small" :loading="previewing" :disabled="saving" @click="preview">重新预览</el-button></div>
    <el-table ref="planTable" :data="results" v-loading="previewing" row-key="id" :expand-row-keys="expanded" max-height="620">
      <el-table-column type="expand"><template slot-scope="s"><div v-if="s.row.policy" class="plan-detail">
        <div class="policy"><strong>{{ s.row.policy.strategy === 'REGION_FIRST' ? '优先省份配置仓' : '优先整单' }}</strong><span>{{ s.row.using_snapshot ? '沿用首次分仓规则' : '店铺当前规则' }} v{{ s.row.policy.rule_revision || '—' }}</span><span>省份：{{ s.row.policy.receiver_province || '未识别，使用默认顺序' }}</span></div>
        <p v-if="s.row.policy.strategy === 'REGION_FIRST'" class="muted">先按收货省份匹配优先仓，不足时按默认仓顺序补足；仍受多仓、同 SKU 拆分和数量上限限制。</p>
        <p class="muted">候选顺序：{{ (s.row.policy.resolved_order || []).join(' → ') }} · 最多 {{ s.row.policy.max_virtual_warehouses || '不限' }} 个虚拟仓 / {{ s.row.policy.max_shipments || '不限' }} 张累计发货单 · 首次最低满足 {{ s.row.policy.min_first_allocation_percent || 0 }}%</p>
        <div class="metrics"><span>原单需求 <b>{{ s.row.required_quantity || 0 }}</b> 件</span><span>已有分仓 <b>{{ s.row.allocated_quantity || 0 }}</b> 件</span><span>本次新增占用 <b>{{ s.row.planned_quantity || 0 }}</b> 件</span><span>剩余待分仓 <b>{{ s.row.remaining_quantity || 0 }}</b> 件</span><span>预计新增 <b>{{ s.row.new_shipment_count || 0 }}</b> 张发货单</span><span>累计 <b>{{ s.row.total_shipment_count || 0 }}</b> 张</span></div>
        <el-alert v-if="s.row.recovered_quantity" :title="'已有 '+s.row.recovered_quantity+' 件库存回执待补建发货单，确认时核对并恢复，不重复占用。'" type="warning" :closable="false" />
        <p v-if="s.row.logical_pending_quantity" class="warning">其中 {{ s.row.logical_pending_quantity }} 件尚未完成逻辑预占，本次不会分配这部分需求。</p>
        <h4>本次新增分配明细</h4><el-table :data="(s.row.lines || []).slice((s.row.linePage-1)*20, s.row.linePage*20)" size="mini" empty-text="本次无新增库存占用">
          <el-table-column label="商品 / SKU" min-width="180"><template slot-scope="l">{{ l.row.goods_name || l.row.sku_sn }}<div class="muted">{{ l.row.sku_sn }}</div></template></el-table-column><el-table-column label="平台子单" prop="platform_oid" min-width="190"/><el-table-column label="虚拟仓 / 实体仓" min-width="180"><template slot-scope="l">{{ l.row.store_code }}<div class="muted">{{ l.row.real_store_name || l.row.real_store_code }}</div></template></el-table-column><el-table-column label="批次" prop="batch_id" width="90"/><el-table-column label="数量" prop="quantity" width="80"/><el-table-column label="来源" width="160"><template slot-scope="l">{{ l.row.source_type === 'LOCK' ? '继承渠道锁库' : '公共库存转占用' }}</template></el-table-column>
        </el-table>
        <el-pagination v-if="(s.row.lines || []).length>20" :current-page.sync="s.row.linePage" :page-size="20" :total="s.row.lines.length" layout="prev, pager, next" small />
        <h4>候选仓与选择原因</h4><el-table :data="s.row.candidates || []" size="mini" max-height="300">
          <el-table-column type="expand"><template slot-scope="c"><el-table :data="c.row.goods" size="mini"><el-table-column label="SKU" prop="sku_sn"/><el-table-column label="待分仓逻辑预占" prop="pending_quantity"/><el-table-column label="批次公共可用" prop="public_available"/><el-table-column label="本单可继承锁库" prop="inherited_locked"/><el-table-column label="来源限制" prop="restriction" min-width="200"/></el-table><p v-if="c.row.goods_truncated" class="muted">此处展示前 20 个 SKU 的库存摘要。</p></template></el-table-column>
          <el-table-column prop="rank" label="顺序" width="70"/><el-table-column label="虚拟仓 / 实体仓" min-width="230"><template slot-scope="c">{{ c.row.store_code }}<div class="muted">{{ c.row.real_store_name || c.row.real_store_code }}</div></template></el-table-column><el-table-column prop="selected_quantity" label="本次分配" width="100"/><el-table-column prop="reason" label="选择 / 未入选原因" min-width="300"/>
        </el-table>
      </div><p v-else class="muted">{{ s.row.message || '正在预览' }}</p></template></el-table-column>
      <el-table-column label="OMS / 平台订单" min-width="280"><template slot-scope="s"><order-identity :order="s.row" /></template></el-table-column>
      <el-table-column label="本次计划" min-width="200"><template slot-scope="s"><span v-if="s.row.policy">新增占用 {{ s.row.planned_quantity || 0 }} 件 · {{ s.row.new_shipment_count || 0 }} 张发货单</span><span v-else>—</span></template></el-table-column>
      <el-table-column label="结果 / 处理建议" min-width="330"><template slot-scope="s"><el-tag v-if="completed" size="mini" :type="s.row.success ? 'success' : 'warning'">{{ s.row.success ? '已处理' : '未分仓' }}</el-tag><el-tag v-else size="mini" :type="s.row.can_allocate ? 'success' : 'warning'">{{ s.row.can_allocate ? '可确认' : '暂不可确认' }}</el-tag><div class="message">{{ s.row.message || '正在预览' }}</div></template></el-table-column>
    </el-table>
    <span slot="footer"><el-button :disabled="saving" @click="close">{{ completed ? '关闭' : '取消' }}</el-button><el-button v-if="!completed" type="primary" :disabled="previewing || !eligible.length" :loading="saving" @click="submit">确认分仓（{{ eligible.length }} 单）</el-button></span>
  </el-dialog>
</template>
<script>
import OrderIdentity from './OrderIdentity'
import { allocateOrders, previewOrderAllocation } from '@/api/order'
export default {
  components: { OrderIdentity },
  data() { return { visible: false, saving: false, previewing: false, completed: false, orders: [], results: [], expanded: [], requestId: 0 } },
  computed: { eligible() { return this.results.filter(row => row.can_allocate) } },
  methods: {
    open(rows) { if (!rows.length || rows.length > 20) return this.$message.warning('分仓每次请选择1至20张订单'); this.requestId++; this.previewing = false; this.orders = rows.map(r => ({ ...r })); this.completed = false; this.results = []; this.expanded = rows.length === 1 ? [rows[0].id] : []; this.visible = true; this.preview() },
    close() { this.visible = false; this.requestId++ },
    async preview() { if (this.saving || this.previewing) return; const id = ++this.requestId; this.previewing = true; try { const result = (await previewOrderAllocation({ items: this.orders.map(r => ({ id: r.id, control_version: r.control_version })) })).data; if (id === this.requestId) { this.results = result.map(r => ({ ...this.orders.find(o => o.id === r.id), ...r, linePage: 1 })); await this.$nextTick(); if (this.results.length === 1 && this.$refs.planTable) this.$refs.planTable.toggleRowExpansion(this.results[0], true) } } finally { if (id === this.requestId) this.previewing = false } },
    async submit() { if (this.saving || this.previewing || !this.eligible.length) return; this.saving = true; try { const result = (await allocateOrders({ items: this.eligible.map(r => ({ id: r.id, control_version: r.control_version, policy_fingerprint: r.policy_fingerprint, plan_fingerprint: r.plan_fingerprint })) })).data; const byId = new Map(result.map(r => [r.id,r])); this.results = this.results.map(r => byId.has(r.id) ? { ...r, ...byId.get(r.id) } : { ...r, success: false, message: '本次未提交：'+r.message }); this.completed = true; this.$emit('changed') } finally { this.saving = false } }
  }
}
</script>
<style scoped>
.preview-toolbar,.policy,.metrics{display:flex;align-items:center;gap:12px 24px;flex-wrap:wrap}.preview-toolbar{justify-content:space-between;margin:16px 0}.plan-detail{padding:12px 18px;background:#f8fafc}.policy{font-size:13px}.policy strong{font-size:15px}.muted{color:#8490a2;font-size:12px;line-height:1.7}.metrics{padding:14px 0;font-size:13px}.metrics b{color:#263c55}h4{font-size:14px;margin:22px 0 10px}.warning{color:#a46a10}.message{font-size:13px;line-height:1.7;margin-top:5px}
</style>
