<template>
  <section class="refund-bound-gifts">
    <div class="section-heading"><h3>本退款关联赠品 <span v-if="rows.length" class="hint">{{ rows.length }} 种</span></h3><span class="hint">单独跟踪，不计入申请数量与申请金额</span></div>
    <p v-if="!rows.length" class="empty-gifts">本退款暂无关联赠品</p>
    <el-table v-else :data="rows" row-key="id" size="small" ref="table">
      <el-table-column type="expand" width="38"><template slot-scope="s"><div class="gift-basis"><strong>原订单累计赠品判定</strong><p>原赠 {{ s.row.quantity }} {{ unit(s.row) }} · 当前可保留 {{ s.row.eligible_quantity }} {{ unit(s.row) }} · 累计待退回 {{ s.row.return_required || 0 }} {{ unit(s.row) }}</p><p class="hint">累计判定包含其他售后影响，不代表本退款新增应退；表格中的退回进度仅统计本退款已关联安排。</p><p v-if="s.row.pending_assessment" class="hint">售后尚未确认，预计可保留 {{ s.row.projected_eligible_quantity }} {{ unit(s.row) }}。</p><p v-if="s.row.waived" class="hint">本退款已确认不再追回 {{ s.row.waived }} {{ unit(s.row) }}，不代表实收或入账。</p><el-table :data="s.row.plans" size="mini"><el-table-column label="本退款退回安排" min-width="150"><template slot-scope="p">安排 #{{ p.row.id }}</template></el-table-column><el-table-column prop="quantity" label="计划退回"/><el-table-column prop="accepted_quantity" label="已确认实收"/><el-table-column prop="posted_quantity" label="已入账"/></el-table></div></template></el-table-column>
      <el-table-column label="赠品 / SKU" min-width="235"><template slot-scope="s"><strong>{{ s.row.goods_name || s.row.sku_sn }}</strong><div class="hint">{{ s.row.sku_sn }}</div><div v-if="s.row.gift_parent_sku&&s.row.gift_parent_sku!==s.row.sku_sn" class="hint">所属赠品：{{ s.row.gift_parent_name || s.row.gift_parent_sku }} · {{ s.row.gift_parent_quantity }}{{ s.row.gift_parent_unit }}</div></template></el-table-column>
      <el-table-column label="计划退回" width="100" align="right"><template slot-scope="s">{{ s.row.planned }} {{ unit(s.row) }}</template></el-table-column>
      <el-table-column label="已确认实收" min-width="125" align="right"><template slot-scope="s">{{ s.row.pending ? '待核对（已确认 ' + s.row.accepted + ' ' + unit(s.row) + '）' : s.row.accepted + ' ' + unit(s.row) }}</template></el-table-column>
      <el-table-column label="已入账" width="95" align="right"><template slot-scope="s">{{ s.row.posted }} {{ unit(s.row) }}</template></el-table-column>
      <el-table-column label="待退回" width="95" align="right"><template slot-scope="s">{{ s.row.remaining }} {{ unit(s.row) }}</template></el-table-column>
      <el-table-column label="进度" min-width="145"><template slot-scope="s"><span :class="{ warning: s.row.needs_action }">{{ s.row.state }}</span></template></el-table-column>
      <el-table-column label="操作" width="135" fixed="right"><template slot-scope="s"><el-button size="small" type="text" @click="$emit('open-goods', s.row)">查看退回进度</el-button><el-button size="small" type="text" @click="$refs.table.toggleRowExpansion(s.row)">判定依据</el-button></template></el-table-column>
    </el-table>
  </section>
</template>
<script>
import { refundGiftRows } from './refundDetailPresentation'
export default {
  props: { lines: { type: Array, default: () => [] }, refundId: [Number, String] },
  methods: { unit(line) { return line.stocked_bundle_snapshot ? '套' : '件' } },
  computed: { rows() { return refundGiftRows(this.lines, this.refundId) } }
}
</script>
<style scoped>
.refund-bound-gifts{margin:0 0 16px}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:12px}.section-heading h3{font-size:15px;color:#29394d;margin:0}.section-heading h3 span{margin-left:8px;font-weight:400}.hint{font-size:12px;line-height:1.7;color:#758397}.empty-gifts{font-size:13px;color:#758397;margin:0;padding:10px 0}.warning{color:#a56b14}.gift-basis{background:#f7f9fc;padding:12px 18px;font-size:13px;line-height:1.8}.gift-basis p{margin:8px 0}.el-button+.el-button{margin-left:8px}
</style>
