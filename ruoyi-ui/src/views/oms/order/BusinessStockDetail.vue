<template>
  <div class="business-stock-detail">
    <div class="product-groups"><el-radio-group v-model="productGroup" size="small"><el-radio-button label="sale">原单履约</el-radio-button><el-radio-button label="after">换货换出（{{ (order.after_sales_lines || []).length }} 条）</el-radio-button><el-radio-button label="all">全部商品</el-radio-button></el-radio-group><span class="muted">平台子单关联交易，OMS 履约明细区分每次实际发货来源。</span></div>
    <div v-if="productGroup !== 'after'" class="sale-products">
    <div class="section-heading"><b>原单履约商品</b><span class="muted">仅统计原销售及未发货换品后的有效需求</span></div>
    <div class="inventory-status-summary"><span>逻辑预占状态 <el-tag size="small">{{ logicalStockLabel(order.logical_inventory_status || order.inventory_status) }}</el-tag></span><span>仓库库存状态 <order-warehouse-stock-status :order="order" /></span></div>
    <div class="inventory-issues-summary"><order-receipt-review-notice :order="order" compact @open="$emit('open-review', $event)" /><order-issue-badges v-if="(order.issues || []).length || (!Number(order.receipt_review_pending_count) && !order.receipt_review_lookup_failed)" :issues="order.issues || []" :pending="!order.issue_synced_at" @open="$emit('issues')" /><el-button v-hasPermi="['order:order:query']" type="text" @click="$emit('issues')">查看问题与处理记录</el-button><span v-if="order.issue_sync_error" class="muted">核对暂未更新，原问题保留</span></div>
    <div class="stock-summary"><order-stock-progress :order="order" /></div>
    <p v-if="order.status === 'VOIDED'" class="muted">原商品数量保留用于追溯，销售需求已取消，不再参与待逻辑预占计算。</p><p v-else class="muted">同订单同 SKU 汇总后足量逻辑预占；待逻辑预占数量是尚未逻辑预占的需求，不等同于库存缺口。</p>
    <el-descriptions :column="2" border size="small">
      <el-descriptions-item label="最近尝试">{{ order.stock_attempt_at || '—' }}</el-descriptions-item>
      <el-descriptions-item label="逻辑预占完成">{{ order.reserved_at || '—' }}</el-descriptions-item>
      <el-descriptions-item label="计划重试">{{ order.next_retry_at || '—' }}<div v-if="order.next_retry_at" class="muted">实际执行取决于定时任务调度</div></el-descriptions-item>
      <el-descriptions-item label="公共库存补占">{{ order.allow_public_stock ? '允许' : '不允许' }}<div class="muted">未完成的逻辑预占使用当前店铺设置</div></el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="order.shop_error && order.inventory_status !== 'RESERVED'" :title="'店铺任务提示：' + order.shop_error" type="warning" :closable="false" />
    <el-alert v-if="order.goods_error" :title="order.goods_error" type="warning" :closable="false" />
    <div v-if="terminatedLines.length" class="history-toggle"><span class="muted">已终止商品 {{ terminatedLines.length }} 条，保留原始明细用于追溯</span><el-switch v-model="showHistory" active-text="显示已替换 / 终止商品" /></div>
    <article v-for="b in order.bundles||[]" :key="b.id" class="kit-summary"><b>套装 {{ b.goods_name }} · {{ b.sku_sn }}</b><p>购买 {{ b.quantity }} 套 · 完整发出 {{ b.shipped_sets }} 套 · 组件已发 {{ b.shipped_component_quantity }} / {{ b.component_quantity }} 件 · 组成版本 {{ b.revision }}</p><p class="muted">平台子单 {{ b.platform_oid }} · 套装实付 ¥{{ b.payment }}。下方组件按实际SKU预占、分仓和出入库。</p><el-table :data="b.components" size="mini"><el-table-column prop="sku_sn" label="组件SKU"/><el-table-column prop="component_quantity" label="每套件数"/><el-table-column prop="quantity" label="组件需求"/><el-table-column prop="shipped_quantity" label="已发件数"/></el-table></article>
    <el-table :data="visibleLines" row-key="id" style="margin-top:16px">
      <el-table-column label="商品身份 / SKU / 替换来源" min-width="340"><template slot-scope="s"><order-line-identity :line="s.row" @open-shipment="$emit('open-review', $event)" /></template></el-table-column>
      <el-table-column label="预占来源 / 问题" min-width="230"><template slot-scope="s"><div class="source-breakdown">渠道锁库 {{ s.row.locked_reserved_quantity || 0 }} 件 + 公共补占 {{ s.row.public_reserved_quantity || 0 }} 件</div><order-issue-badges :issues="s.row.issues || []" empty-text="暂无库存问题" :pending="!order.issue_synced_at" @open="$emit('issues')" /><p v-if="s.row.issue_evidence && s.row.issue_evidence.excess_quantity" class="muted">当前渠道额度 {{ s.row.issue_evidence.channel_quota }} 件 · 超额度预占 {{ s.row.issue_evidence.excess_quantity }} 件</p><p v-if="s.row.stock_snapshot && s.row.stock_snapshot.observed_at" class="muted">预占时公司可用 {{ s.row.stock_snapshot.company_available }} 件 · 渠道额度 {{ s.row.stock_snapshot.channel_quota }} 件</p></template></el-table-column>
      <el-table-column label="平台子订单号" min-width="190"><template slot-scope="s">{{ s.row.platform_oid }}<div class="muted">OMS 履约明细 #{{ s.row.id }}</div></template></el-table-column>
      <el-table-column label="有效需求（件）" min-width="130" align="right"><template slot-scope="s"><b>{{ Math.max(0, Number(s.row.quantity) - Number(s.row.cancelled_quantity || 0)) }}</b><div v-if="s.row.cancelled_quantity" class="muted">原 {{ s.row.quantity }} · 终止 {{ s.row.cancelled_quantity }}</div></template></el-table-column>
      <el-table-column label="累计逻辑预占（件）" prop="reserved_quantity" width="125" align="right" /><el-table-column label="已出库 / 仍预占" min-width="150"><template slot-scope="s">{{ s.row.shipped_quantity || 0 }} / {{ s.row.logical_active_quantity === undefined ? s.row.reserved_quantity : s.row.logical_active_quantity }} 件</template></el-table-column>
      <el-table-column label="待逻辑预占（件）" width="110" align="right"><template slot-scope="s">{{ order.status === 'VOIDED' ? '—' : s.row.pending_quantity }}</template></el-table-column>
      <el-table-column label="逻辑预占结果" min-width="220"><template slot-scope="s"><el-tag size="small" :type="Number(s.row.quantity) <= Number(s.row.cancelled_quantity || 0) ? 'info' : s.row.pending_quantity ? 'warning' : 'success'">{{ order.status === 'VOIDED' ? '逻辑预占已释放' : Number(s.row.quantity) <= Number(s.row.cancelled_quantity || 0) ? '需求已终止' : s.row.pending_quantity ? '待逻辑预占' : '逻辑预占完成' }}</el-tag><p>{{ s.row.inventory_reason || '—' }}</p><el-button v-if="s.row.inventory_reason_code === 'PUBLIC_DISABLED'" v-hasPermi="['order:pull:config']" type="text" @click="$emit('settings')">查看转单设置</el-button></template></el-table-column>
    </el-table>
    <el-collapse class="stock-sources"><el-collapse-item title="查看已逻辑预占库存来源" name="sources">
      <p class="muted">库存来源用于追溯逻辑预占；已确定的发货仓、批次和占用数量见“分仓明细”。</p>
      <el-alert v-if="order.stock_sources_error" :title="order.stock_sources_error" type="warning" :closable="false" />
      <el-table v-else :data="sources" empty-text="暂无已逻辑预占库存来源"><el-table-column label="SKU" prop="sku_sn" /><el-table-column label="平台子订单" prop="platform_oid" min-width="200" /><el-table-column label="来源类型"><template slot-scope="s">{{ s.row.source_type === 'LOCK' ? '渠道锁库' : '公共库存补占' }}</template></el-table-column><el-table-column label="来源虚仓编码"><template slot-scope="s">{{ s.row.store_code || '公司公共库存' }}</template></el-table-column><el-table-column label="累计预占件数" prop="quantity" /><el-table-column label="已核销件数"><template slot-scope="s">{{ s.row.outbound_quantity || 0 }}</template></el-table-column></el-table>
    </el-collapse-item></el-collapse>
    </div>
    <order-after-sale-lines v-if="productGroup !== 'sale'" :lines="order.after_sales_lines || []" @open-shipment="$emit('open-review', $event)" @exchanges="$emit('exchanges')" />
  </div>
