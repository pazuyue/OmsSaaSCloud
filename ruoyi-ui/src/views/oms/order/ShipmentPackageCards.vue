<template>
  <section class="shipment-package-cards" :class="{ compact }">
    <div class="package-section-heading"><h3>包裹与运单 <span>{{ packages.length }} 个</span></h3><slot name="actions" /></div>
    <div class="package-overview">
      <span>实际出库 <strong>{{ detail.shipped_quantity || 0 }}</strong> 件</span>
      <span>剩余待出库 <strong>{{ remaining }}</strong> 件</span>
      <span>已关联包裹 <strong>{{ detail.packaged_quantity || 0 }}</strong> 件</span>
      <span :class="{ warning: Number(detail.unpacked_quantity) > 0 }">已出库待关联包裹 <strong>{{ detail.unpacked_quantity || 0 }}</strong> 件</span>
    </div>
    <p v-if="!packages.length" class="package-empty">{{ emptyMessage }}</p>
    <article v-for="(pack, index) in packages" :key="pack.id || pack.package_code" class="package-card">
      <div class="package-title"><strong>包裹 {{ index + 1 }}</strong><span>{{ pack.carrier_name || pack.raw_carrier_name || '实际快递待识别' }}</span><el-tag size="mini" type="info" effect="plain">{{ isSimulated(pack) ? '模拟运单' : '运单已记录' }}</el-tag><el-tag v-if="pack.shared_review_id" size="mini" type="warning" effect="plain">已核对共用运单</el-tag><span class="package-size">{{ skuGroups(pack).length }} 种 SKU / {{ pack.quantity }} 件</span></div>
      <p v-if="pack.source_mode==='WMS'" class="package-code">WMS 原始编码：{{pack.warehouse_carrier_code || '未提供'}} · OMS 标准编码：{{pack.carrier_code || '待识别'}} <el-tag v-if="pack.carrier_resolution_state && pack.carrier_resolution_state!=='RESOLVED'" size="mini" type="warning">待处理编码对照</el-tag><el-tag v-else-if="pack.standard_id && detail.mandated_standard_id && pack.standard_id!==detail.mandated_standard_id" size="mini" type="warning">与平台指定不一致</el-tag><el-tag v-else-if="pack.standard_id && detail.standard_id && pack.standard_id!==detail.standard_id" size="mini" type="info">实际与计划不同</el-tag></p><div class="package-waybill"><span class="field-label">运单号</span><span class="waybill-number">{{ pack.express_code || '尚未提供' }}</span><el-button v-if="pack.express_code" v-clipboard:copy="pack.express_code" v-clipboard:success="copied" v-clipboard:error="copyFailed" type="text" size="mini" icon="el-icon-document-copy" :aria-label="'复制运单号 ' + pack.express_code">复制</el-button></div>
      <ul class="package-products"><li v-for="product in skuGroups(pack)" :key="product.sku"><div class="product-identity"><span class="product-name">{{ product.name || '商品名称未提供' }}</span><span class="product-sku">{{ product.sku || 'SKU 未提供' }}</span></div><strong class="product-quantity">× {{ product.quantity }} 件</strong></li></ul>
      <p v-if="!(pack.lines || []).length" class="warning">包裹商品明细尚未同步，请刷新核对。</p>
      <div class="package-meta"><span>来源：{{ pack.source_mode === 'WMS' ? '仓库回传' : '人工登记' }}</span><span>出库时间：{{ pack.outbound_time || '未提供' }}</span></div>
      <el-collapse v-model="expanded" class="package-sources"><el-collapse-item :name="String(pack.id || pack.package_code)" title="查看包裹编号与商品来源"><p class="package-code">包裹编号：{{ pack.package_code || '未提供' }}</p><el-table :data="pack.lines || []" size="mini"><el-table-column label="SKU" prop="sku_sn" min-width="140" /><el-table-column label="平台子单号" prop="platform_oid" min-width="190" /><el-table-column label="虚拟仓 / 库存批次" min-width="160"><template slot-scope="s">{{ s.row.store_code || '—' }} / {{ s.row.batch_code || (s.row.batch_id ? '批次 ID ' + s.row.batch_id : '—') }}</template></el-table-column><el-table-column label="包裹内数量" width="105" align="right"><template slot-scope="s">{{ s.row.quantity }} 件</template></el-table-column></el-table></el-collapse-item></el-collapse>
    </article>
  </section>
