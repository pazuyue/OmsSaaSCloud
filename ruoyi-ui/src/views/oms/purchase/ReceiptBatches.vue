<template>
  <div>
    <el-alert :title="real ? '以下为仓库回传的实际收货增量，每条记录独立入账。' : '以下为按到货计划自动生成的虚拟收货记录。'" type="info" :closable="false" />
    <el-alert v-if="error" title="收货批次加载失败" type="error" :closable="false"><el-button type="text" @click="load">重试</el-button></el-alert>
    <el-table v-loading="loading" :data="rows" empty-text="尚无实际收货记录">
      <el-table-column label="商品 / SKU" min-width="170"><template slot-scope="s">{{ s.row.goodsName }}<div class="muted">{{ s.row.skuSn }}</div></template></el-table-column>
      <el-table-column label="实际批次" prop="batchCode" min-width="150" show-overflow-tooltip />
      <el-table-column label="正品 / 次品" width="110"><template slot-scope="s">{{ s.row.goodQty }} / {{ s.row.badQty }}</template></el-table-column>
      <el-table-column label="入账结果" min-width="190"><template slot-scope="s"><el-tag :type="s.row.posted === 2 ? 'danger' : s.row.posted === 1 ? 'success' : 'warning'" size="small">{{ ['待入账', '入账成功', '入账失败'][s.row.posted] }}</el-tag><div class="error">{{ s.row.errorInfo }}</div></template></el-table-column>
      <el-table-column label="收货消息" prop="messageId" min-width="150" show-overflow-tooltip />
      <el-table-column label="最终确认" width="90"><template slot-scope="s">{{ s.row.finalReceipt ? '是' : '否' }}</template></el-table-column>
      <el-table-column label="接收时间" min-width="160"><template slot-scope="s">{{ parseTime(s.row.createTime) }}</template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="page" :limit.sync="size" @pagination="load" />
  </div>
</template>
<script>
import { receiptBatches } from '@/api/purchase/workspace'
export default {
  props: { ticketId: { type: Number, required: true }, real: Boolean },
  data() { return { rows: [], total: 0, page: 1, size: 20, loading: false, error: false, sequence: 0 } },
  watch: { ticketId: { immediate: true, handler() { this.page = 1; this.load() } } },
  methods: { async load() { const seq = ++this.sequence; this.loading = true; this.error = false; try { const r = await receiptBatches(this.ticketId, { pageNum: this.page, pageSize: this.size }); if (seq === this.sequence) { this.rows = r.rows; this.total = r.total } } catch (_) { if (seq === this.sequence) { this.rows = []; this.error = true } } finally { if (seq === this.sequence) this.loading = false } } }
}
</script>
<style scoped>.muted{color:#7c8798;font-size:12px}.error{color:#d84b4b;font-size:12px;margin-top:6px}</style>
