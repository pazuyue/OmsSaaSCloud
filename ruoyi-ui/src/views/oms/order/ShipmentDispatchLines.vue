<template>
  <div class="dispatch-lines">
    <p class="hint">{{ frozen ? '原下发商品快照' : '本次发货单商品' }} · {{ groups.length }} 种 SKU / {{ quantity }} 件。展开商品查看平台子单、虚拟仓及批次数量。</p>
    <el-table :data="groups" row-key="key" size="small" empty-text="商品明细暂未加载，请刷新校验" border>
      <el-table-column type="expand" width="42"><template slot-scope="s"><el-table :data="s.row.sources" size="mini" class="source-lines"><el-table-column label="平台子单号" prop="platform_oid" min-width="180" /><el-table-column label="虚拟仓" prop="store_code" min-width="100" /><el-table-column label="库存批次" min-width="140"><template slot-scope="l">{{ l.row.batch_code || (l.row.batch_id ? '批次 ID ' + l.row.batch_id : '—') }}</template></el-table-column><el-table-column label="本次下发数量" prop="quantity" width="115" align="right" /><el-table-column v-if="showActual" label="实际出库" prop="shipped_quantity" width="100" align="right" /></el-table></template></el-table-column>
      <el-table-column label="商品 / SKU" min-width="240"><template slot-scope="s"><strong>{{ s.row.goods_name || '商品名称未提供' }}</strong><div class="sku">{{ s.row.sku_sn }}<el-button v-clipboard:copy="s.row.sku_sn" v-clipboard:success="copied" v-clipboard:error="copyFailed" type="text" icon="el-icon-document-copy" :aria-label="'复制 SKU ' + s.row.sku_sn" /></div></template></el-table-column>
      <el-table-column label="规格" min-width="150"><template slot-scope="s">{{ s.row.spec || '—' }}</template></el-table-column><el-table-column label="来源明细" width="105"><template slot-scope="s">{{ s.row.sources.length }} 行</template></el-table-column><el-table-column label="本次下发数量" width="125" align="right"><template slot-scope="s"><strong class="quantity">{{ s.row.quantity }} 件</strong></template></el-table-column>
      <el-table-column v-if="showActual" label="实际出库 / 剩余" width="145" align="right"><template slot-scope="s">{{ s.row.shipped_quantity }} / {{ Math.max(0, s.row.quantity - s.row.shipped_quantity) }} 件</template></el-table-column>
    </el-table>
  </div>
</template>
<script>
export default {
  props: { lines: { type: Array, default: () => [] }, frozen: Boolean, showActual: Boolean },
  computed: {
    quantity() { return this.lines.reduce((sum, line) => sum + Number(line.quantity || 0), 0) },
    groups() { const groups = new Map(); this.lines.forEach(line => { const key = String(line.sku_sn || ''); if (!groups.has(key)) groups.set(key, { key, goods_name: line.goods_name, sku_sn: line.sku_sn, spec: line.spec, quantity: 0, shipped_quantity: 0, sources: [] }); const group = groups.get(key); group.quantity += Number(line.quantity || 0); group.shipped_quantity += Number(line.shipped_quantity || 0); group.sources.push(line); group.spec = [...new Set(group.sources.map(source => source.spec).filter(Boolean))].join(' / ') }); return [...groups.values()] }
  },
  methods: { copied() { this.$message.success('SKU 已复制') }, copyFailed() { this.$message.error('复制失败，请手动复制 SKU') } }
}
</script>
<style scoped>.hint{color:#68778d;font-size:13px;line-height:1.6;margin:0 0 12px}.sku{color:#65748a;overflow-wrap:anywhere}.sku .el-button{padding:3px 8px}.quantity{color:#1765ad}.source-lines{width:100%;padding:0}.dispatch-lines /deep/ .el-table__expanded-cell{padding:14px 8px}.dispatch-lines /deep/ .el-table th{background:#f5f7fa}</style>
