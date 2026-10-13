<template>
  <div class="app-container shipment-workspace">
    <el-form :inline="true" :model="query" size="small" @submit.native.prevent="search">
      <el-form-item label="单号"><el-input v-model.trim="query.number" placeholder="完整发货单、OMS 或平台单号" clearable style="width:270px" @keyup.enter.native="search" /></el-form-item>
      <el-form-item label="店铺"><el-select v-model="query.channelId" clearable filterable placeholder="全部店铺"><el-option v-for="shop in channels" :key="shop.channel_id" :value="shop.channel_id" :label="shop.channel_name" /></el-select></el-form-item>
      <el-form-item label="实体仓"><el-input v-model.trim="query.store" placeholder="实体仓编码" clearable style="width:150px" @keyup.enter.native="search" /></el-form-item>
      <el-form-item label="仓库下发"><el-select v-model="query.dispatchState" clearable placeholder="全部下发状态" style="width:160px"><el-option v-for="state in dispatchStates" :key="state" :value="state" :label="dispatchLabel(state)" /></el-select></el-form-item>
      <el-form-item label="SKU"><el-input v-model.trim="query.sku" placeholder="完整 SKU" clearable style="width:150px" @keyup.enter.native="search" /></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
    </el-form>
    <el-tabs v-model="query.status" @tab-click="search"><el-tab-pane label="全部" name="ALL" /><el-tab-pane v-for="state in states" :key="state" :label="label(state)" :name="state" /></el-tabs>
    <div class="toolbar"><span>共 {{ total }} 张发货单 · 已选 {{ selected.length }} 张（批量最多 20 张）</span><div><el-button v-hasPermi="['order:shipment:carrier']" size="small" type="primary" :disabled="!selected.length || selected.length > 20 || selected.some(s => !s.can_match_carrier || s.status !== 'WAIT_CARRIER')" @click="$refs.carrier.open(selected)">批量匹配快递</el-button><el-button v-hasPermi="['order:shipment:dispatch']" size="small" type="primary" :disabled="!selected.length || selected.length > 20" @click="$refs.dispatch.open(selected)">批量下发仓库</el-button><el-button v-hasPermi="['order:shipment:queryWarehouse']" size="small" :disabled="!selected.length || selected.length > 20 || !selected.some(s => s.can_query_warehouse)" @click="$refs.dispatch.open(selected, 'QUERY')">批量核对仓库</el-button><el-button v-hasPermi="['order:shipment:virtualOutbound']" size="small" type="primary" :disabled="!selected.length || selected.length > 20 || selected.some(s => !s.can_virtual_outbound)" @click="$refs.virtualOutbound.open(selected)">批量虚拟出库</el-button><el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table ref="table" v-loading="loading" :data="rows" empty-text="暂无符合条件的发货单，订单分仓确认后自动生成" @selection-change="selected = $event">
      <el-table-column type="selection" width="48" :selectable="row => !['VOIDING', 'VOIDED'].includes(row.status)" />
      <el-table-column label="发货单 / 来源订单" min-width="260"><template slot-scope="s"><el-button v-hasPermi="['order:order:query']" type="text" @click="$refs.detail.open(s.row.id)">{{ s.row.shipment_sn }}</el-button><div class="hint">OMS：<el-button type="text" class="source-order" @click="openOrder(s.row.order_id)">{{ s.row.order_sn }}</el-button></div><div class="hint">平台：{{ s.row.platform_tid }}</div></template></el-table-column>
      <el-table-column label="渠道 / 店铺" min-width="140"><template slot-scope="s">天猫 · {{ s.row.channel_name || s.row.channel_id }}</template></el-table-column>
      <el-table-column label="实体仓 / 货主" min-width="150"><template slot-scope="s">{{ s.row.real_store_name || s.row.real_store_code }}<div class="hint">{{ s.row.real_store_code }} · {{ s.row.owner_code }}</div></template></el-table-column>
      <el-table-column label="发货状态" width="125"><template slot-scope="s"><el-tag size="small" :type="tone(s.row.status)">{{ label(s.row.status, s.row.outbound_mode) }}</el-tag></template></el-table-column>
      <el-table-column label="仓库下发 / 出库任务" min-width="200"><template slot-scope="s"><shipment-dispatch-status :row="s.row" /></template></el-table-column>
      <el-table-column label="快递 / 匹配结果" min-width="220"><template slot-scope="s"><span>{{ s.row.carrier_name || (s.row.carrier_match_status === 'FAILED' ? '匹配失败' : '尚未匹配') }}</span><div v-if="s.row.carrier_code" class="hint">{{ s.row.carrier_code }} · {{ s.row.carrier_match_mode === 'MANUAL' ? '手工确认' : '自动匹配' }}</div><div v-if="s.row.carrier_match_status === 'FAILED'" class="failure">{{ s.row.carrier_match_reason }}</div><div v-if="s.row.fulfillment_block" class="failure">{{ s.row.fulfillment_block }}</div></template></el-table-column>
      <el-table-column label="数量" min-width="150"><template slot-scope="s">计划 {{ s.row.quantity }} 件<div class="hint">已出库 {{ s.row.shipped_quantity }} 件<div class="hint">剩余 {{ Math.max(0, Number(s.row.quantity) - Number(s.row.shipped_quantity || 0)) }} 件</div></div><div v-if="s.row.package_count" class="hint">{{ s.row.package_count }} 个包裹</div><div v-if="s.row.unpacked_quantity" class="failure">待登记运单 {{ s.row.unpacked_quantity }} 件</div></template></el-table-column>
      <el-table-column label="生成批次 / 时间" min-width="175"><template slot-scope="s">第 {{ s.row.generation_no }} 批<div class="hint">{{ s.row.create_time }}</div><div v-if="s.row.wms_submitted_at" class="hint">首次提交 {{ s.row.wms_submitted_at }}</div><div v-if="s.row.wms_updated_at" class="hint">最近处理 {{ s.row.wms_updated_at }}</div></template></el-table-column>
      <el-table-column label="操作" width="190" fixed="right"><template slot-scope="s"><template v-if="s.row.can_match_carrier"><el-button v-if="s.row.status === 'WAIT_CARRIER'" v-hasPermi="['order:shipment:carrier']" type="text" @click="$refs.carrier.open([s.row])">匹配快递</el-button><el-button v-hasPermi="['order:shipment:carrier']" type="text" @click="$refs.carrier.open([s.row], 'MANUAL')">{{ s.row.carrier_code ? '调整快递' : '手工选择' }}</el-button></template><el-button v-if="s.row.can_register_package" v-hasPermi="['order:shipment:package']" type="text" @click="$refs.packages.open(s.row.id)">登记运单</el-button><el-button v-if="s.row.can_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" type="text" @click="$refs.virtualOutbound.open([s.row])">虚拟出库</el-button><el-button v-if="s.row.can_retry_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" type="text" @click="$refs.virtualOutbound.open([s.row], 'RETRY')">重试虚拟出库</el-button><el-button v-if="s.row.can_query_virtual_outbound" v-hasPermi="['order:shipment:virtualOutbound']" type="text" @click="$refs.virtualOutbound.open([s.row], 'QUERY')">核对出库</el-button><el-button v-if="s.row.can_dispatch" v-hasPermi="['order:shipment:dispatch']" type="text" @click="$refs.dispatch.open([s.row])">{{ s.row.wms_dispatch_state === 'FAILED' ? '重试下发' : '下发仓库' }}</el-button><el-button v-if="s.row.can_query_warehouse" v-hasPermi="['order:shipment:queryWarehouse']" type="text" @click="$refs.dispatch.open([s.row], 'QUERY')">核对仓库结果</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />
    <shipment-detail ref="detail" @open-order="openOrder" @changed="load" />
    <shipment-carrier-dialog ref="carrier" @changed="load" /><shipment-dispatch-dialog ref="dispatch" @changed="load" @open-detail="$refs.detail.open($event)" /><shipment-virtual-outbound-dialog ref="virtualOutbound" @changed="load" /><shipment-package-dialog ref="packages" @changed="load" />
  </div>
