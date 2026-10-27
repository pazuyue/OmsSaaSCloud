export const views = [
  { value: 'TASK', label: '退货任务', unit: '项任务', hint: '按实际商品退回任务查询，包含尚未生成仓库入库单的安排。' },
  { value: 'PARCEL', label: '退回包裹', unit: '个包裹', hint: '按已关联包裹查询合包归属；命中任一来源，仍展示整个包裹数量。' },
  { value: 'DOCUMENT', label: '退货入库单', unit: '张入库单', hint: '按实际仓库入库单查询；共同单只展示一行，原来源台账在明细追溯。' }
]
export const buckets = [{ value: '', label: '全部' }, { value: 'WAIT_LOGISTICS', label: '待寄回' }, { value: 'WAIT_DISPATCH', label: '待下发' }, { value: 'WAIT_RECEIVE', label: '待收货' }, { value: 'WAIT_POST', label: '待入库' }, { value: 'NEED_CHECK', label: '待核对' }, { value: 'DONE', label: '已完成' }, { value: 'STOPPED', label: '已终止' }]
export const stageLabels = { WAIT_LOGISTICS: '待取得退回物流', COLLECT: '归集中', WAIT_DISPATCH: '待建立或下发仓单', WAIT_RECEIVE: '待仓库收货', PARTIAL: '部分实收', WAIT_POST: '已收待入库', NEED_CHECK: '待核对', DONE: '退货已结案', STOPPED: '剩余安排已终止' }
export function quantityText(units, field) {
  const known = (units || []).filter(u => Number(u[field] || 0) > 0).map(u => `${u[field]} ${u.unit}`)
  const unknown = (units || []).some(u => Number(u.unknown || 0) > 0)
  if (field === 'quantity' && unknown) known.push('另有数量待确认')
  return known.join(' + ') || '0'
}
export function sourceText(row) {
  const parts = []
  if (Number(row.refund_count)) parts.push(`退款 ${row.refund_count} 单`)
  if (Number(row.exchange_count)) parts.push(`换货 ${row.exchange_count} 单`)
  return parts.join(' + ')
}
export function nextStep(row) {
  if (row.incomplete) return '合包来源正在同步，请稍后刷新完整归属。'
  if (row.message && row.stage !== 'DONE') return row.message
  return ({ WAIT_LOGISTICS: '等待买家寄回并取得有效退回运单。', COLLECT: `归集至 ${String(row.dispatch_after || '').replace('T', ' ')}，到期按规则统一下发。`, WAIT_DISPATCH: '等待自动流程建立或下发原退回安排。', WAIT_RECEIVE: '仓库已接单，等待实际收货回传。', PARTIAL: '等待剩余商品实收；已确认实收和入库分别保留。', WAIT_POST: '实收已明确，等待库存入账。', NEED_CHECK: '核对来源、仓库回传或原下发结果。', DONE: '本范围退货已结案；退款资金和换出发货在来源售后中查看。', STOPPED: '原剩余安排已终止，已发生实收和库存记录保留。' })[row.stage] || '查看来源售后核对当前进度。'
}
export function sourceLink(member) { return member.owner_kind === 'EXCHANGE' ? { path: '/oms-order/exchanges', query: { exchangeId: member.owner_id }} : { path: '/oms-order/businessRefunds', query: { id: member.owner_id }} }
export function csvCell(value) { const text = String(value == null ? '' : value); return '"' + (/^[\s]*[=+\-@]/.test(text) ? "'" : '') + text.replace(/"/g, '""') + '"' }
