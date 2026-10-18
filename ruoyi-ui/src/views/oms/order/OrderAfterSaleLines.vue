<template>
  <section class="after-sale-lines">
    <div class="section-heading"><b>换货换出商品</b><span> {{ lines.length }} 条履约明细 · 计划 {{ planned }} 件 · 已出库 {{ shipped }} 件</span></div>
    <p class="muted">归属各自 HH 换货单，分别展示每一次实际换出；不计入原订单购买数量、销售分仓与发货统计。</p>
    <el-table v-if="lines.length" :data="lines" row-key="id">
      <el-table-column label="换货后发出的商品 / 来源" min-width="340"><template slot-scope="s"><order-line-identity :line="s.row" @open-shipment="$emit('open-shipment', $event)" /></template></el-table-column>
      <el-table-column label="原平台子订单号" min-width="185"><template slot-scope="s">{{ s.row.platform_oid }}<div class="muted">用于关联原交易</div></template></el-table-column>
      <el-table-column label="计划换出 / 已出库" width="150"><template slot-scope="s"><b>{{ Math.max(0, Number(s.row.quantity) - Number(s.row.cancelled_quantity || 0)) }}</b> / {{ s.row.shipped_quantity || 0 }} 件</template></el-table-column>
      <el-table-column label="换出发货单" min-width="265"><template slot-scope="s"><div v-for="ship in s.row.shipments || []" :key="ship.shipment_line_id"><el-button type="text" @click="$emit('open-shipment', ship.shipment_id)">{{ ship.shipment_sn }}</el-button><div class="muted">发货明细 #{{ ship.shipment_line_id }} · 已出库 {{ ship.shipped_quantity }} / {{ ship.quantity }} 件</div></div><span v-if="!(s.row.shipments || []).length" class="muted">待换货单分仓生成</span></template></el-table-column>
    </el-table>
    <el-empty v-else description="尚无换货换出履约明细" :image-size="70"><el-button type="text" @click="$emit('exchanges')">查看换货申请</el-button></el-empty>
  </section>
</template>
<script>
import OrderLineIdentity from './OrderLineIdentity'
export default {
  components: { OrderLineIdentity },
  props: { lines: { type: Array, default: () => [] } },
  computed: { planned() { return this.lines.reduce((n, l) => n + Math.max(0, Number(l.quantity) - Number(l.cancelled_quantity || 0)), 0) }, shipped() { return this.lines.reduce((n, l) => n + Number(l.shipped_quantity || 0), 0) } }
}
</script>
<style scoped>
.section-heading{color:#34465e;line-height:1.7}.section-heading span{font-size:13px;color:#687b91;margin-left:8px}.muted{color:#8490a2;font-size:12px;line-height:1.7;word-break:break-word}.after-sale-lines{margin-top:16px}
</style>
