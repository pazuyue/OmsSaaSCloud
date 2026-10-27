<template>
  <div>
    <el-alert :title="processing ? '以下为已确认仓内加工结果对应的库存流水，投入与产出按同一消息成组记账。' : outbound ? '逐次展示已确认的实际出库数量与批次，每条记录对应一次库存扣账。' : real ? '以下为仓库回传的实际收货增量，每条记录独立入账。' : '以下为按到货计划自动生成的虚拟收货记录。'" type="info" :closable="false" />
    <el-alert v-if="error" :title="outbound ? '出库记录加载失败' : '收货批次加载失败'" type="error" :closable="false"><el-button type="text" @click="load">重试</el-button></el-alert>
    <el-table v-loading="loading" :data="rows" :empty-text="outbound ? '尚无实际出库记录' : '尚无实际收货记录'">
      <el-table-column label="商品 / SKU" min-width="170"><template slot-scope="s">{{ s.row.goodsName }}<div class="muted">{{ s.row.skuSn }}</div></template></el-table-column>
      <el-table-column label="实际批次" prop="batchCode" min-width="150" show-overflow-tooltip />
      <el-table-column label="正品 / 次品" width="110"><template slot-scope="s">{{ s.row.goodQty }} / {{ s.row.badQty }}</template></el-table-column>
      <el-table-column :label="outbound ? '扣账结果' : '入账结果'" min-width="190"><template slot-scope="s"><el-tag :type="s.row.posted === 2 ? 'danger' : s.row.posted === 1 ? 'success' : 'warning'" size="small">{{ outbound ? ['待扣账', '扣账成功', '扣账失败'][s.row.posted] : ['待入账', '入账成功', '入账失败'][s.row.posted] }}</el-tag><div class="error">{{ s.row.errorInfo }}</div></template></el-table-column>
      <el-table-column :label="outbound ? '出库请求' : '收货消息'" prop="messageId" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="!outbound && !processing" label="最终确认" width="90"><template slot-scope="s">{{ s.row.finalReceipt ? '是' : '否' }}</template></el-table-column>
<el-table-column v-if="outbound" label="执行方式 / 操作人" min-width="160"><template slot-scope="s">{{ s.row.sourceMode === 'PROCESS' ? '仓内加工' : s.row.sourceMode === 'WMS' ? 'WMS 出库' : '虚拟出库' }}<div class="muted">{{ s.row.operatorName }}</div></template></el-table-column>
      <el-table-column v-if="!outbound" label="成本 / 入账关联号" min-width="210"><template slot-scope="s"><div v-if="s.row.purchasePrice!=null">原采购单价 ¥{{ s.row.purchasePrice }}</div><div class="muted">{{ s.row.inventoryRelation || '查看原执行单' }}</div></template></el-table-column>
      <el-table-column v-if="!outbound" label="原回传" width="100"><template slot-scope="s"><el-button v-if="s.row.sourceLogId" type="text" @click="$emit('interaction',s.row.sourceLogId)">查看回传</el-button></template></el-table-column>
      <el-table-column :label="outbound ? '出库时间' : '接收时间'" min-width="160"><template slot-scope="s">{{ parseTime(s.row.createTime) }}</template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="page" :limit.sync="size" @pagination="load" />
  </div>
</template>
<script>
import { receiptBatches } from '@/api/purchase/workspace'
export default {
  props: { ticketId: { type: Number, required: true }, real: Boolean, outbound: Boolean, processing: Boolean },
  data() { return { rows: [], total: 0, page: 1, size: 20, loading: false, error: false, sequence: 0 } },
  watch: { ticketId: { immediate: true, handler() { this.page = 1; this.load() } } },
  methods: { async load() { const seq = ++this.sequence; this.loading = true; this.error = false; try { const r = await receiptBatches(this.ticketId, { pageNum: this.page, pageSize: this.size }); if (seq === this.sequence) { this.rows = r.rows; this.total = r.total } } catch (_) { if (seq === this.sequence) { this.rows = []; this.error = true } } finally { if (seq === this.sequence) this.loading = false } } }
}
</script>
<style scoped>.muted{color:#7c8798;font-size:12px}.error{color:#d84b4b;font-size:12px;margin-top:6px}</style>
