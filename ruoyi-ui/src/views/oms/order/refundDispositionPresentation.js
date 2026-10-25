import { quantityText } from './refundReturnPresentation'
const n = value => Number(value || 0)
export const stopped = row => ['CANCELED', 'RELEASED'].includes(row.state)
export const valueQuantity = (value, row) => value == null ? '待核对' : `${value} ${row.stock_unit || '库存单位'}`
export function scopeQuantity(rows) { return (rows || []).some(row => row.quantity == null) ? '数量待确认' : quantityText(rows || []) }
export function cancellationResult(row) {
  if (stopped(row)) return { label: '原任务已停止', tone: 'info', detail: '不计入成功取消；按当前有效方案继续核对。' }
  if (row.receipt_confirmed) return { label: '取消已确认', tone: 'success', detail: row.logical_released_quantity == null ? '需求取消有原回执；本次释放预占数量未记录。' : `已确认释放预占 ${valueQuantity(row.logical_released_quantity, row)}。` }
  if ((row.warehouses || []).some(w => w.warehouse_state === 'OUTBOUND')) return { label: '已出库，需核对追回', tone: 'warning', detail: '不把原截单任务视为截停成功。' }
  if (['MANUAL', 'UNKNOWN'].includes(row.state)) return { label: '结果待核对', tone: 'warning', detail: row.result_message || '原请求结果尚未确认。' }
  if ((row.warehouses || []).some(w => w.warehouse_state === 'CONFIRMED')) return { label: '已截停，销售取消待确认', tone: 'warning', detail: '仓库撤回与销售需求取消分阶段核对。' }
  return { label: row.state === 'DONE' ? '已执行，原回执待核对' : '取消处理中', tone: 'info', detail: row.result_message || '等待仓库及库存原请求确认。' }
}
export function returnResult(row) {
  if (stopped(row)) return { label: '原安排已停止', tone: 'info', detail: '停止安排不代表实物已退回。' }
  if (row.parcel_pending) return { label: '另有实收待分配', tone: 'warning', detail: '未分配部分不计入本退款实收或库存。' }
  if (n(row.pending_review_quantity)) return { label: '回传待核对', tone: 'warning', detail: `待核对 ${valueQuantity(row.pending_review_quantity, row)}，尚未计入确认实收。` }
  if (n(row.unposted_quantity)) return { label: '已收，部分待入库', tone: 'warning', detail: `已确认实收中还有 ${valueQuantity(row.unposted_quantity, row)} 未入库。` }
  if (n(row.remaining_quantity)) {
    if (row.state === 'STOPPED') return { label: '收货已停止，仍有未收', tone: 'warning', detail: '核对剩余退回或差异处理。' }
    return { label: n(row.accepted_quantity) ? '部分退回' : (row.documents || []).length ? '等待仓库实收' : '退回安排待下发', tone: 'info', detail: `尚需处理 ${valueQuantity(row.remaining_quantity, row)}。` }
  }
  if (n(row.waived_quantity)) return { label: '含不再追回处理', tone: 'warning', detail: `已确认实收 ${valueQuantity(row.accepted_quantity, row)}；另有 ${valueQuantity(row.waived_quantity, row)} 不再追回。` }
  if (n(row.quantity) && n(row.accepted_quantity) === n(row.quantity) && n(row.posted_quantity) === n(row.quantity)) return { label: row.state === 'CLOSED' ? '已退齐并入库' : '已入库，待货物结案', tone: 'success', detail: '本退款此来源的实收与入库数量已核实。' }
  return { label: '待核对退回结果', tone: 'info', detail: row.result_message || '核对原退回安排。' }
}
export const nodeState = value => ({ CONFIRMED: '已确认', COMPLETED: '已完成', POSTED: '已入账', ACCEPTED: '仓库已接单', OUTBOUND: '已出库', UNKNOWN: '待核对', WAITING: '等待确认', NOT_SENT: '尚未发出', PENDING: '待执行', PROCESSING: '处理中', STOPPED: '已停止', FAILED: '明确失败', CANCELED: '已停止', SUCCESS: '操作返回成功', RUNNING: '提交中' })[value] || '待核对'
export const modeText = value => ({ INTERCEPT: '在途拦截退回', REFUSAL: '拒收退回', RETURN: '商品退回' })[value] || '退回'