</template>
<script>
import OrderLineIdentity from './OrderLineIdentity'
import OrderAfterSaleLines from './OrderAfterSaleLines'
import OrderStockProgress from './OrderStockProgress'
import OrderWarehouseStockStatus from './OrderWarehouseStockStatus'
import OrderIssueBadges from './OrderIssueBadges'
import OrderReceiptReviewNotice from './OrderReceiptReviewNotice'
import { logicalStockLabel } from './stockLabels'

export default {
  components: { OrderLineIdentity, OrderAfterSaleLines, OrderReceiptReviewNotice, OrderIssueBadges, OrderStockProgress, OrderWarehouseStockStatus },
  props: { order: { type: Object, required: true } },
  data() { return { productGroup: 'sale', showHistory: false } },
  methods: { logicalStockLabel },
  computed: {
    terminatedLines() { return (this.order.lines || []).filter(l => Number(l.quantity) <= Number(l.cancelled_quantity || 0)) },
    visibleLines() { return this.showHistory ? this.order.lines || [] : (this.order.lines || []).filter(l => Number(l.quantity) > Number(l.cancelled_quantity || 0)) },
    sources() { return (this.order.lines || []).flatMap(line => (line.stock_sources || []).map(source => ({ ...source, sku_sn: line.sku_sn, platform_oid: line.platform_oid }))) } }
}
</script>
<style scoped>
.kit-summary{padding:18px;margin:16px 0;background:#f4f7fc;border:1px solid #dce7f7;border-radius:8px}.kit-summary p{line-height:1.8}
.product-groups{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin:18px 0}.section-heading{display:flex;align-items:center;gap:14px;margin:18px 0}.history-toggle{display:flex;gap:18px;align-items:center;flex-wrap:wrap;margin-top:16px}
.inventory-status-summary{display:flex;flex-wrap:wrap;gap:12px 24px;align-items:center;margin-bottom:16px;font-size:13px;color:#526176}
.inventory-issues-summary{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:12px 0}.source-breakdown{font-size:13px;color:#52647c;line-height:1.7;margin-bottom:8px}.stock-summary{padding:18px;background:#f5f8fc;border-radius:8px}.muted{color:#8490a2;font-size:13px;line-height:1.6}.matched{font-size:12px;color:#426e9a;margin-top:8px;line-height:1.6}.stock-sources{margin-top:20px}
</style>
