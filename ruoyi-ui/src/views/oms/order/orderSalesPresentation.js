import { lineAmounts } from './orderAmounts'
const n = value => Number(value || 0)
const same = (a, b) => String(a) === String(b)
export const activeQuantity = line => Math.max(0, n(line.quantity) - n(line.cancelled_quantity))
export const productSpec = line => [line.current_goods && line.current_goods.color_name, line.current_goods && line.current_goods.size_name].filter(Boolean).join(' / ')
export const physicalUnit = line => line.stocked_bundle_snapshot ? '套' : '件'
export function stockedRecipe(line) {
  if (!line.stocked_bundle_snapshot) return null
  try { return typeof line.stocked_bundle_snapshot === 'string' ? JSON.parse(line.stocked_bundle_snapshot) : line.stocked_bundle_snapshot } catch (_) { return { invalid: true, components: [] } }
}

export function salesRows(order) {
  const lines = (order.lines || []).filter(line => !n(line.gift_plan_id))
  const groups = new Map(), bundleOids = new Set(), amounts = lineAmounts(order)
  for (const b of (order.bundles || []).filter(parent => !n(parent.gift_plan_id))) {
    bundleOids.add(String(b.platform_oid))
    groups.set(String(b.platform_oid), { key: `bundle-${b.id}`, kind: b.business_type === 'LUCKY_BAG' ? 'LUCKY_BAG' : 'VIRTUAL_BUNDLE',
      source: b, name: b.goods_name, sku: b.sku_sn, oid: String(b.platform_oid), quantity: n(b.quantity), payment: b.payment, lines: [], units: b.units || [] })
  }
  for (const line of lines) {
    const key = String(line.platform_oid)
    if (!groups.has(key)) groups.set(key, { key: `sale-${key}`, oid: key, lines: [] })
    groups.get(key).lines.push(line)
  }
  return [...groups.values()].map(row => {
    const original = row.lines.find(l => !l.exchange_id) || row.lines[0]
    if (!bundleOids.has(row.oid)) {
      row.source = original; row.recipe = stockedRecipe(original)
      row.kind = row.recipe ? 'STOCKED_BUNDLE' : 'NORMAL'; row.name = row.recipe && row.recipe.goods_name || original.oms_goods_name || original.goods_name; row.sku = original.sku_sn
      row.quantity = n(original.quantity); row.payment = original.payment; row.units = []
    }
    row.unit = row.kind === 'LUCKY_BAG' ? '袋' : row.kind === 'NORMAL' ? '件' : '套'
    row.type_label = { LUCKY_BAG: '福袋', VIRTUAL_BUNDLE: '虚拟组合单品', STOCKED_BUNDLE: '实物组合单品', NORMAL: '普通商品' }[row.kind]
    row.spec = original && !bundleOids.has(row.oid) ? productSpec(original) : ''
    row.active_lines = row.lines.filter(l => activeQuantity(l) > 0)
    row.physical_quantity = row.active_lines.reduce((sum, l) => sum + activeQuantity(l), 0)
    row.shipped_physical_quantity = row.lines.reduce((sum, l) => sum + n(l.shipped_quantity), 0)
    row.shipped = bundleOids.has(row.oid) ? n(row.source.shipped_sets) : row.shipped_physical_quantity
    row.has_replacement = row.lines.some(l => !!l.exchange_id)
    row.has_canceled_components = row.lines.some(l => n(l.cancelled_quantity) > 0)
    const composite = ['LUCKY_BAG', 'VIRTUAL_BUNDLE'].includes(row.kind)
    row.canceled = order.status === 'VOIDED' || (!row.physical_quantity && !row.shipped) ? row.quantity : composite ? row.has_canceled_components ? null : 0 : Math.max(0, row.quantity - row.physical_quantity)
    row.pending = Math.max(0, row.quantity - row.shipped - n(row.canceled))
    row.progress = order.status === 'VOIDED' ? '已作废' : row.canceled === row.quantity ? '已终止' : row.shipped >= row.quantity - n(row.canceled) ? '已发货' : row.shipped > 0 ? '部分发货' : '待发货'
    row.progress_tone = row.progress === '已发货' ? 'success' : row.progress === '部分发货' ? 'warning' : 'info'
    row.return_units = row.units.filter(u => !!u.return_refund_id)
    row.returns = purchaseReturnProgress(order, row)
    row.money = amounts[row.oid] || {}
    return row
  })
}

