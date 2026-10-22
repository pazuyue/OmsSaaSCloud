<template>
  <div class="trial-diagnostics">
    <section v-for="g in diagnostics.groups || []" :key="g.key">
      <h4>{{ g.name }} <span class="group-need">本批需要 {{ g.required }} 件</span></h4>
      <el-table :data="g.candidates" size="small">
        <el-table-column label="商品 / SKU" min-width="200"><template slot-scope="s"><b>{{ s.row.goods_name || s.row.sku_sn }}</b><div>{{ s.row.sku_sn }}</div></template></el-table-column>
        <el-table-column label="本批需要" width="105"><template slot-scope="s">{{ s.row.required == null ? '按组分配' : `${s.row.required}件` }}</template></el-table-column>
        <el-table-column label="本仓可用" prop="available" width="95"/>
        <el-table-column label="缺口" width="105"><template slot-scope="s"><b v-if="s.row.shortage > 0" class="stock-shortage">缺{{ s.row.shortage }}件</b><span v-else>{{ s.row.required == null ? '见分组缺口' : s.row.mode === 'EXCLUDE' ? '不选' : '数量充足' }}</span></template></el-table-column>
        <el-table-column label="处理" width="100"><template slot-scope="s"><router-link target="_blank" :to="{path:'/oms-inventory/productInventory',query:{skuSn:s.row.sku_sn}}">查看库存</router-link></template></el-table-column>
      </el-table>
      <p v-if="g.flexible_required > 0" :class="{'stock-shortage':g.flexible_shortage > 0}">按规则分配的部分：需要{{ g.flexible_required }}件，候选合计可用{{ g.flexible_available }}件，{{ g.flexible_shortage ? `缺${g.flexible_shortage}件` : '数量充足' }}。</p>
    </section>
    <el-alert v-for="(m,i) in diagnostics.missing_cost || []" :key="`cost-${i}`" :title="`${m.group_name}${m.sku_sn ? ` · ${m.sku_sn}` : ''}：至少${m.missing_quantity}件必需组件缺少可用批次成本`" type="warning" :closable="false"/>
  </div>
</template>
<script>
export default { name: 'LuckyBagTrialDiagnostics', props: { diagnostics: { type: Object, default: () => ({}) } } }
</script>
<style scoped>
.trial-diagnostics section{margin:16px 0}.group-need{font-size:12px;font-weight:400;color:#66788d;margin-left:12px}.stock-shortage{color:#c0392b}.trial-diagnostics p{font-size:13px;line-height:1.8}
</style>
