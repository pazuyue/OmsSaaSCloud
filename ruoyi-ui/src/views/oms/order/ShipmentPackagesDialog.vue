<template>
  <el-dialog title="包裹与运单" :visible.sync="visible" width="min(960px, 96vw)" append-to-body :close-on-click-modal="false" class="shipment-packages-dialog">
    <div class="packages-dialog-body"><p class="scope-label">{{ scopeLabel }}</p><el-alert v-if="loading" title="正在读取最新包裹信息" type="info" :closable="false" show-icon /><el-empty v-if="!loading && !entries.length" description="该仓库尚未生成发货单，也尚无实际包裹" :image-size="60" />
      <section v-for="entry in entries" :key="entry.id" class="package-shipment"><div class="package-shipment-heading"><strong>{{ entry.shipment_sn }}</strong><span>虚拟仓 {{ entry.store_code || '—' }} · {{ entry.real_store_name || entry.real_store_code }} · {{ entry.carrier_name || '快递未匹配' }}</span></div><div v-if="entry.error" class="load-error"><el-alert :title="entry.error" type="warning" :closable="false" show-icon /><el-button size="small" @click="refresh">重新读取</el-button></div><shipment-package-cards v-else :detail="entry" compact /></section>
    </div><span slot="footer"><el-button :loading="loading" @click="refresh">刷新包裹</el-button><el-button @click="visible=false">关闭</el-button></span>
  </el-dialog>
</template>
<script>
import { shipmentDetail } from '@/api/order'
import ShipmentPackageCards from './ShipmentPackageCards'
export default {
  components: { ShipmentPackageCards },
  data() { return { visible: false, loading: false, items: [], entries: [], scopeLabel: '', requestId: 0 } },
  watch: { visible(value) { if (!value) this.requestId++ } },
  beforeDestroy() { this.requestId++ },
  methods: {
    open(rows, scope = '') { this.items = rows.map(row => ({ ...row })); this.scopeLabel = scope; this.entries = []; this.visible = true; return this.refresh() },
    async refresh() {
      if (!this.visible) return
      const request = ++this.requestId; this.loading = true
      try {
        const entries = []
        for (const row of this.items) {
          try { const data = (await shipmentDetail(row.id)).data; if (request !== this.requestId) return; entries.push(data) }
          catch (_) { if (request !== this.requestId) return; entries.push({ ...row, error: '包裹信息读取失败，请重新读取；当前不代表没有包裹。' }) }
        }
        if (request === this.requestId) {
          const changed = entries.some(row => !row.error && ['status', 'shipped_quantity', 'package_count', 'packaged_quantity', 'unpacked_quantity'].some(field => String(row[field] || '') !== String((this.items.find(item => item.id === row.id) || {})[field] || '')))
          this.entries = entries
          this.items = entries.map(row => ({ ...row }))
          if (changed) this.$emit('changed')
        }
      } finally { if (request === this.requestId) this.loading = false }
    }
  }
}
</script>
<style scoped>.packages-dialog-body{max-height:calc(100vh - 240px);overflow:auto;padding-right:4px}.scope-label{color:#68778d;margin:0 0 16px;overflow-wrap:anywhere;line-height:1.7}.package-shipment{margin:16px 0 24px}.package-shipment-heading{display:flex;flex-wrap:wrap;gap:8px 20px;padding:12px;background:#f4f7fb;border-radius:6px;font-size:13px;color:#36485f;overflow-wrap:anywhere}.load-error{margin-top:12px}.load-error .el-button{margin-top:10px}.shipment-packages-dialog /deep/ .el-dialog{margin-top:5vh!important;margin-bottom:20px}</style>
