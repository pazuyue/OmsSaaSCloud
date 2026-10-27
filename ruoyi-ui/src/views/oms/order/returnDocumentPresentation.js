export const warehouseStates = { UNSENT: '未下发', SENDING: '等待仓库响应', ACCEPTED: '仓库已受理', FAILED: '下发失败', UNKNOWN: '受理结果待确认', CANCEL_PENDING: '截停待确认', CANCEL_REJECTED: '仓库拒绝截停', CANCELED: '仓库已截停' }
export const warehouseFilters = { ...warehouseStates, LAST_FAILED: '最近交互失败', WAIT_ALLOCATION: '实收待归属', DIFFERENCE: '最终收货有差异' }
export function warehouseTone(state) { return ['FAILED', 'CANCEL_REJECTED'].includes(state) ? 'danger' : ['UNKNOWN', 'CANCEL_PENDING', 'SENDING'].includes(state) ? 'warning' : state === 'ACCEPTED' ? 'success' : 'info' }
export function warehouseText(row) {
  const docs = row.warehouse_documents || []
  if (!docs.length) return '未下发'
  const states = [...new Set(docs.map(d => warehouseStates[d.warehouse_state] || '状态待核对'))]
  return states.join(' / ') + (docs.length > 1 ? `（${docs.length}张仓单）` : '')
}
export function sourceFacts(member, sources) { return (sources || []).find(s => s.owner_kind === member.owner_kind && Number(s.task_id) === Number((member.warehouse || {}).task_id)) || {} }
export function productRows(members, sources) {
  const by = new Map()
  ;(members || []).forEach(m => {
    const key = [m.sku_sn, m.unit, m.store_code].join('|')
    const p = by.get(key) || { key, sku_sn: m.sku_sn, goods_name: m.goods_name, unit: m.unit, members: [], quantity: 0, accepted_quantity: 0, posted_quantity: 0, cancelled_quantity: 0, good_qty: 0, bad_qty: 0, gradesKnown: true, difference: false }
    const f = sourceFacts(m, sources)
    p.members.push(m); p.difference = p.difference || Number((m.warehouse || {}).receipt_difference) > 0
    ;['quantity', 'accepted_quantity', 'posted_quantity'].forEach(k => { p[k] += Number(m[k] || 0) })
    p.cancelled_quantity += (m.warehouse || {}).cancel_state === 'CONFIRMED' ? Math.max(0, Number(m.quantity) - Number(m.accepted_quantity)) : 0
    p.good_qty += Number(f.good_qty || 0); p.bad_qty += Number(f.bad_qty || 0)
    if (!Object.prototype.hasOwnProperty.call(f, 'good_qty')) p.gradesKnown = false
    by.set(key, p)
  })
  return [...by.values()].map(p => ({ ...p, remaining: Math.max(0, p.quantity - p.accepted_quantity - p.cancelled_quantity), unposted: Math.max(0, p.accepted_quantity - p.posted_quantity) }))
}
export function receiptLabel(row) { return ({ WAIT_ALLOCATION: '实收待归属，尚未入账', ALLOCATED: '已确认来源分配', POSTED: '已入账', PENDING: '已确认实收，等待入账', FAILED: '处理失败' })[row.state] || row.state }
export function receiveLabel(detail) {
  if (Number(detail.pending_count)) return '回传已接收，实收待归属'
  if ((detail.warehouse_documents || []).some(w => Number(w.receipt_difference) > 0)) return '最终回传有差异，待核对'
  const units = detail.units || []
  const any = units.some(u => Number(u.accepted_quantity) > 0)
  const full = units.length && units.every(u => Number(u.accepted_quantity) >= Number(u.quantity))
  return full ? '已确认全部实收' : any ? '部分实收' : '尚未确认实收'
}
export function postingLabel(detail) { const u = detail.units || []; return u.some(x => Number(x.accepted_quantity) > Number(x.posted_quantity)) ? '已实收，部分待入账' : u.some(x => Number(x.posted_quantity) > 0) ? '已确认实收均已入账' : '尚无库存入账' }
