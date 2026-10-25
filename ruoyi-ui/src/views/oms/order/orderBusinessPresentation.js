export const businessSettingsPath = '/oms-order/businessSettings'
export const syncTasksPath = '/oms-order/syncTasks'
export const sectionGroups = [
  { label: '配置概览', items: [{ key: 'overview', label: '店铺概览' }] },
  { label: '平台与数据接入', items: [{ key: 'platform', label: '平台授权与能力', permissions: ['channel:platform:query', 'order:refund:config', 'order:exchange:config'] }, { key: 'order-intake', label: '订单接入', permissions: ['order:pull:config'] }, { key: 'refund-intake', label: '退款接入', permissions: ['order:refund:config'] }, { key: 'exchange-intake', label: '换货接入', permissions: ['order:exchange:config'] }] },
  { label: '订单处理', items: [{ key: 'conversion', label: '转单与预占', permissions: ['order:pull:config'] }, { key: 'review', label: '审单规则', permissions: ['order:pull:config'] }, { key: 'allocation', label: '分仓规则', permissions: ['order:pull:config'] }, { key: 'gifts', label: '适用赠品策略', permissions: ['order:gift:list'] }] },
  { label: '发货履约', items: [{ key: 'carriers', label: '快递匹配', permissions: ['order:pull:config'] }, { key: 'warehouse', label: '仓库处理方式' }, { key: 'shipping', label: '平台发货回传', permissions: ['order:shipment:platformConfig'] }] },
  { label: '售后处理', items: [{ key: 'refund-automation', label: '退款自动处理', permissions: ['order:refund:config'] }, { key: 'refund-returns', label: '退货与入库', permissions: ['order:refund:config'] }, { key: 'exchange', label: '换货处理', permissions: ['order:exchange:config'] }] },
  { label: '运行检查', items: [{ key: 'runtime', label: '执行状态与检查' }] }
]
export function permittedSections(permissions) { const can = p => permissions.includes('*:*:*') || permissions.includes(p); return sectionGroups.map(g => ({ ...g, items: g.items.filter(s => !s.permissions || s.permissions.some(can)) })).filter(g => g.items.length) }
export function settingsLocation(section = 'overview', channelId, extra = {}) { return { path: businessSettingsPath, query: { ...extra, ...(channelId ? { channelId: String(channelId) } : {}), section }} }
export function legacySettingsLocation(route) {
  const q = { ...route.query }; const channel = q.channelId; let section = 'overview'
  if (route.path.includes('afterSaleRules') || route.path.endsWith('refundSettings')) section = ({ intake: 'refund-intake', authorization: 'platform', automation: 'refund-automation', returns: 'refund-returns' })[q.tab] || (route.path.endsWith('returns') ? 'refund-returns' : 'refund-intake')
  else if (q.tab === 'sources') { delete q.tab; return { path: businessSettingsPath, query: { ...q, scope: 'sources' }} } else section = ({ review: 'review', allocation: 'allocation', 'carrier-rules': 'carriers', conversion: 'conversion' })[q.tab] || 'conversion'
  if (q.tab === 'carriers') return { path: businessSettingsPath, query: { scope: 'carriers-base' }}
  delete q.tab; return settingsLocation(section, channel, q)
}
const on = v => Number(v) === 1 || v === true
export function businessFlow(row) {
  const order = row.order || {}; const review = (row.review || {}).policy || {}; const allocation = (row.allocation || {}).policy || {}; const carriers = (row.carriers || {}).policy || {}
  const flows = []
  if (Object.prototype.hasOwnProperty.call(row, 'order')) flows.push(on(order.enabled) ? '自动接收订单' : '订单自动同步关闭', on(order.auto_convert) ? '自动转业务订单' : '人工转单', on(order.allow_public_stock) ? '渠道库存优先，允许公共库存补占' : '仅渠道库存预占', review.mode === 'AUTO' ? (review.stock_condition === 'FULL_RESERVED' ? '库存满足后按规则自动审单' : '按规则自动审单') : '人工审单', on(allocation.enabled) ? allocation.execution_mode === 'AUTO' ? '自动分仓' : '人工分仓' : '分仓未开启', on(carriers.enabled) ? '自动匹配快递' : '人工选择快递')
  flows.push('人工确认下发仓库', '仓库回传出库事实', !row.shipping ? '平台回传按权限查看' : row.shipping.mode === 'SIMULATION' ? '模拟回传平台' : '平台发货回传关闭')
  return !on(row.shop_enabled) ? ['店铺已停用，自动流程暂停', ...flows] : flows
}
export function sectionSummaries(row) {
  const order = row.order || {}; const review = (row.review || {}).policy || {}; const allocation = (row.allocation || {}).policy || {}; const flow = row.refund_flow || {}; const ret = (row.returns || {}).policy || {}
  const rows = []
  if (row.order) rows.push({ section: 'order-intake', label: '订单接入', value: order.revision ? on(order.enabled) ? '自动同步已开启' : '自动同步关闭' : '尚未配置' }, { section: 'conversion', label: '转单与预占', value: `${on(order.auto_convert) ? '自动转单' : '人工转单'} · ${on(order.allow_public_stock) ? '允许公共库存补占' : '仅渠道库存'}` }, { section: 'review', label: '审单规则', value: review.mode === 'AUTO' ? '按规则自动审核' : '人工审核' }, { section: 'allocation', label: '分仓规则', value: on(allocation.enabled) ? allocation.execution_mode === 'AUTO' ? '自动分仓' : '人工分仓' : '未开启' }, { section: 'carriers', label: '快递匹配', value: on(((row.carriers || {}).policy || {}).enabled) ? '自动匹配' : '人工匹配' })
  if (row.shipping) rows.push({ section: 'shipping', label: '平台发货回传', value: row.shipping.mode === 'SIMULATION' ? '模拟回传，真实接口未启用' : '关闭；当前仅支持模拟模式' })
  if (row.refund) rows.push({ section: 'refund-intake', label: '退款接入', value: `JDP ${on(row.refund.enabled) ? '开启' : '关闭'} · 接口补充 ${on((row.refund_api || {}).enabled) ? '开启' : '关闭'}` }, { section: 'refund-automation', label: '退款自动处理', value: on(flow.enabled) ? '已开启，按单据条件执行' : '关闭' }, { section: 'refund-returns', label: '退货与入库', value: `${on(ret.auto_create) ? '自动创建' : '人工创建'} · ${on(ret.auto_enabled) ? '自动合包' : on(ret.enabled) ? '人工合包' : '不合包'} · ${on(ret.auto_dispatch) ? '自动下发' : '人工下发'}` })
  if (row.exchange) rows.push({ section: 'exchange-intake', label: '换货接入', value: on(row.exchange.enabled) ? '自动同步已开启' : '自动同步关闭' }, { section: 'exchange', label: '换货处理', value: on((row.exchange_flow||{}).enabled) ? '自动流程开启 · 按节点授权' : '自动流程关闭' })
  return rows
}
