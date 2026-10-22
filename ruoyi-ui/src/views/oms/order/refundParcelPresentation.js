const number = value => Number(value || 0)
function sourceUnit(sources) {
  const units = new Set(sources.map(row => row.stock_unit || '库存单位'))
  return units.size === 1 ? [...units][0] : '库存单位'
}
export function receiptPayload(receipt) {
  try { return typeof receipt.payload === 'string' ? JSON.parse(receipt.payload) : (receipt.payload || {}) } catch (_) { return {} }
}
export function parcelScope(parcel, refundId) {
  const members = (parcel.members || []).filter(m => refundId == null || String(m.refund_id) === String(refundId))
  const plans = new Map()
  members.forEach(m => plans.set(String(m.plan_id), Math.max(plans.get(String(m.plan_id)) || 0, number(m.quantity))))
  return {
    planned: [...plans.values()].reduce((a, b) => a + b, 0),
    accepted: members.reduce((n, m) => n + number(m.accepted_quantity), 0),
    posted: members.reduce((n, m) => n + number(m.posted_quantity), 0)
  }
}
export function parcelPending(parcel) {
  return (parcel.receipts || []).filter(e => e.state === 'WAIT_ALLOCATION').reduce((n, e) => n + number(e.quantity), 0)
}
export function eligibleSources(parcel, receipt, line) {
  const raw = receiptPayload(receipt)
  return (parcel.members || []).filter(m => m.task_id && m.sku_sn === line.sku &&
    (!line.lineNo || String(line.lineNo) === String(m.id)) &&
    (!raw.ticketSn || raw.ticketSn === m.return_sn) && number(m.quantity) > number(m.accepted_quantity))
}
export function sourcePending(parcel, member) {
  return (parcel.receipts || []).some(e => {
    if (e.state !== 'WAIT_ALLOCATION') return false
    const raw = receiptPayload(e)
    return (!raw.ticketSn || raw.ticketSn === member.return_sn) && (raw.lines || []).some(line =>
      line.sku === member.sku_sn && (line.lineNo ? String(line.lineNo) === String(member.id) : number(member.quantity) > number(member.accepted_quantity)))
  })
}
export function scopePending(parcel, refundId) {
  return (parcel.members || []).some(m => String(m.refund_id) === String(refundId) && sourcePending(parcel, m))
}
export function receiptText(accepted, pending, unit = '件') {
  if (pending) return number(accepted) ? `已确认 ${accepted} ${unit}，另有待分配` : `待确定（已确认 0 ${unit}）`
  return `已确认 ${number(accepted)} ${unit}`
}
export function allocationReason(parcel, receipt) {
  const audit = allocationMetadata(receipt)
  if (audit.reason) return audit.reason
  const raw = receiptPayload(receipt)
  const doc = parcel.warehouse_document || {}
  if (doc.cancel_state && doc.cancel_state !== 'NONE') return '涉及截单或迟到回传，请先核对原THK、实物与后续安排，再确认实收分配。'
  for (const line of raw.lines || []) {
    const sources = eligibleSources(parcel, receipt, line)
    const available = sources.reduce((n, m) => n + number(m.quantity) - number(m.accepted_quantity), 0)
    const received = number(line.good) + number(line.bad)
    if (!line.lineNo && sources.length > 1 && received < available) return `仓库回传同一SKU ${received} ${sourceUnit(sources)}，未提供来源明细行号；该SKU合计待收 ${available} ${sourceUnit(sources)}，无法确定各退款单的实收数量，暂停自动分配。`
    if (number(line.good) && number(line.bad) && sources.length > 1) return '同一SKU同时回传良品和次品，且存在多个退款来源；需确认各退款单分别收到的品质与数量。'
  }
  return '本次回传未满足自动分配条件。请核对原THK明细行、商品、品质和数量，明确各退款来源；未分配部分未入账。'
}
export function allocationError(parcel, receipt, allocations) {
  const lines = receiptPayload(receipt).lines || []
  if (!lines.length) return '未读取到仓库原始明细，请刷新核对'
  const used = new Map()
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index], rows = allocations.filter(a => a.line_index === index)
    const good = rows.reduce((n, a) => n + number(a.good_qty), 0), bad = rows.reduce((n, a) => n + number(a.bad_qty), 0)
    const unit = sourceUnit(eligibleSources(parcel, receipt, line))
    if (good !== number(line.good) || bad !== number(line.bad)) return `原回传第 ${index + 1} 行需分配良品 ${number(line.good)} ${unit}、次品 ${number(line.bad)} ${unit}；当前分配 ${good} / ${bad} ${unit}`
    for (const row of rows) {
      const qty = number(row.good_qty) + number(row.bad_qty)
      if (![number(row.good_qty), number(row.bad_qty)].every(n => Number.isInteger(n) && n >= 0)) return '分配数量必须是非负整数'
      if (!qty) continue
      const source = eligibleSources(parcel, receipt, line).find(m => String(m.task_id) === String(row.task_id))
      if (!source) return '请选择属于原THK明细的退款来源'
      const total = (used.get(String(row.task_id)) || 0) + qty
      if (total > number(source.quantity) - number(source.accepted_quantity)) return '分配数量超过所选退款来源的剩余待收数量'
      used.set(String(row.task_id), total)
    }
  }
  return ''
}

export function allocationMetadata(receipt) {
  try { return typeof receipt.allocation_metadata === 'string' ? JSON.parse(receipt.allocation_metadata) || {} : receipt.allocation_metadata || {} } catch (_) { return {} }
}
export function allocationOutcome(parcel, planId) {
  const events = (parcel.receipts || []).filter(e => e.state === 'ALLOCATED' && allocationMetadata(e).method === 'RULE_PARTIAL').slice().sort((a,b) => number(b.id)-number(a.id))
  for (const event of events) {
    const audit = allocationMetadata(event)
    const row = (audit.rows || []).find(row => String(row.plan_id) === String(planId))
    if (row) { const unit = sourceUnit((parcel.members || []).filter(m => String(m.plan_id) === String(planId))); return `最近一次按规则分配 ${number(row.assigned)} ${unit}${number(row.assigned) === 0 ? '（本次未分配到实收）' : ''} · 分配后待退回 ${number(row.remaining)} ${unit} · 规则版本 ${audit.policy_revision}` }
  }
  return ''
}
