export function mergeMode(rule = {}) {
  return Number(rule.enabled) !== 1 ? 'NONE' : Number(rule.auto_enabled) === 1 ? 'AUTO' : 'MANUAL'
}
export function applyMergeMode(rule, mode) {
  rule.merge_mode = mode
  rule.enabled = mode === 'NONE' ? 0 : 1
  rule.manual_enabled = mode === 'NONE' ? 0 : 1
  rule.auto_enabled = mode === 'AUTO' ? 1 : 0
  rule.allow_append = 0
  rule.auto_append = 0
}
export function dispatchStores(rule = {}) {
  return (rule.store_codes || []).length ? rule.store_codes : rule.create_store_code ? [rule.create_store_code] : []
}
export function ruleFlow(rule = {}) {
  const mode = mergeMode(rule)
  return [Number(rule.auto_create) ? '审核后自动创建' : '人工创建安排', mode === 'NONE' ? '不合包' : mode === 'AUTO' ? '自动归集' : '人工确认合包', Number(rule.auto_dispatch) ? '自动下发仓库' : '人工下发仓库', rule.allocation_mode === 'MANUAL' ? '实收人工确认' : rule.allocation_mode === 'RULE' ? '实收按规则分配' : '实收严格匹配']
}
export function ruleValidation(rule, warehouses) {
  const error = (tab, field, message) => ({ tab, field, message })
  if (Number(rule.auto_create)) {
    for (const [field, name] of [['create_store_code', '默认退回仓'], ['create_return_address', '退回地址'], ['create_return_contact', '退回联系人']]) {
      if (!String(rule[field] || '').trim()) return error('arrangements', field, `请填写${name}`)
    }
  }
  const stores = dispatchStores(rule)
  if ((Number(rule.auto_dispatch) || Number(rule.auto_enabled)) && !stores.length) return error(Number(rule.auto_dispatch) ? 'dispatch' : 'merge', 'store_codes', '请选择适用退回仓，或填写默认退回仓')
  if (Number(rule.auto_create) && Number(rule.auto_dispatch) && !stores.includes(rule.create_store_code)) return error('dispatch', 'store_codes', '默认退回仓须纳入自动下发范围')
  if (Number(rule.auto_enabled) && !(rule.logistics_sources || []).length) return error('merge', 'logistics_sources', '请选择可信退回物流来源')
  const active = new Set(Number(rule.auto_dispatch) || Number(rule.auto_enabled) ? stores : [])
  if (Number(rule.auto_create)) active.add(rule.create_store_code)
  for (const code of active) {
    const warehouse = warehouses.find(row => row.store_code === code)
    if (!warehouse || !Number(warehouse.usable)) return error(Number(rule.auto_create) && code === rule.create_store_code ? 'arrangements' : 'dispatch', 'warehouses', '适用退回仓不可用，请核对仓库能力或调整自动处理范围')
  }
  return null
}
export function autoDispatchText(plan = {}) {
  if (plan.actual && plan.actual.dispatch_state === 'ACCEPTED') return '仓库已接收'
  return ({ NONE: '按店铺规则处理', WAIT_REVIEW: '待审核', WAIT_ASSOCIATION: '待确认合包', WAIT_LOGISTICS: '待补物流', COLLECTING: '合包归集中', READY: '待下发', SENT: '已下发', RETRY: '下发稍后重试', FAILED: '下发失败', MANUAL: '待人工核对', HELD: '人工停止', WAIT_SHOP: '店铺不可用' })[plan.auto_dispatch_state] || '按店铺规则处理'
}