</template>
<script>
import { packageSkuGroups, simulatedPackage } from './packagePresentation'
export default {
  props: { detail: { type: Object, required: true }, compact: Boolean },
  data() { return { expanded: [] } },
  computed: {
    packages() { return this.detail.packages || [] },
    remaining() { return ['VOIDING', 'VOIDED'].includes(this.detail.status) ? 0 : Math.max(0, Number(this.detail.quantity || 0) - Number(this.detail.shipped_quantity || 0)) },
    emptyMessage() { return Number(this.detail.unpacked_quantity) > 0 ? '尚无包裹。已有出库商品待关联运单，可登记运单或核对仓库回传。' : Number(this.detail.shipped_quantity) > 0 ? '尚无包裹记录，请刷新核对。' : ['VOIDING', 'VOIDED'].includes(this.detail.status) ? '尚无包裹，发货单已停止履约。' : '尚无包裹，实际包裹与运单将在出库回传或登记后展示。' }
  },
  methods: { skuGroups(pack) { return packageSkuGroups(pack.lines) }, isSimulated: simulatedPackage, copied() { this.$message.success('运单号已复制') }, copyFailed() { this.$message.info('请选中运单号复制') } }
}
</script>
<style scoped>
.shipment-package-cards{margin:20px 0}.package-section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.package-section-heading h3{margin:0;font-size:16px;color:#27374c}.package-section-heading h3 span{font-size:13px;color:#68778d;font-weight:400;margin-left:8px}.package-overview{display:flex;flex-wrap:wrap;gap:8px 22px;padding:12px 0;color:#68778d;font-size:13px}.package-overview strong{color:#27374c}.package-empty{padding:16px;background:#f7f9fc;border:1px dashed #dbe3ee;border-radius:8px;color:#68778d;line-height:1.7;font-size:13px}.package-card{border:1px solid #dfe7f1;border-radius:8px;padding:16px;margin:10px 0;background:#fff}.package-title{display:flex;align-items:center;flex-wrap:wrap;gap:10px;color:#36485f;font-size:14px}.package-size{margin-left:auto;font-weight:600}.package-waybill{display:flex;align-items:flex-start;gap:12px;margin:12px 0;font-size:14px}.field-label{flex-shrink:0;color:#68778d;line-height:28px}.waybill-number{font-variant-numeric:tabular-nums;font-weight:600;color:#245f9c;overflow-wrap:anywhere;line-height:28px}.package-waybill .el-button{flex-shrink:0}.package-products{list-style:none;padding:0;margin:10px 0;border-top:1px solid #edf1f6}.package-products li{display:flex;gap:16px;align-items:center;padding:10px 0;border-bottom:1px solid #edf1f6}.product-identity{min-width:0;flex:1}.product-name{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:#52647c;font-size:13px;line-height:1.6}.product-sku{display:block;overflow-wrap:anywhere;color:#245f9c;font-size:13px;margin-top:3px}.product-quantity{white-space:nowrap;color:#27374c;font-size:14px}.package-meta{display:flex;flex-wrap:wrap;gap:8px 24px;font-size:12px;color:#68778d;line-height:1.7}.package-sources{border:0;margin-top:6px}.package-sources /deep/ .el-collapse-item__header{border:0;font-size:12px;color:#68778d;height:32px;line-height:32px}.package-sources /deep/ .el-collapse-item__wrap{border:0}.package-sources /deep/ .el-collapse-item__content{padding-bottom:0}.package-code{overflow-wrap:anywhere;font-size:12px;color:#68778d}.warning{color:#a46a10;font-size:13px;line-height:1.6}.compact .package-card{padding:12px}.compact .package-overview{gap:6px 16px}@media(max-width:650px){.package-card{padding:12px}.package-size{margin-left:0}.package-waybill{gap:8px;flex-wrap:wrap}.waybill-number{max-width:100%}.package-products li{gap:10px}}
</style>
