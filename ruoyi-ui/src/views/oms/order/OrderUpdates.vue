<template>
  <section v-loading="loading" class="order-updates">
    <div class="updates-heading"><strong>订单更新</strong><el-button size="small" :disabled="busy" @click="load">刷新更新记录</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-else-if="report">
      <el-alert :title="report.update_summary || '暂无待处理的订单更新'" :type="report.change_pending ? 'warning' : 'info'" :closable="false" show-icon :description="report.change_pending ? '整单暂停新增预占、审核、分仓、匹配快递及下发；原库存占用保留。仓库回传与记账继续处理。' : '平台最新版本与业务采用版本分别保留，已发货记录保持原快照。'" />
      <p class="updates-note">平台单号 {{ report.platform_tid }} · 来源更新 {{ report.source_modified }} · 当前版本 {{ report.update_version }}</p>
      <div v-if="report.change_pending" class="updates-tags"><el-tag size="small" :type="report.update_state === 'ERROR' ? 'danger' : 'info'">{{ states[report.update_state] || report.update_state }}</el-tag><el-tag v-for="category in categories" :key="category" size="small" type="warning">{{ labels[category] || category }}</el-tag></div>
      <el-alert v-if="Number(report.active_shipments) > 0 && report.change_pending" title="仍有未完成的原发货单，仓库可能继续发货" description="平台变更阻断不会自动通知仓库截单。请核对下方发货单；需停止发货时使用截单入口。" type="warning" :closable="false" show-icon />
      <h4>平台最新信息与业务已采用信息的差异</h4>
      <el-table :data="report.differences || []" size="small" empty-text="当前无字段差异；退款等未完成业务信号仍以顶部提示为准">
        <el-table-column label="类型 / 平台子单" min-width="170"><template slot-scope="s">{{ differenceCategory(s.row.category) }}<div class="updates-note">{{ s.row.platform_oid || '订单整体' }}</div></template></el-table-column>
        <el-table-column label="字段" width="145"><template slot-scope="s">{{ fields[s.row.field] || s.row.field }}</template></el-table-column>
        <el-table-column label="业务已采用值" min-width="210"><template slot-scope="s"><span class="difference-value">{{ displayValue(s.row, 'before') }}</span></template></el-table-column>
        <el-table-column label="平台最新值" min-width="210"><template slot-scope="s"><span class="difference-value changed-value">{{ displayValue(s.row, 'after') }}</span></template></el-table-column>
      </el-table>
      <div v-if="report.can_apply_recipient" class="recipient-action">
        <h4>处理收货信息更新</h4>
        <p>先撤回原未发履约，再应用平台最新收货信息。应用后重新审核，按新地址重新分仓及匹配快递；其他商品、金额或售后阻断继续保留。</p>
        <el-input v-model="reason" type="textarea" :rows="2" maxlength="150" show-word-limit placeholder="填写处理原因，例如：已与客户核实新地址" :disabled="busy" />
        <div class="updates-actions">
          <el-button v-if="report.order" v-hasPermi="['order:order:update', 'order:order:cutoff']" :disabled="busy || !reason.trim() || !Number(report.active_shipments) || report.order.status === 'CUTTING'" @click="act('withdraw')">先撤回原未发履约</el-button>
          <el-button v-hasPermi="['order:order:update']" type="primary" :disabled="busy || !reason.trim() || !canApply" @click="act('recipient')">应用收货信息</el-button>
          <span v-if="!canApply" class="updates-note">{{ applyBlock }}</span>
        </div>
      </div>
      <el-alert v-if="deferred" title="以下变化等待对应业务流程承接" :description="deferred + '。本期记录并阻断，不直接覆盖商品、数量或金额，不自动释放库存；后续关联售中、售后或纠错申请处理。'" type="info" :closable="false" />
      <template v-if="report.order">
        <div class="updates-heading"><h4>关联履约</h4><el-button v-hasPermi="['order:order:cutoff']" type="text" @click="$emit('cutoff', report.order)">截单 / 查看处理结果</el-button></div>
        <el-table :data="report.shipments || []" size="small" empty-text="尚无发货单"><el-table-column label="发货单号" prop="shipment_sn" min-width="280" /><el-table-column label="状态" width="130"><template slot-scope="s">{{ shipmentStates[s.row.status] || s.row.status }}</template></el-table-column><el-table-column label="原单数量" prop="quantity" width="100" /><el-table-column label="已发货" prop="shipped_quantity" width="90" /><el-table-column label="已截停" prop="cancelled_quantity" width="90" /></el-table>
        <p v-for="op in report.cutoff_operations || []" :key="op.id" class="updates-note">截单 #{{ op.id }} · {{ op.mode === 'REPLAN' ? '撤回重新发货' : '终止剩余履约' }} · {{ op.state === 'COMPLETED' ? '已完成' : '待核对' }} {{ op.last_error || '' }}</p>
      </template>
      <h4>关联换货处理</h4>
      <el-table :data="report.exchanges || []" size="small" empty-text="尚无关联申请；平台申请可独立同步，未发货协商换品可在换货管理创建"><el-table-column label="换货单" min-width="250"><template slot-scope="s"><router-link :to="{ path: '/oms-order/exchanges', query: { exchangeId: s.row.id } }">{{ s.row.exchange_sn }}</router-link></template></el-table-column><el-table-column label="申请数量" prop="requested_quantity" width="100" /><el-table-column label="处理状态" width="150"><template slot-scope="s">{{ exchangeStates[s.row.state] || s.row.state }}</template></el-table-column><el-table-column label="执行阶段" width="180"><template slot-scope="s">{{ exchangeStages[s.row.stage] || s.row.stage }}</template></el-table-column></el-table>
      <p class="updates-note"><router-link to="/oms-order/exchanges">进入换货管理</router-link>，关联申请与具体商品差异；其他订单更新阻断继续保留。</p>
      <h4>更新历史（最近 30 次）</h4>
      <el-collapse @change="loadEvents"><el-collapse-item v-for="event in report.history || []" :key="event.id" :name="event.id" :title="'版本 ' + event.version + ' · ' + event.create_time + ' · ' + (states[event.state] || event.state)">
        <p v-if="event.loadError" class="updates-note">差异加载失败，请收起后重新展开</p><p>{{ event.reason }} · {{ event.operator_name }}<span v-if="event.cutoff_id"> · 关联截单 #{{ event.cutoff_id }}</span></p>
        <el-table :data="event.differences || []" size="mini"><el-table-column label="平台子单" prop="platform_oid" min-width="180" /><el-table-column label="变化字段" width="145"><template slot-scope="s">{{ fields[s.row.field] || s.row.field }}</template></el-table-column><el-table-column label="更新前" min-width="210"><template slot-scope="s">{{ displayValue(s.row, 'before') }}</template></el-table-column><el-table-column label="更新后" min-width="210"><template slot-scope="s">{{ displayValue(s.row, 'after') }}</template></el-table-column></el-table>
      </el-collapse-item></el-collapse>
      <el-empty v-if="!(report.history || []).length" description="尚无更新记录" :image-size="50" />
    </template>
  </section>
