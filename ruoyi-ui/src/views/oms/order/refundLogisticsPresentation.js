export const logisticsFilters = [
  { value: 'AWAIT_APPLY', label: '拦截待申请' }, { value: 'APPLY_FAILED', label: '平台拦截申请失败' },
  { value: 'RESULT_UNKNOWN', label: '拦截结果未登记' }, { value: 'WAIT_WAYBILL', label: '退回运单待补充' },
  { value: 'WAIT_RECEIVE', label: '退回待实收' }, { value: 'UNPOSTED', label: '实收待入库' }
]
export function interceptApplication(op = {}) {
  return ({ SUCCESS: { label: '平台已受理拦截申请', tone: '' }, ACCEPTED: { label: '平台已受理拦截申请', tone: '' },
    PENDING: { label: '拦截申请待提交', tone: 'info' }, RUNNING: { label: '拦截申请提交中', tone: 'info' },
    UNKNOWN: { label: '平台申请结果待核对', tone: 'warning' }, FAILED: { label: '平台拦截申请失败', tone: 'danger' }
  })[op.state] || { label: '尚无拦截申请记录', tone: 'info' }
}
export function reportedOutcome(op = {}) {
  const labels = { DELIVERY_INTERCEPT_SUCCESS: '已登记拦截成功', DELIVERY_INTERCEPT_FAILED: '已登记拦截失败', BUYER_REFUSE_SIGN: '已登记买家拒签', LOGISTICS_RETURNING: '已登记物流退回中', RETURN_LOGISTIC_SELLER_CONSIGN: '已登记退回物流签收' }
  if (!labels[op.logistic_status]) return { label: '尚未取得快递拦截结果', tone: 'info', source: '无独立快递结果记录' }
  return { label: labels[op.logistic_status] + (op.confirmed ? '' : '，平台回告待确认'), tone: op.logistic_status === 'DELIVERY_INTERCEPT_FAILED' ? 'warning' : 'info', source: '来源：商家核实后回告平台；退款级记录' }
}
export const logisticsSource = value => ({ PLATFORM: '平台退款资料', MANUAL: '人工核实登记', CONFIRMED_PARCEL: '已确认退回包裹', UNKNOWN: '来源待核对' })[value] || '来源待核对'
export const logisticsReturnState = value => ({ RECEIVED: '本退款退回数量已闭合', UNPOSTED: '实收待入库', LINKED: '已关联退回包裹', WAIT_WAYBILL: '退回运单待补充', ARRANGED: '已有退回安排', NONE: '暂无退回安排' })[value] || '退回进度待核对'
export const waybillState = value => ({ BOUND: '已关联退回包裹', PROVIDED: '运单已提供，待关联包裹', MISSING: '退回运单尚未取得', UNAVAILABLE: '包裹物流资料暂未读取' })[value] || '运单资料待核对'
export function groupReturns(rows = []) {
  const groups = new Map()
  for (const row of rows.filter(r => !['CANCELED', 'RELEASED', 'STOPPED'].includes(r.state) || Number(r.accepted_quantity) || Number(r.posted_quantity))) {
    const info = row.logistics || {}, key = info.state === 'BOUND' ? `parcel-${info.parcel_id}` : `plan-${row.plan_id}`
    if (!groups.has(key)) groups.set(key, { key, logistics: info, rows: [], documents: [] })
    const group = groups.get(key); group.rows.push(row)
    for (const d of row.documents || []) if (d.return_sn && !group.documents.some(x => x.return_sn === d.return_sn)) group.documents.push(d)
  }
  return [...groups.values()]
}