</template>
<script>
import { shipmentList, orderCatalog } from '@/api/order'
import { shipmentLabel, shipmentTone, dispatchLabel } from './shipmentLabels'
import ShipmentDetail from './ShipmentDetail'
import ShipmentCarrierDialog from './ShipmentCarrierDialog'
import ShipmentDispatchDialog from './ShipmentDispatchDialog'
import ShipmentDispatchStatus from './ShipmentDispatchStatus'
import ShipmentVirtualOutboundDialog from './ShipmentVirtualOutboundDialog'
import ShipmentPackageDialog from './ShipmentPackageDialog'
const defaults = () => ({ number: '', channelId: undefined, store: '', sku: '', status: 'ALL', dispatchState: '', pageNum: 1, pageSize: 20 })
export default {
  name: 'OrderShipmentWorkspace', components: { ShipmentDetail, ShipmentCarrierDialog, ShipmentDispatchDialog, ShipmentDispatchStatus, ShipmentVirtualOutboundDialog, ShipmentPackageDialog },
  data() { return { query: defaults(), channels: [], rows: [], selected: [], total: 0, loading: false, error: '', requestId: 0, dispatchStates: ['NONE', 'PENDING', 'PROCESSING', 'UNKNOWN', 'FAILED', 'ACCEPTED', 'CANCELED'], states: ['WAIT_CARRIER', 'WAIT_DISPATCH', 'DISPATCHING', 'WAREHOUSE_PROCESSING', 'PART_SHIPPED', 'SHIPPED', 'VOIDING', 'VOIDED'] } },
  watch: { '$route.query.number'() { this.query.number = this.$route.query.number || ''; this.search() } },
  created() { this.query.number = this.$route.query.number || ''; this.load(); orderCatalog().then(r => { this.channels = r.data.channels }) },
  methods: {
    label: shipmentLabel, tone: shipmentTone, dispatchLabel,
    async load() { const id = ++this.requestId; this.loading = true; this.error = ''; this.selected = []; if (this.$refs.table) this.$refs.table.clearSelection(); try { const r = await shipmentList({ ...this.query, status: this.query.status === 'ALL' ? '' : this.query.status }); if (id === this.requestId) { this.rows = r.rows; this.total = r.total } } catch (_) { if (id === this.requestId) { this.rows = []; this.total = 0; this.error = '发货单加载失败，请刷新重试' } } finally { if (id === this.requestId) this.loading = false } },
    search() { this.query.pageNum = 1; this.load() }, reset() { this.query = defaults(); this.load() },
    openOrder(id) { this.$router.push({ path: '/oms-order/business', query: { view: 'business', orderId: String(id) } }) }
  }
}
</script>
<style scoped>.hint{font-size:13px;color:#8490a2;margin:6px 0}.failure{font-size:13px;color:#e6a23c;line-height:1.5;margin:6px 0}.toolbar{display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between;margin:10px 0 18px}.toolbar>div{display:flex;gap:8px;flex-wrap:wrap}.toolbar .el-button+.el-button{margin-left:0}.source-order{font-size:12px;padding:0}</style>