</template>

<script>
import { states as exchangeStates, stages as exchangeStages } from './exchangeLabels'
import { orderUpdates, orderUpdateEvent, applyOrderRecipient, withdrawOrderForUpdate } from '@/api/order'
export const updateLabels = { RECIPIENT: '收货信息变更', GOODS: '商品变更待处理', AMOUNT: '金额变更待核对', REFUND: '售后影响履约', CLOSE: '平台交易关闭', HOLD: '平台暂缓发货', STATUS: '平台状态待核对', TRADE: '交易信息变更', CONFLICT: '来源版本冲突', DATA: '平台数据待核对', DISPLAY: '展示信息' }
export default {
  name: 'OrderUpdates',
  props: { platformId: { type: [Number, String], required: true } },
  data() { return { exchangeStates, exchangeStages, loading: false, busy: false, error: '', report: null, reason: '', sequence: 0, labels: updateLabels, states: { WAITING: '待处理', PROCESSING: '履约撤回中 / 待应用', ERROR: '处理异常待核对', SUPERSEDED: '已被后续平台版本接续', COMPLETED: '本版本已处理' }, shipmentStates: { VOIDED: '已撤回', WAIT_CARRIER: '待匹配快递', WAIT_DISPATCH: '待下发仓库', DISPATCHING: '下发处理中', WAREHOUSE_PROCESSING: '仓库处理中', PART_SHIPPED: '部分发货', SHIPPED: '已发货' }, fields: { receiver_name: '收件人', receiver_mobile: '手机', receiver_phone: '电话', receiver_state: '省份', receiver_city: '城市', receiver_district: '区县', receiver_town: '街道', receiver_address: '详细地址', oaid: '平台地址标识', post_fee: '买家运费', discount_fee: '平台优惠金额', adjust_fee: '改价金额', total_fee: '平台应付金额', price: '优惠前销售单价', part_mjz_discount: '整单优惠分摊', currency: '币种', promotion_details: '优惠明细', pay_time: '平台付款时间', consign_time: '平台发货时间', end_time: '平台交易结束时间', invoice_name: '发票抬头', invoice_type: '发票类型', invoice_tax_no: '发票税号', invoice_status: '开票状态', payment: '实付金额', divide_order_fee: '子单分摊金额', status: '平台状态', refund_status: '退款状态', refund_id: '退款单号', top_hold: '平台暂缓发货', num_iid: '平台商品 ID', sku_id: '平台规格 ID', outer_sku_id: '商家 SKU 编码', outer_iid: '商家商品编码', num: '购买数量', sku_properties_name: '规格描述', title: '商品标题', type: '交易类型', seller_nick: '卖家', line: '商品明细', seller_memo: '卖家备注', buyer_message: '买家留言' } } },
  computed: {
    categories() { return ((this.report || {}).update_categories || '').split(',').filter(Boolean) },
    deferred() { return this.categories.filter(x => ['GOODS', 'AMOUNT', 'REFUND', 'TRADE', 'CONFLICT', 'DATA', 'CLOSE'].includes(x)).map(x => this.labels[x]).join('、') },
    applyBlock() { const r = this.report || {}; const o = r.order; if (!o) return ''; if (o.status !== 'WAIT_ALLOCATION') return '请先核对截单及订单履约状态'; if (['PROCESSING', 'UNCERTAIN'].includes(o.allocation_status)) return '分仓结果尚未确认'; if (Number(r.active_shipments)) return '请先完成原未发履约撤回及库存核对'; return '' },
    canApply() { return !!this.report && this.report.can_apply_recipient && !this.applyBlock }
  },
  watch: { platformId: { immediate: true, handler() { this.report = null; this.reason = ''; this.load() } } },
  beforeDestroy() { this.sequence++ },
  methods: {
    differenceCategory(code) { return ({ STATUS: '平台状态变化', REFUND: '退款信号变化', HOLD: '平台发货控制变化' })[code] || this.labels[code] || '展示信息' },
    displayValue(row, side) { const value = row[side]; if (!value) return '—'; if (row.field === 'refund_status') return ({ NO_REFUND: '无退款', CLOSED: '退款关闭', SUCCESS: '退款成功', WAIT_SELLER_AGREE: '买家申请退款，待商家处理', SELLER_REFUSE_BUYER: '商家拒绝退款', WAIT_BUYER_RETURN_GOODS: '待买家退货', WAIT_SELLER_CONFIRM_GOODS: '待商家确认收货' })[value] || value; if (row.field === 'status') return ({ WAIT_BUYER_PAY: '待付款', WAIT_SELLER_SEND_GOODS: '已付款待发货', WAIT_BUYER_CONFIRM_GOODS: '已发货', TRADE_FINISHED: '交易完成', TRADE_CLOSED: '交易关闭', TRADE_CLOSED_BY_TAOBAO: '交易关闭' })[value] || value; if (row.field === 'top_hold') return value === '1' ? '暂缓发货' : '未暂缓发货'; if (row.field === 'line') { try { const line = JSON.parse(value); return `${line.title || '商品'} · ${line.outer_sku_id || line.sku_id || line.num_iid || '编码待核对'} · ${line.num || '待核对'} 件` } catch (_) { return value } } return value },
    async loadEvents(ids) { const platform = this.platformId; const rows = ((this.report || {}).history || []).filter(r => ids.includes(r.id) && !r.differences && !r.loading); for (const row of rows) { this.$set(row, 'loading', true); this.$set(row, 'loadError', false); try { const r = await orderUpdateEvent(platform, row.id); if (String(platform) === String(this.platformId)) this.$set(row, 'differences', r.data.differences) } catch (_) { this.$set(row, 'loadError', true) } finally { this.$set(row, 'loading', false) } } },
    async load() { const seq = ++this.sequence; this.loading = true; this.error = ''; try { const r = (await orderUpdates(this.platformId)).data; if (seq === this.sequence) this.report = r } catch (_) { if (seq === this.sequence) this.error = '更新记录加载失败，请刷新重试' } finally { if (seq === this.sequence) { this.loading = false; this.$nextTick(() => this.$emit('ready')) } } },
    async act(action) { if (this.busy || !this.reason.trim()) return; const id = this.platformId; const data = { update_version: this.report.update_version, reason: this.reason.trim() }; try { await this.$confirm(action === 'withdraw' ? '将申请撤回原未发履约。仓库确认和库存核对完成后，仍需应用收货信息。' : '采用平台最新收货信息；已发货快照保持，未发部分需要重新审核。其他阻断不会解除。', '确认处理订单更新', { type: 'warning' }) } catch (_) { return } this.busy = true; try { const r = await (action === 'withdraw' ? withdrawOrderForUpdate(id, data) : applyOrderRecipient(id, data)); if (String(id) === String(this.platformId)) this.report = r.data; this.$message.success(action === 'withdraw' ? '已提交撤回，请刷新核对处理结果' : '收货信息已应用'); this.$emit('changed') } finally { this.busy = false } }
  }
}
</script>

<style scoped>
.order-updates{padding:8px 0 20px}.updates-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:8px 0 12px}.updates-note{color:#8492a6;font-size:12px;line-height:1.7}.updates-tags{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0}.recipient-action{background:#f5f8fc;border:1px solid #e4ebf3;border-radius:8px;padding:16px;margin:18px 0}.recipient-action h4{margin-top:0}.recipient-action p{color:#606266;font-size:13px;line-height:1.7}.updates-actions{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin-top:12px}.difference-value{white-space:pre-wrap;overflow-wrap:anywhere}.changed-value{color:#a66a16}h4{margin:20px 0 12px}
</style>
