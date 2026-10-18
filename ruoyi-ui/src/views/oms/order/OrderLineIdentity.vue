<template>
  <div class="line-identity">
    <div class="identity-heading"><strong>{{ line.oms_goods_name || line.goods_name || '商品资料暂未加载' }}</strong><el-tag size="mini" :type="identityTone">{{ identity }}</el-tag></div>
    <div class="sku">{{ line.sku_sn || 'SKU 待匹配' }}</div>
    <div v-if="spec" class="muted">{{ spec }}<span> · 当前商品资料</span></div>
    <div class="muted">OMS 履约明细 #{{ line.id }}<span v-if="Number(line.exchange_generation)"> · 第 {{ line.exchange_generation }} 次换货后发出</span></div>
    <template v-if="line.exchange_id">
      <div class="source"><span>本次替换：</span><template v-if="line.source_product">{{ line.source_product.goods_name || line.source_product.sku_sn }}<div class="muted">{{ line.source_product.sku_sn }} · 履约明细 #{{ line.source_product.id }}</div></template><span v-else>来源待核对</span></div>
      <div class="links"><router-link :to="exchangeLink">{{ line.exchange_sn || '查看所属换货单' }}</router-link><el-button type="text" size="mini" @click="visible=true">查看换货链路</el-button></div>
      <div v-if="line.source_shipment_line_id" class="muted">来源发货明细 #{{ line.source_shipment_line_id }}</div>
    </template>
    <div v-if="line.platform_spec && !line.exchange_id" class="muted">平台规格：{{ line.platform_spec }}</div>
    <div v-if="line.manual_sku_sn" class="matched">人工匹配 · {{ line.manual_match_by }} · {{ line.manual_match_at }}<br>原商家编码：{{ line.outer_sku_id || '缺失' }}<br>{{ line.manual_match_reason }}</div>
    <el-dialog title="商品换货链路" :visible.sync="visible" width="760px" append-to-body custom-class="order-lineage-dialog">
      <p class="chain-hint">按独立履约明细追溯；相同 SKU 再次换回仍是不同的发货来源。</p>
      <el-alert v-if="line.lineage_incomplete" title="部分来源信息缺失，请核对关联单据。" type="warning" :closable="false" />
      <ol class="lineage">
        <li v-for="(node, index) in line.lineage || []" :key="node.id" :class="{ current: String(node.id) === String(line.id) }">
          <div class="node-heading"><el-tag size="mini" :type="index ? 'warning' : 'info'">{{ index ? '第 ' + index + ' 次换货后发出' : line.lineage_incomplete ? '最早可追溯商品' : '原购商品' }}</el-tag><b>{{ node.goods_name || node.sku_sn }}</b><el-tag v-if="String(node.id) === String(line.id)" size="mini" type="success">当前查看</el-tag></div>
          <div>{{ node.sku_sn }} · {{ node.quantity }} 件</div><div class="muted">平台子单：{{ node.platform_oid }} · OMS 履约明细 #{{ node.id }}</div>
          <router-link v-if="node.exchange_id" :to="{ path: '/oms-order/exchanges', query: { exchangeId: node.exchange_id } }">{{ node.exchange_sn }}</router-link>
          <div v-if="!(node.shipments || []).length" class="muted">尚未生成发货单</div>
          <div v-for="ship in node.shipments || []" :key="ship.shipment_line_id"><el-button type="text" @click="$emit('open-shipment', ship.shipment_id)">{{ ship.shipment_sn }}</el-button><span class="muted"> · 发货明细 #{{ ship.shipment_line_id }} · 已出库 {{ ship.shipped_quantity }} 件</span></div>
        </li>
      </ol>
      <span slot="footer"><el-button @click="visible=false">关闭</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { good } from './exchangePresentation'
export default {
  props: { line: { type: Object, required: true } },
  data() { return { visible: false } },
  computed: {
    terminated() { return Number(this.line.quantity) <= Number(this.line.cancelled_quantity || 0) },
    identity() { if (this.terminated) return this.line.was_replaced ? '已替换 · 不再发货' : '需求已终止'; if (this.line.business_type === 'EXCHANGE') return ({DURING_SALE:'售中换出商品',AFTER_SALE:'售后换出商品'})[this.line.exchange_phase] || '换货换出商品'; return this.line.exchange_id ? '换货后发出 · 原单履约' : '原购商品' },
    identityTone() { return this.terminated ? 'info' : this.line.business_type === 'EXCHANGE' ? 'warning' : this.line.exchange_id ? '' : 'info' },
    exchangeLink() { return { path: '/oms-order/exchanges', query: { exchangeId: this.line.exchange_id } } },
    spec() { return good(this.line.current_goods || {}).spec }
  }
}
</script>
<style scoped>
.line-identity{line-height:1.6;word-break:break-word}.identity-heading{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.sku{color:#465870;margin-top:4px}.muted{color:#8490a2;font-size:12px;line-height:1.7}.source{margin-top:8px;padding:7px 10px;border-left:2px solid #bad3f6;background:#f6f9fd;font-size:13px}.source>span{color:#687b91}.links{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.matched{font-size:12px;color:#426e9a;margin-top:8px}.chain-hint{color:#687b91;margin-top:0}.lineage{list-style:none;padding:0 0 0 12px;margin:20px 0}.lineage li{position:relative;padding:0 0 22px 22px;border-left:2px solid #e1e9f3;word-break:break-word}.lineage li:before{content:'';position:absolute;left:-6px;top:5px;width:10px;height:10px;border-radius:50%;background:#a9bfd9}.lineage li.current:before{background:#409eff}.lineage li:last-child{padding-bottom:0;border-color:transparent}.node-heading{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:6px}
</style>
