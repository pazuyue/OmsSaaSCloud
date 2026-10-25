<template>
  <el-dialog :title="mode === 'MANUAL' ? '手工选择 / 调整快递' : '匹配发货单快递'" :visible.sync="visible" width="min(820px, 94vw)" append-to-body :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
    <div v-loading="loading">
      <el-alert title="按发货单匹配，保持分仓占用和单据数量不变；已下发或已有出库数量的单据不能调整。" type="info" :closable="false" show-icon />
      <el-table v-if="!results.length" key="preview" :data="items" :max-height="300" class="summary">
        <el-table-column label="发货单" prop="shipment_sn" min-width="290" /><el-table-column label="实体仓" min-width="140"><template slot-scope="s">{{ s.row.real_store_name || s.row.real_store_code }}</template></el-table-column><el-table-column label="当前快递" min-width="130"><template slot-scope="s">{{ s.row.carrier_name || '未匹配' }}</template></el-table-column>
      </el-table>
      <el-form v-if="mode === 'MANUAL'" label-width="110px" class="summary" :disabled="saving || !!results.length">
        <el-form-item label="收货省份">{{ province || '省份缺失，请核对来源订单地址' }}</el-form-item>
        <el-form-item label="选择快递"><el-select v-model="carrier" filterable placeholder="选择该店铺、仓库及区域可用的快递"><el-option v-for="c in options" :key="c.carrier_code" :value="c.carrier_code" :label="`${c.carrier_name}（${c.carrier_code}）${c.available ? '' : ' · ' + c.reason}`" :disabled="!c.available" /></el-select><p v-if="!loading && !options.some(c => c.available)" class="hint">暂无可用快递，请核对店铺允许快递及仓库配送范围。</p></el-form-item>
        <el-form-item label="调整原因"><el-input v-model.trim="reason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="说明选择或更换该快递的原因" /></el-form-item>
      </el-form>
      <p v-else class="hint">按店铺启用的快递规则重新尝试；已经确认快递的单据保留现有选择。</p>
      <p v-if="saving" class="hint">已处理 {{ results.length }} / {{ items.length }} 张发货单</p>
      <el-table v-if="results.length" key="results" :data="results" :max-height="380" class="summary"><el-table-column label="发货单" min-width="290"><template slot-scope="s">{{ s.row.shipment_sn || (items.find(i => i.id === s.row.id) || {}).shipment_sn }}</template></el-table-column><el-table-column label="结果" width="100"><template slot-scope="s"><el-tag size="small" :type="s.row.success ? 'success' : s.row.unconfirmed || s.row.not_submitted ? 'warning' : 'danger'">{{ s.row.success ? '成功' : s.row.unconfirmed ? '待核对' : s.row.not_submitted ? '未提交' : '失败' }}</el-tag></template></el-table-column><el-table-column label="说明" prop="message" min-width="250" /></el-table>
      <router-link v-hasPermi="['order:pull:config']" class="settings-link" to="/oms-order/businessSettings?tab=carrier-rules" @click.native="visible=false">查看快递规则</router-link>
    </div>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">{{ results.length ? '关闭' : '取消' }}</el-button><el-button v-if="!results.length" type="primary" :loading="saving" :disabled="loading || !items.length || (mode === 'MANUAL' && (!carrier || !reason))" @click="submit">{{ mode === 'MANUAL' ? '确认快递' : '开始匹配' }}</el-button></span>
  </el-dialog>
</template>
<script>
import { shipmentCarrierOptions, matchShipmentCarriers } from '@/api/order'
export default {
  data() { return { visible: false, loading: false, saving: false, items: [], mode: 'AUTO', options: [], carrier: '', reason: '', province: '', results: [], requestId: 0 } },
  methods: {
    async open(rows, mode = 'AUTO') { if (this.saving || !rows.length) return; const id = ++this.requestId; this.visible = true; this.items = rows.map(r => ({ ...r })); this.mode = mode; this.options = []; this.carrier = ''; this.reason = ''; this.province = ''; this.results = []; this.loading = mode === 'MANUAL'; if (mode === 'MANUAL') { try { const r = await shipmentCarrierOptions(rows[0].id); if (id !== this.requestId) return; this.options = r.data.carriers; this.province = r.data.receiver_province; if (this.options.some(c => c.carrier_code === rows[0].carrier_code && c.available)) this.carrier = rows[0].carrier_code } finally { if (id === this.requestId) this.loading = false } } },
    async submit() {
      if (this.saving) return; this.saving = true;
      try {
        for (let offset = 0; offset < this.items.length; offset += 20) {
          const chunk = this.items.slice(offset, offset + 20)
          try { const r = await matchShipmentCarriers({ mode: this.mode, items: chunk.map(s => ({ id: s.id, control_version: s.control_version })), carrier_code: this.carrier, reason: this.reason }); this.results.push(...r.data) }
          catch (_) {
            this.results.push(...chunk.map(s => ({ id: s.id, shipment_sn: s.shipment_sn, success: false, unconfirmed: true, message: '本次请求结果未确认，请刷新后核对再重试' })))
            this.results.push(...this.items.slice(offset + 20).map(s => ({ id: s.id, shipment_sn: s.shipment_sn, success: false, not_submitted: true, message: '尚未提交，请刷新后重新匹配' })))
            break
          }
        }
      } finally { this.saving = false; this.$emit('changed') }
    }
  }
}
</script>
<style scoped>.summary{margin:18px 0}.hint{font-size:13px;color:#8490a2;margin:10px 0}.el-select{width:100%}.settings-link{color:#409eff;font-size:13px}</style>
