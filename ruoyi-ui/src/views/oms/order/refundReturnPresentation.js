const n = value => Number(value || 0)
export const stockUnit = row => row.stock_unit || '库存单位'
export const rowQuantity = (value, row) => `${n(value)} ${stockUnit(row)}`

export function quantityText(rows, key = 'quantity') {
  const units = new Map()
  rows.forEach(row => {
    const value = typeof key === 'function' ? key(row) : row[key]
    const unit = stockUnit(row)
    units.set(unit, (units.get(unit) || 0) + n(value))
  })
  const values = [...units].filter(([, value]) => value !== 0)
  return values.length ? values.map(([unit, value]) => `${value} ${unit}`).join(' + ') : '0'
}

export function sourceKind(row) {
  return ({ NORMAL: '普通商品', BUNDLE: '套装组件', STOCKED_BUNDLE: '整套入库', LUCKY_BAG: '福袋内商品' })[row.source_kind] || '实际发货商品'
}

export function dispatchText(document) {
  if (document.cancel_state === 'CONFIRMED') return '剩余已截停'
  return ({ PENDING: '待下发', SENDING: '下发中', ACCEPTED: '仓库已接单', UNKNOWN: '下发结果待确认', FAILED: '下发失败', CANCELED: '已取消' })[document.dispatch_state] || '待下发'
}

export function receiveText(row) {
  const qty = n(row.quantity); const accepted = n(row.accepted_quantity)
  if (accepted >= qty && qty > 0) return '全部实收'
  if (row.cancel_state === 'CONFIRMED' || n(row.waived_quantity) > 0) return accepted ? '部分实收，剩余已停止' : '已停止收货'
  if (row.receipt_state === 'COMPLETED' && accepted < qty) return '收货结束，存在差异'
  return accepted > 0 ? '部分实收' : '待收货'
}

export function postingText(row) {
  const accepted = n(row.accepted_quantity); const posted = n(row.posted_quantity)
  if (accepted > posted) return posted ? '部分入账' : '待入账'
  return accepted > 0 ? '全部入账' : '暂无实收'
}

// Plans can have multiple execution generations. Count the current plan scope once,
// but retain every confirmed receipt; an old stopped ticket is never a new demand.
export function planScopeRows(members) {
  const grouped = new Map()
  members.forEach(member => {
    const key = String(member.plan_id)
    if (!grouped.has(key)) grouped.set(key, { ...member, quantity: 0, accepted_quantity: 0, posted_quantity: 0 })
    const row = grouped.get(key)
    row.quantity = Math.max(row.quantity, n(member.quantity))
    row.accepted_quantity += n(member.accepted_quantity)
    row.posted_quantity += n(member.posted_quantity)
  })
  return [...grouped.values()]
}

export function returnDocuments(plans, parcels, refundId) {
  const result = new Map()
  parcels.forEach(parcel => {
    (parcel.members || []).filter(row => row.return_sn).forEach(row => {
      const sn = row.return_sn; const current = (parcel.warehouse_document || {}).return_sn === sn
      if (!result.has(sn)) result.set(sn, { ...(current ? parcel.warehouse_document : row), return_sn: sn, parcel, shared: true, current, rows: [], ownRows: [] })
      const doc = result.get(sn)
      doc.rows.push(row)
      if (String(row.refund_id) === String(refundId)) doc.ownRows.push(row)
    })
  })
  plans.forEach(plan => {
    const docs = plan.actual ? (plan.actual.documents || [plan.actual]) : []
    docs.forEach((document, index) => {
      if (!document.return_sn || result.has(document.return_sn) && result.get(document.return_sn).shared) return
      const sn = document.return_sn
      if (!result.has(sn)) result.set(sn, { ...document, shared: false, current: index === 0, rows: [], ownRows: [], plan, document })
      const row = { ...plan, ...document, stock_unit: plan.stock_unit }
      result.get(sn).rows.push(row)
      result.get(sn).ownRows.push(row)
    })
  })
  return [...result.values()].filter(doc => doc.ownRows.length).sort((a, b) => Number(b.current) - Number(a.current))
}

// Raw warehouse quantities must be classified by the exact ticket + SKU/source.
// Unknown or conflicting units stay explicit instead of being guessed as pieces.
export function receiptQuantities(parcel, events) {
  return events.flatMap(event => {
    let payload = event.payload || {}
    try { if (typeof payload === 'string') payload = JSON.parse(payload) } catch (_) { payload = {} }
    if (!Array.isArray(payload.lines) || !payload.lines.length) return [{ quantity: n(event.quantity), stock_unit: '库存单位' }]
    return payload.lines.map(line => {
      const candidates = (parcel.members || []).filter(row => row.sku_sn === line.sku && (!payload.ticketSn || row.return_sn === payload.ticketSn) && (!line.lineNo || String(row.id) === String(line.lineNo)))
      const units = new Set(candidates.map(stockUnit))
      return { quantity: n(line.good) + n(line.bad), stock_unit: units.size === 1 ? [...units][0] : '库存单位' }
    })
  })
}
