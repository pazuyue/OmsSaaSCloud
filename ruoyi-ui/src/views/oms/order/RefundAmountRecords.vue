<template>
  <el-dialog title="关联退款金额明细" :visible.sync="visible" width="min(960px, 94vw)" append-to-body :close-on-click-modal="false" @closed="invalidate">
    <p class="records-scope">平台子订单 {{ refund.platform_oid }} · 以下列出其他单已退款及进行中的额度占用，不重复包含当前退款。</p>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" size="small" empty-text="没有其他已退款或进行中占用" class="refund-amount-records">
      <el-table-column label="关联退款" min-width="285"><template slot-scope="s"><router-link v-if="s.row.business_refund_id" :to="{path:'/oms-order/businessRefunds',query:{id:s.row.business_refund_id}}" target="_blank">{{ s.row.refund_sn }}</router-link><span v-else>平台退款 {{ s.row.refund_id }}</span><div class="records-hint">平台退款号 {{ s.row.refund_id }}</div></template></el-table-column>
      <el-table-column label="退款用途" min-width="110"><template slot-scope="s">{{ label(s.row.purpose || 'UNKNOWN') }}</template></el-table-column>
      <el-table-column label="确认已退" min-width="110"><template slot-scope="s">{{ money(s.row.confirmed_amount) }}</template></el-table-column>
      <el-table-column label="进行中占用" min-width="110"><template slot-scope="s">{{ money(s.row.occupied_amount) }}</template></el-table-column>
      <el-table-column label="平台状态" min-width="160"><template slot-scope="s">{{ s.row.platform_status==='SUCCESS'?'退款成功':label(s.row.platform_status) }}<div v-if="s.row.source_state!=='VALID'" class="records-warning">来源待核对</div></template></el-table-column>
    </el-table>
    <div class="records-pagination"><el-button size="small" :disabled="loading || page<=1" @click="load(page-1)">上一页</el-button><span>第 {{ page }} 页</span><el-button size="small" :disabled="loading || !more" @click="load(page+1)">下一页</el-button></div>
    <span slot="footer"><el-button v-if="error" size="small" :loading="loading" @click="load(page)">重试读取</el-button><el-button size="small" @click="visible=false">返回当前退款</el-button></span>
  </el-dialog>
</template>
<script>
import { refundAmountRecords } from '@/api/order/refund'
import { money } from './orderAmounts'
import { label } from './refundLabels'
export default {
  name: 'RefundAmountRecords',
  props: { refund: { type: Object, required: true } },
  data: () => ({ visible: false, loading: false, error: '', rows: [], page: 1, more: false, sequence: 0 }),
  watch: { 'refund.id'() { this.visible = false; this.invalidate() } },
  beforeDestroy() { this.sequence++ },
  methods: {
    money, label,
    invalidate() { this.sequence++; this.rows = []; this.error = ''; this.loading = false; this.more = false },
    open() { this.invalidate(); this.page = 1; this.visible = true; return this.load(1) },
    async load(page) {
      const seq = ++this.sequence; const id = this.refund.id
      this.loading = true; this.error = ''
      try {
        const response = await refundAmountRecords(id, { pageNum: page, pageSize: 20 })
        if (seq !== this.sequence || !this.visible || id !== this.refund.id) return
        this.rows = response.data.rows; this.more = response.data.has_more; this.page = page
      } catch (error) {
        if (seq === this.sequence && this.visible && id === this.refund.id) this.error = error.message || '关联退款明细读取失败，请重试'
      } finally { if (seq === this.sequence) this.loading = false }
    }
  }
}
</script>
<style scoped>
.records-scope,.records-hint{font-size:12px;line-height:1.8;color:#7d8da3}.records-scope{overflow-wrap:anywhere;margin-top:0}.records-warning{font-size:12px;color:#b37b25}.records-pagination{display:flex;justify-content:flex-end;align-items:center;gap:12px;margin-top:16px;font-size:12px;color:#718198}.refund-amount-records a{color:#1677d2;overflow-wrap:anywhere}
</style>
