<template>
  <div>
    <div class="inventory-status-summary"><span>逻辑预占状态 <el-tag size="small">{{ logicalStockLabel(order.logical_inventory_status || order.inventory_status) }}</el-tag></span><span>仓库库存状态 <order-warehouse-stock-status :order="order" /></span></div>
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
    <el-table :data="order.lines" style="margin-top:16px">
      <el-table-column label="OMS 商品（当前） / SKU" min-width="230"><template slot-scope="s"><b>{{ s.row.sku_sn }}</b><div>{{ s.row.oms_goods_name || '当前商品资料不可用' }}</div><div class="muted">平台商品：{{ s.row.goods_name || '—' }}</div><div class="muted">平台规格：{{ s.row.platform_spec || '—' }}</div><div v-if="s.row.manual_sku_sn" class="matched">人工匹配 · {{ s.row.manual_match_by }} · {{ s.row.manual_match_at }}<br>原商家编码：{{ s.row.outer_sku_id || '缺失' }}<br>{{ s.row.manual_match_reason }}</div></template></el-table-column>
      <el-table-column label="平台子订单" prop="platform_oid" min-width="190" />
      <el-table-column :label="order.status === 'VOIDED' ? '原需求（件）' : '需求（件）'" prop="quantity" width="110" align="right" />
      <el-table-column label="累计逻辑预占（件）" prop="reserved_quantity" width="125" align="right" /><el-table-column label="已出库 / 仍预占" min-width="150"><template slot-scope="s">{{ s.row.shipped_quantity || 0 }} / {{ s.row.logical_active_quantity === undefined ? s.row.reserved_quantity : s.row.logical_active_quantity }} 件</template></el-table-column>
      <el-table-column label="待逻辑预占（件）" width="110" align="right"><template slot-scope="s">{{ order.status === 'VOIDED' ? '—' : s.row.pending_quantity }}</template></el-table-column>
      <el-table-column label="逻辑预占结果" min-width="220"><template slot-scope="s"><el-tag size="small" :type="s.row.pending_quantity ? 'warning' : 'success'">{{ order.status === 'VOIDED' ? '逻辑预占已释放' : s.row.pending_quantity ? '待逻辑预占' : '逻辑预占完成' }}</el-tag><p>{{ s.row.inventory_reason || '—' }}</p><el-button v-if="s.row.inventory_reason_code === 'PUBLIC_DISABLED'" v-hasPermi="['order:pull:config']" type="text" @click="$emit('settings')">查看转单设置</el-button></template></el-table-column>
    </el-table>
    <el-collapse class="stock-sources"><el-collapse-item title="查看已逻辑预占库存来源" name="sources">
      <p class="muted">库存来源用于追溯逻辑预占；已确定的发货仓、批次和占用数量见“分仓明细”。</p>
      <el-alert v-if="order.stock_sources_error" :title="order.stock_sources_error" type="warning" :closable="false" />
      <el-table v-else :data="sources" empty-text="暂无已逻辑预占库存来源"><el-table-column label="SKU" prop="sku_sn" /><el-table-column label="平台子订单" prop="platform_oid" min-width="200" /><el-table-column label="来源类型"><template slot-scope="s">{{ s.row.source_type === 'LOCK' ? '渠道锁库' : '公共库存补占' }}</template></el-table-column><el-table-column label="来源虚仓编码"><template slot-scope="s">{{ s.row.store_code || '公司公共库存' }}</template></el-table-column><el-table-column label="累计预占件数" prop="quantity" /><el-table-column label="已核销件数"><template slot-scope="s">{{ s.row.outbound_quantity || 0 }}</template></el-table-column></el-table>
    </el-collapse-item></el-collapse>
  </div>
</template>
<script>
import OrderStockProgress from './OrderStockProgress'
import OrderWarehouseStockStatus from './OrderWarehouseStockStatus'
import { logicalStockLabel } from './stockLabels'

export default {
  components: { OrderStockProgress, OrderWarehouseStockStatus },
  props: { order: { type: Object, required: true } },
  methods: { logicalStockLabel },
  computed: { sources() { return (this.order.lines || []).flatMap(line => (line.stock_sources || []).map(source => ({ ...source, sku_sn: line.sku_sn, platform_oid: line.platform_oid }))) } }
}
</script>
<style scoped>
.inventory-status-summary{display:flex;flex-wrap:wrap;gap:12px 24px;align-items:center;margin-bottom:16px;font-size:13px;color:#526176}
.stock-summary{padding:18px;background:#f5f8fc;border-radius:8px}.muted{color:#8490a2;font-size:13px;line-height:1.6}.matched{font-size:12px;color:#426e9a;margin-top:8px;line-height:1.6}.stock-sources{margin-top:20px}
</style>
