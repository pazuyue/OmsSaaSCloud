<template>
  <section class="sales-composition" :class="{'sales-bundles':row.kind === 'LUCKY_BAG'}">
    <template v-if="row.kind === 'LUCKY_BAG'">
      <div class="composition-heading"><b>每袋实际组成</b><span class="muted">完整发出 {{ row.shipped }} 袋 · 组成确定后按原袋履约</span></div>
      <el-table :data="visibleUnits" size="small" row-key="unit_id" empty-text="尚未形成逐袋组成">
        <el-table-column label="袋序号" width="125"><template slot-scope="s"><b>第{{ s.row.unit }}袋</b><div class="muted">原袋号 #{{ s.row.unit_id }}<el-button v-clipboard:copy="String(s.row.unit_id)" v-clipboard:success="copied" type="text" icon="el-icon-document-copy" size="mini" aria-label="复制原袋号"/></div></template></el-table-column>
        <el-table-column label="实际商品 / 规格" min-width="285"><template slot-scope="s"><div v-for="(c,index) in s.row.components" :key="`${c.stock_source_id}_${c.sku_sn}_${index}`" class="bag-component"><div><b>{{ c.goods_name || c.sku_sn }}</b><span class="part-quantity">× {{ c.quantity }} 件</span></div><div class="muted">{{ c.sku_sn }}<span v-if="spec(c.sku_sn)"> · {{ spec(c.sku_sn) }}</span></div></div></template></el-table-column>
        <el-table-column label="发货仓库" min-width="170"><template slot-scope="s">{{ warehouse(s.row.store_code) }}<div v-if="warehouse(s.row.store_code) !== s.row.store_code" class="muted">{{ s.row.store_code }}</div></template></el-table-column>
        <el-table-column label="发货进度" width="125"><template slot-scope="s"><el-tag :type="s.row.shipped_complete ? 'success' : 'info'" size="small">{{ s.row.shipped_complete ? '整袋已发' : row.progress === '已作废' || row.progress === '已终止' ? '已终止' : '尚未完整发出' }}</el-tag></template></el-table-column>
        <el-table-column label="售后关联" width="125"><template slot-scope="s"><router-link v-if="s.row.return_refund_id" :to="{path:'/oms-order/businessRefunds',query:{id:s.row.return_refund_id}}" target="_blank">查看原袋退回</router-link><span v-else class="muted">—</span></template></el-table-column>
      </el-table>
      <el-button v-if="row.units.length > 5" type="text" @click="showAll = !showAll">{{ showAll ? '收起' : `展开剩余${row.units.length - 5}袋` }}</el-button>
    </template>
    <template v-else-if="row.kind === 'VIRTUAL_BUNDLE'">
      <div class="composition-heading"><b>固定组合组件</b><span class="muted">购买 {{ row.quantity }} 套 · 完整发出 {{ row.shipped }} 套 · 组件按件发货</span></div>
      <el-table :data="row.active_lines" row-key="id" size="small"><el-table-column label="实际组件 / SKU" min-width="260"><template slot-scope="s"><b>{{ s.row.goods_name }}</b><div class="muted">{{ s.row.sku_sn }} · {{ spec(s.row.sku_sn) }}</div><div v-if="s.row.exchange_id" class="muted">换品履约，原来源见仓库发货明细</div></template></el-table-column><el-table-column label="每套用量" width="110"><template slot-scope="s">{{ s.row.component_quantity ? `${s.row.component_quantity}件` : '换品履约' }}</template></el-table-column><el-table-column label="本单有效需求" width="115"><template slot-scope="s">{{ active(s.row) }} 件</template></el-table-column><el-table-column label="已出库" width="100"><template slot-scope="s">{{ s.row.shipped_quantity || 0 }} 件</template></el-table-column><el-table-column label="待发" width="100"><template slot-scope="s">{{ order.status === 'VOIDED' ? 0 : Math.max(0, active(s.row) - Number(s.row.shipped_quantity || 0)) }} 件</template></el-table-column></el-table>
    </template>
    <template v-else>
      <div class="composition-heading"><b>{{ row.kind === 'STOCKED_BUNDLE' ? '成品履约' : '实际履约商品' }}</b><span class="muted">{{ row.kind === 'STOCKED_BUNDLE' ? '订单按成品发货，配方用于查看组装资料。' : '按独立履约明细查看原购及换品后的实际商品。' }}</span></div>
      <order-product-lines :lines="row.active_lines" :order="order" hide-platform-oid :voided="order.status === 'VOIDED'" :bundles="order.bundles || []" @open-shipment="$emit('open-shipment', $event)"/>
      <el-collapse v-if="row.kind === 'STOCKED_BUNDLE'" class="recipe"><el-collapse-item title="商品配方（组装资料）" name="recipe"><el-alert v-if="row.recipe.invalid" title="配方资料异常，请核对商品组成。" type="error" :closable="false"/><el-table v-else :data="row.recipe.components" size="small"><el-table-column label="配方组件" prop="goods_name"/><el-table-column label="SKU" prop="sku_sn"/><el-table-column label="每套用量" prop="quantity"/></el-table></el-collapse-item></el-collapse>
    </template>
  </section>
</template>
<script>
import OrderProductLines from './OrderProductLines'
import { warehouseName, productSpec, activeQuantity } from './orderSalesPresentation'
export default {
  name: 'OrderSalesBundles', components: { OrderProductLines }, props: { row: { type: Object, required: true }, order: { type: Object, required: true } }, data: () => ({ showAll: false }),
  computed: { visibleUnits() { return this.showAll ? this.row.units : this.row.units.slice(0, 5) } },
  methods: { active: activeQuantity, warehouse(code) { return warehouseName(this.order, code) }, spec(sku) { const line = this.row.lines.find(l => l.sku_sn === sku); return line ? productSpec(line) : '' }, copied() { this.$message.success('原袋号已复制') } }
}
</script>
<style scoped>
.composition-heading{display:flex;align-items:center;flex-wrap:wrap;gap:10px 18px;margin-bottom:12px}.bag-component+.bag-component{margin-top:10px}.part-quantity{font-size:12px;margin-left:10px;white-space:nowrap;color:#52647c}.muted{font-size:12px;color:#7a8798;line-height:1.7;overflow-wrap:anywhere}.recipe{margin-top:14px}.sales-composition{overflow-wrap:anywhere}.sales-composition .el-button--text{padding:3px 0}
</style>
