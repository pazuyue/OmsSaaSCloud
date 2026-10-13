<template>
  <el-drawer title="发货单详情" :visible.sync="visible" size="min(1180px, 94vw)" append-to-body>
    <div v-loading="loading" class="shipment-detail">
      <template v-if="detail">
        <div class="identity"><strong>{{ detail.shipment_sn }}</strong><el-tag :type="tone(detail.status)">{{ label(detail.status, detail.outbound_mode) }}</el-tag></div>
        <el-alert v-if="nextAction" :title="nextAction" type="info" :closable="false" show-icon />
        <el-button v-if="detail.shipment_sn.startsWith('XS')" v-hasPermi="['warehouse:tickets:list']" type="text" @click="$router.push({ path: '/oms-supplychain/wmsTickets', query: { documentType: 'SALE_OUT', noSn: detail.shipment_sn }}); visible = false">查看销售出库单及明细</el-button>
        <el-alert v-if="detail.fulfillment_block && !['VOIDING', 'VOIDED'].includes(detail.status)" :title="detail.fulfillment_block" type="warning" :closable="false" show-icon />
        <el-descriptions border :column="2" class="summary"><el-descriptions-item label="仓库下发 / 出库任务" :span="2"><shipment-dispatch-status :row="detail" /></el-descriptions-item><template v-if="detail.warehouse_target"><el-descriptions-item label="仓库连接">{{ detail.warehouse_target.connection_name }}</el-descriptions-item><el-descriptions-item label="协议 / 环境">{{ detail.warehouse_target.provider === 'QIMEN' ? '奇门' : '京东虎符' }} · {{ detail.warehouse_target.environment === 'PRODUCTION' ? '正式' : '测试' }}</el-descriptions-item><el-descriptions-item label="外部仓库 / 货主" :span="2">{{ detail.warehouse_target.external_warehouse }} / {{ detail.warehouse_target.external_owner }}</el-descriptions-item></template>
          <el-descriptions-item label="快递">{{ detail.carrier_name ? detail.carrier_name + '（' + detail.carrier_code + '）' : '尚未匹配' }}</el-descriptions-item>
          <el-descriptions-item label="仓库快递编码">{{ detail.warehouse_carrier_code || '—' }}</el-descriptions-item>
          <el-descriptions-item label="匹配方式">{{ detail.carrier_match_mode === 'MANUAL' ? '手工确认' : detail.carrier_match_mode === 'AUTO' ? '自动匹配' : '—' }}</el-descriptions-item>
          <el-descriptions-item label="收货省份">{{ detail.receiver_province || '—' }}</el-descriptions-item>
          <el-descriptions-item label="匹配结果 / 原因" :span="2">{{ detail.carrier_match_status === 'FAILED' ? '失败：' : '' }}{{ detail.carrier_match_reason || '尚未尝试匹配' }}</el-descriptions-item>
          <el-descriptions-item label="匹配人 / 时间" :span="2">{{ detail.carrier_matched_by || '—' }} · {{ detail.carrier_matched_at || '—' }}</el-descriptions-item>
          <el-descriptions-item label="OMS 订单"><el-button type="text" @click="openOrder">{{ detail.order_sn }}</el-button></el-descriptions-item>
          <el-descriptions-item label="平台单号">{{ detail.platform_tid }}</el-descriptions-item>
          <el-descriptions-item label="店铺 / 渠道">{{ detail.channel_name || detail.channel_id }} · 天猫</el-descriptions-item>
          <el-descriptions-item label="实体仓">{{ detail.real_store_name || detail.real_store_code }}（{{ detail.real_store_code }}）</el-descriptions-item>
          <el-descriptions-item label="发货单数量">{{ detail.quantity }} 件</el-descriptions-item>
          <el-descriptions-item label="已发货数量">{{ detail.shipped_quantity }} 件</el-descriptions-item>
          <el-descriptions-item label="货主">{{ detail.owner_code }}</el-descriptions-item>
          <el-descriptions-item label="生成批次">第 {{ detail.generation_no }} 批</el-descriptions-item>
          <el-descriptions-item label="创建人 / 时间" :span="2">{{ detail.created_by }} · {{ detail.create_time }}</el-descriptions-item>
          <el-descriptions-item label="收件人">{{ detail.receiver_name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系方式">{{ detail.receiver_phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" :span="2">{{ detail.receiver_address || '—' }}</el-descriptions-item>
        </el-descriptions>
        <p class="hint">发货单数量来自已确认的分仓占用；实际发货数量以出库结果为准。不同虚拟仓或批次的同一商品分行保留来源。</p>
        <el-table :data="detail.lines">
          <el-table-column label="商品 / SKU" min-width="190"><template slot-scope="s">{{ s.row.goods_name }}<div class="hint">{{ s.row.sku_sn }}</div></template></el-table-column>
          <el-table-column label="平台子单号" prop="platform_oid" min-width="180" />
          <el-table-column label="虚拟仓 / 库存批次" min-width="150"><template slot-scope="s">{{ s.row.store_code }}<div class="hint">批次 {{ s.row.batch_id }}</div></template></el-table-column>
          <el-table-column label="单据数量" prop="quantity" width="100" />
          <el-table-column label="已发货" prop="shipped_quantity" width="90" />
          <el-table-column label="占用来源" min-width="155"><template slot-scope="s">{{ s.row.source_type === 'LOCK' ? '继承渠道锁库' : '公共库存转占用' }}<div class="hint">分仓回执 {{ s.row.allocation_id }}</div></template></el-table-column>
        </el-table>
        <shipment-outbound-history :detail="detail" @changed="changed" />
        <el-table v-if="detail.logs && detail.logs.length" :data="detail.logs" class="summary"><el-table-column label="操作时间" prop="create_time" width="170" /><el-table-column label="操作人" prop="operator_name" width="130" /><el-table-column label="结果" width="90"><template slot-scope="s">{{ s.row.result === 'SUCCESS' ? '成功' : s.row.result === 'FAILED' ? '失败' : '待处理' }}</template></el-table-column><el-table-column label="处理记录" prop="message" min-width="280" /></el-table>
        <div class="footer"><el-button v-if="detail.can_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" type="primary" @click="$refs.virtualOutbound.open([detail])">虚拟出库</el-button><el-button v-if="detail.can_retry_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" @click="$refs.virtualOutbound.open([detail], 'RETRY')">重试虚拟出库</el-button><el-button v-if="detail.can_query_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" @click="$refs.virtualOutbound.open([detail], 'QUERY')">核对出库</el-button><el-button v-if="detail.can_dispatch" v-hasPermi="['order:shipment:dispatch']" type="primary" @click="$refs.dispatch.open([detail])">{{ detail.wms_dispatch_state === 'FAILED' ? '重试下发' : '下发仓库' }}</el-button><el-button v-if="detail.can_query_warehouse" v-hasPermi="['order:shipment:queryWarehouse']" @click="$refs.dispatch.open([detail], 'QUERY')">核对仓库结果</el-button><el-button v-if="detail.wms_dispatch_state && detail.wms_dispatch_state !== 'NONE'" v-hasPermi="['warehouse:wms:log']" @click="$refs.wmsLogs.open(detail.shipment_sn)">仓库交互日志</el-button><template v-if="detail.can_match_carrier"><el-button v-if="detail.status === 'WAIT_CARRIER'" v-hasPermi="['order:shipment:carrier']" type="primary" @click="$refs.carrier.open([detail])">匹配快递</el-button><el-button v-hasPermi="['order:shipment:carrier']" @click="$refs.carrier.open([detail], 'MANUAL')">{{ detail.carrier_code ? '调整快递' : '手工选择' }}</el-button></template><el-button @click="openOrder">查看来源订单</el-button><el-button @click="open(detail.id)">刷新详情</el-button><el-button @click="visible=false">关闭</el-button></div>
      </template>
      <el-empty v-else-if="!loading" description="详情加载失败，请重试" />
    </div>
    <shipment-carrier-dialog ref="carrier" @changed="changed" /><shipment-dispatch-dialog ref="dispatch" @changed="changed" @open-detail="open($event)" /><shipment-virtual-outbound-dialog ref="virtualOutbound" @changed="changed" /><wms-logs ref="wmsLogs" />
  </el-drawer>
</template>
<script>
import { shipmentDetail } from '@/api/order'
import { shipmentLabel, shipmentTone } from './shipmentLabels'
import ShipmentCarrierDialog from './ShipmentCarrierDialog'
import ShipmentDispatchDialog from './ShipmentDispatchDialog'
import ShipmentDispatchStatus from './ShipmentDispatchStatus'
import ShipmentVirtualOutboundDialog from './ShipmentVirtualOutboundDialog'
import WmsLogs from '@/views/oms/warehouse/WmsLogs'
import ShipmentOutboundHistory from './ShipmentOutboundHistory'
export default {
  components: { ShipmentCarrierDialog, ShipmentDispatchDialog, ShipmentDispatchStatus, ShipmentVirtualOutboundDialog, WmsLogs, ShipmentOutboundHistory },
  data() { return { visible: false, loading: false, detail: null, requestId: 0 } },
  computed: {
    nextAction() {
      const row = this.detail; if (!row) return ''
      if (row.fulfillment_block) return '请先处理来源订单的履约阻断原因。'
      if (['VOIDING', 'VOIDED'].includes(row.status)) return ''
      if (row.can_retry_virtual_outbound) return '虚拟出库明确失败，请核对原因后重试。'
      if (row.can_query_virtual_outbound) return '虚拟出库结果待确认，请先核对出库。'
      if (row.can_virtual_outbound) return '本发货单使用虚拟出库，可提交本次实际出库数量。'
      if (row.wms_dispatch_state === 'UNKNOWN') return '仓库受理结果待确认，请先核对仓库结果。'
      if (row.wms_dispatch_state === 'FAILED') return '下发明确失败，请核对原因后按原商品快照重试。'
      if (row.can_dispatch) return '快递已确认，可以核对商品明细后下发仓库。'
      if (row.status === 'WAIT_CARRIER') return '请先匹配快递，再处理仓库下发或虚拟出库。'
      if (row.can_register_package) return '已有实际出库数量，请登记对应运单。'
      if (row.wms_dispatch_state === 'ACCEPTED' && Number(row.shipped_quantity) === 0) return '仓库已接收，等待实际出库回传；当前尚未出库。'
      if (['PENDING', 'PROCESSING'].includes(row.wms_dispatch_state)) return '下发任务已提交，等待仓库确认受理。'
      return ''
    }
  },
  methods: {
    label: shipmentLabel, tone: shipmentTone,
    changed() { this.$emit('changed'); this.open(this.detail.id) },
    async open(id) { const request = ++this.requestId; this.visible = true; this.loading = true; if (!this.detail || this.detail.id !== id) this.detail = null; try { const r = await shipmentDetail(id); if (request === this.requestId) this.detail = r.data } finally { if (request === this.requestId) this.loading = false } },
    openOrder() { const id = this.detail.order_id; this.visible = false; this.$emit('open-order', id) }
  }
}
</script>
<style scoped>
.shipment-detail{padding:0 24px 115px}.identity{display:flex;align-items:center;gap:16px;margin-bottom:20px}.identity strong{font-size:18px;overflow-wrap:anywhere}.summary{margin:16px 0}.hint{font-size:13px;color:#8490a2;margin:6px 0}.footer{position:absolute;bottom:0;left:0;right:0;z-index:3;display:flex;flex-wrap:wrap;justify-content:flex-end;gap:10px;padding:14px 24px;background:#fff;border-top:1px solid #e5eaf1;box-shadow:0 -4px 12px rgba(35,55,80,.04)}.footer .el-button+.el-button{margin-left:0}
</style>