export function purchaseReturnProgress(order, row) {
  const lineIds = new Set(row.lines.map(line => String(line.id)))
  const plans = (order.purchase_returns || []).filter(plan => same(plan.platform_oid, row.oid) && lineIds.has(String(plan.order_line_id)))
  const counts = {}, refunds = new Map()
  for (const plan of plans) {
    const unit = plan.stock_unit, totals = counts[unit] || (counts[unit] = { posted: 0, unposted: 0, pending: 0, waived: 0, stopped: 0 })
    totals.posted += n(plan.posted_quantity)
    totals.unposted += Math.max(0, n(plan.accepted_quantity) - n(plan.posted_quantity))
    totals.waived += n(plan.waived_quantity)
    const left = Math.max(0, n(plan.quantity) - n(plan.accepted_quantity) - n(plan.waived_quantity))
    totals[['STOPPED', 'CLOSED'].includes(plan.state) ? 'stopped' : 'pending'] += left
    refunds.set(String(plan.refund_id), { id: plan.refund_id, sn: plan.refund_sn })
  }
  const text = key => Object.keys(counts).filter(unit => counts[unit][key]).map(unit => `${counts[unit][key]} ${unit}`).join('、')
  const review = plans.some(plan => plan.state === 'WAIT_REVIEW')
  const posted = text('posted'), unposted = text('unposted'), pending = text('pending'), waived = text('waived'), stopped = text('stopped')
  const title = review ? '退回待核对' : posted ? `已入库 ${posted}` : unposted ? `待入账 ${unposted}` : pending ? `待退回 ${pending}` : waived ? '已结束追退' : stopped ? '已停止安排' : ''
  const details = [review && posted && `已入库 ${posted}`, posted && unposted && `待入账 ${unposted}`, (posted || unposted || review) && pending && `待退回 ${pending}`, waived && `不再追回 ${waived}`, stopped && `已停止 ${stopped}`].filter(Boolean)
  return { title, details, refunds: [...refunds.values()], pending_review: review }
}

export function salesSummary(rows) {
  const quantities = { '袋': 0, '套': 0, '件': 0 }
  let components = 0, cents = 0
  for (const row of rows) {
    quantities[row.unit] += row.quantity
    if (['LUCKY_BAG', 'VIRTUAL_BUNDLE'].includes(row.kind)) components += row.physical_quantity
    cents += Math.round(n(row.payment) * 100)
  }
  return { quantity_text: Object.keys(quantities).filter(unit => quantities[unit]).map(unit => `${quantities[unit]}${unit}`).join('、') || '0件', components, payment: (cents / 100).toFixed(2) }
}

export function salesOrigin(line, bundles) {
  const parent = (bundles || []).find(b => same(b.id, line.bundle_id))
  if (!parent) return null
  const lucky = parent.business_type === 'LUCKY_BAG'
  const units = lucky ? (parent.units || []).map(unit => ({ unit: unit.unit, unit_id: unit.unit_id,
    quantity: (unit.components || []).filter(c => c.sku_sn === line.sku_sn).reduce((sum, c) => sum + n(c.quantity), 0) })).filter(u => u.quantity > 0) : []
  return { lucky, name: parent.goods_name, sku: parent.sku_sn, quantity: parent.quantity, units }
}

export function warehouseName(order, code) {
  const warehouse = (order.warehouse_allocations || []).find(w => w.store_code === code) || (order.shipments || []).find(w => w.store_code === code)
  return warehouse ? warehouse.store_name || warehouse.real_store_name || code : code
}

export function physicalSources(order, line) {
  return (order.warehouse_allocations || []).filter(r => String(r.platform_oid) === String(line.platform_oid) && r.sku_sn === line.sku_sn && r.platform_tid === (line.stock_reference || order.platform_tid))
}
