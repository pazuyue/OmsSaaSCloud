<template>
  <el-dialog title="登记虚拟出库运单" :visible.sync="visible" width="min(930px, 96vw)" append-to-body :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
    <div v-loading="loading">
      <el-alert title="仅关联已经出库、尚未登记运单的商品；登记运单不会再次扣减库存。" type="info" show-icon :closable="false" />
      <template v-if="detail">
        <p>{{ detail.shipment_sn }} · {{ detail.carrier_name }} <span class="hint">待登记运单 {{ detail.unpacked_quantity || 0 }} 件</span></p>
        <el-form label-width="95px" size="small"><el-form-item label="运单号" required><el-input v-model.trim="express" maxlength="100" placeholder="快递实际运单号" /></el-form-item><el-form-item label="包裹编号"><el-input v-model.trim="packageCode" maxlength="100" placeholder="可留空，由系统生成" /></el-form-item><el-form-item label="实际出库时间"><el-date-picker v-model="outboundTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" placeholder="可留空，记录登记时间" /></el-form-item></el-form>
        <el-table :data="lines" max-height="320"><el-table-column label="商品 / SKU" min-width="220"><template slot-scope="s">{{ s.row.goods_name }}<div class="hint">{{ s.row.sku_sn }}</div></template></el-table-column><el-table-column label="平台子单" prop="platform_oid" min-width="190" /><el-table-column label="已出库 / 已登记" width="140"><template slot-scope="s">{{ s.row.shipped_quantity || 0 }} / {{ s.row.packaged_quantity || 0 }} 件</template></el-table-column><el-table-column label="本包裹数量" width="185"><template slot-scope="s"><el-input-number v-model="s.row.take" size="small" :min="0" :max="s.row.remaining" :precision="0" :disabled="!s.row.remaining" /></template></el-table-column></el-table>
        <p class="hint">本包裹 {{ quantity }} 件。一个发货单可以登记多个包裹，每行累计包裹数量不能超过已出库量。</p>
      </template>
      <el-alert v-if="message" :title="message" :type="completed ? 'success' : 'warning'" show-icon :closable="false" />
    </div>
    <span slot="footer"><el-button :disabled="saving" @click="visible=false">{{ completed ? '关闭' : '取消' }}</el-button><el-button v-if="!completed" type="primary" :disabled="loading || !quantity || !express" :loading="saving" @click="submit">登记运单（{{ quantity }} 件）</el-button></span>
  </el-dialog>
</template>
<script>
import { shipmentDetail, registerShipmentPackage } from '@/api/order'
export default {
  data() { return { visible: false, loading: false, saving: false, detail: null, lines: [], express: '', packageCode: '', outboundTime: '', request: '', requestId: 0, completed: false, message: '' } },
  computed: { quantity() { return this.lines.reduce((n, line) => n + Number(line.take || 0), 0) } },
  methods: {
    async open(id) { if (this.saving) return; const requestId = ++this.requestId; this.visible = true; this.loading = true; this.detail = null; this.lines = []; this.express = ''; this.packageCode = ''; this.outboundTime = ''; this.completed = false; this.message = ''; this.request = 'PK' + Date.now().toString(36) + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2); try { const r = await shipmentDetail(id); if (requestId !== this.requestId) return; this.detail = r.data; this.lines = r.data.lines.map(line => ({ ...line, remaining: Math.max(0, Number(line.shipped_quantity || 0) - Number(line.packaged_quantity || 0)), take: Math.max(0, Number(line.shipped_quantity || 0) - Number(line.packaged_quantity || 0)) })) } finally { if (requestId === this.requestId) this.loading = false } },
    async submit() { if (this.saving || !this.quantity) return; this.saving = true; this.message = ''; try { const r = await registerShipmentPackage(this.detail.id, { control_version: this.detail.control_version, request_id: this.request, express_code: this.express, package_code: this.packageCode, outbound_time: this.outboundTime, lines: this.lines.filter(line => line.take > 0).map(line => ({ id: line.id, quantity: line.take })) }); this.message = r.data.message; this.completed = true; this.$emit('changed') } catch (_) { this.message = '登记结果未确认，请刷新查看包裹记录后核对；再次提交将使用本次原请求号。' } finally { this.saving = false } }
  }
}
</script>
<style scoped>.hint{font-size:13px;color:#8490a2;line-height:1.6;margin:6px 0}</style>
