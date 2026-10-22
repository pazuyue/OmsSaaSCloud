import { refundCurrentTask, refundGoodsNextAction, refundScenario } from './refundDetailPresentation'

export const refundTaskViews = [{ value: '', label: '全部' }, { value: 'REVIEW', label: '待审核' }, { value: 'GOODS', label: '货物待处理' }, { value: 'GIFT', label: '赠品待处理' }, { value: 'ISSUE', label: '异常待核对' }, { value: 'DONE', label: '已完成' }]

export function businessRefundTask(row) {
  const task = refundCurrentTask(row, [{ needs_action: row.gift_pending === true || Number(row.gift_pending) === 1 }], refundGoodsNextAction(row))
  if (!task) return null
  const issue = (row.parcel_issues || []).length > 0
  return { ...task, issue, button: issue ? '核对包裹' : task.gifts ? '处理赠品' : task.tab === 'approval' ? '核对方案' : '处理货物' }
}

export function progressSummary(progress, fallback) {
  if (!progress) return { label: fallback, detail: '', evidence: '' }
  const planned = Number(progress.return_quantity || 0), accepted = Number(progress.accepted_quantity || 0), posted = Number(progress.posted_quantity || 0), waived = Number(progress.waived_quantity || 0)
  const pending = Number(progress.pending_count || 0) > 0
  const remaining = Number(progress.remaining_quantity || 0), unposted = Number(progress.unposted_quantity || 0)
  const label = planned <= 0 ? '无有效退回安排' : pending ? '实收待核对' : unposted > 0 ? '待入账' : Number(progress.stopped_count) === Number(progress.plan_count) && remaining > 0 ? '退回安排已截停' : remaining > 0 ? '待退回' : waived > 0 ? '已处理（含不再追回）' : '已退入账'
  return { label, detail: `已安排 ${progressQuantity(progress, 'return_quantity')} · 实收 ${progressQuantity(progress, 'accepted_quantity')} · 入账 ${progressQuantity(progress, 'posted_quantity')}${waived ? ' · 不再追回 ' + progressQuantity(progress, 'waived_quantity') : ''}`, evidence: `计划退回 ${progressQuantity(progress, 'return_quantity')}；已确认实收 ${progressQuantity(progress, 'accepted_quantity')}；已入账 ${progressQuantity(progress, 'posted_quantity')}；待退回或差异处理 ${progressQuantity(progress, 'remaining_quantity')}；不再追回 ${progressQuantity(progress, 'waived_quantity')}${pending ? '；另有实收待核对' : ''}` }
}

export function applicationSummary(row) {
  const products = row.application_products || []
  return { name: products.length ? products[0].goods_name : '申请商品资料待核对', sku: products.length ? products[0].sku_sn : '', more: Math.max(0, products.length - 1), quantity: row.platform_quantity == null ? '数量未提供' : `${row.platform_quantity} ${products.length && Number(products[0].is_bundle) ? (products[0].sales_business_type === 'LUCKY_BAG' ? '袋' : '套') : '件'}` }
}

export { refundScenario }
export const refundScenarioViews = [{ value: '', label: '全部场景' }, { value: 'REFUND_RETURN', label: '仅退款含商品退回' }, { value: 'REFUND_GIFT_ONLY', label: '仅退款仅退赠品' }, { value: 'PAID_PENDING', label: '已退款货物未完成' }, { value: 'MISSING_DOCUMENT', label: '商品需退但未建单' }, { value: 'UNPOSTED', label: '实收未入账' }]

export function progressQuantity(progress, key) {
  if (!progress) return '未提供'
  const units = progress.unit_progress || [{ ...progress, physical_unit: '件' }]
  return units.filter(item => Number(item[key]) > 0).map(item => `${item[key]} ${item.physical_unit}`).join('、') || (units.length === 1 ? '0 ' + units[0].physical_unit : '0（分单位见明细）')
}
