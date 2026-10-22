export const giftTypes = [
  { value: 'AMOUNT', label: '满额赠', example: '实付满200元，赠G一件' },
  { value: 'PRODUCT', label: '指定商品赠', example: 'A、B合计满2件，赠G一件，仅赠一次' },
  { value: 'MULTIPLE', label: '买多赠多', example: '每满2件A赠G一件，最多3倍' },
  { value: 'TIER', label: '阶梯赠', example: '满2件赠一件，满4件赠三件，取最高档' }
]
export const clone = value => JSON.parse(JSON.stringify(value))
export const newGiftRule = () => ({
  key: 'R' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8),
  name: '', type: 'AMOUNT', purchase_scope: 'ALL', skus: [], amount: 200,
  minimum: 2, cap: 3, max_count: 0, quota_limited: false, gifts: [], tiers: [{ minimum: 2, gifts: [] }], stop: false
})
export function draftFrom(row, copy = false) {
  const form = row ? clone(row) : { name: '', enabled: 0, revision: 0, policy: {
    shipped_policy: 'RETURN', allow_waive: true, difference_affects_amount: false, rules: [newGiftRule()]
  } }
  delete form.quota_usage
  form.policy.rules.forEach(r => { r.purchase_scope = r.type === 'AMOUNT' && !r.skus.length ? 'ALL' : 'SELECTED';r.max_count = r.max_count || 0;r.quota_limited = r.max_count > 0;delete r.quota_tracked })
  if (copy) { delete form.id; form.revision = 0; form.enabled = 0; form.name = form.name.slice(0, 94) + '（副本）' }
  return form
}
export function strategyPayload(form, period) {
  const result = clone(form)
  result.policy.start_time = period && period[0]
  result.policy.end_time = period && period[1]
  result.policy.rules.forEach(r => {
    if (r.purchase_scope === 'ALL' && r.type === 'AMOUNT') r.skus = []
    if (r.type === 'TIER') r.gifts = clone((r.tiers[0] || {}).gifts || [])
    delete r.purchase_scope
    delete r.quota_tracked
    delete r.quota_limited
  })
  return result
}
export function ruleSummary(r) {
  const scope = r.purchase_scope === 'ALL' && r.type === 'AMOUNT' ? '全部购买商品' : (r.skus || []).length + '种指定商品'
  const gifts = values => (values || []).map(g => (g.goods_name || g.sku_sn) + ' × ' + g.quantity + (g.sales_unit || '件')).join('、') || '待选择赠品'
  let condition = ''
  if (r.type === 'AMOUNT') condition = scope + '实付满' + r.amount + '元 → ' + gifts(r.gifts)
  if (r.type === 'PRODUCT') condition = scope + '合计满' + r.minimum + '件 → ' + gifts(r.gifts) + '，仅赠一次'
  if (r.type === 'MULTIPLE') condition = scope + '每满' + r.minimum + '件 → ' + gifts(r.gifts) + '，最多' + r.cap + '倍'
  if (r.type === 'TIER') condition = scope + '：' + (r.tiers || []).map(t => '满' + t.minimum + '件赠' + gifts(t.gifts)).join('；') + '，取最高档'
  return condition + (r.max_count ? '；活动累计最多赠送' + r.max_count + '次' : '') + '；实际赠送后' + (r.stop ? '结束本组' : '继续下一条规则')
}
const integer = (v, max) => Number.isSafeInteger(Number(v)) && Number(v) >= 1 && Number(v) <= max
export function draftIssues(form, period) {
  const issues = [], all = new Set()
  const add = (field, message, key = '') => issues.push({ field, message, key })
  if (!form.name.trim() || form.name.length > 100) add('name', '请填写1至100字的策略组名称')
  if (!period || period.length !== 2 || !period[0] || !period[1] || period[0] >= period[1]) add('period', '请选择有效的付款时间范围，结束时间须晚于开始时间')
  const rules = form.policy.rules
  if (!rules.length || rules.length > 30) add('rules', '请配置1至30条规则')
  rules.forEach(r => {
    if (!r.name.trim() || r.name.length > 100) add('name', '请填写1至100字的规则名称', r.key)
    if (!(r.type === 'AMOUNT' && r.purchase_scope === 'ALL')) {
      if (!r.skus.length || r.skus.length > 100) add('skus', '请选择1至100种购买商品', r.key)
      r.skus.forEach(sku => all.add(sku))
    }
    if (r.type === 'AMOUNT' && (!Number.isFinite(Number(r.amount)) || Number(r.amount) < 0 || Number(r.amount) > 1e9 || !/^\d+(\.\d{1,2})?$/.test(String(r.amount)))) add('amount', '请输入有效的满额门槛，最多两位小数', r.key)
    if (['PRODUCT', 'MULTIPLE'].includes(r.type) && !integer(r.minimum, 1e8)) add('minimum', '购买数量须为1至一亿的整数', r.key)
    if (r.type === 'MULTIPLE' && !integer(r.cap, 100000)) add('cap', '倍赠上限须为1至十万的整数', r.key)
    if (!Number.isSafeInteger(Number(r.max_count)) || Number(r.max_count) < 0 || Number(r.max_count) > 1e9) add('max_count', '活动累计赠送上限须为0至十亿的整数，0表示不限', r.key)
    else if (r.quota_limited && !integer(r.max_count, 1e9)) add('max_count', '限制次数时，请填写1至十亿的整数', r.key)
    const sets = r.type === 'TIER' ? r.tiers.map(t => t.gifts) : [r.gifts]
    if (r.type === 'TIER') {
      if (!r.tiers.length || r.tiers.length > 20 || r.tiers.some((t, i) => !integer(t.minimum, 1e8) || (i && Number(t.minimum) <= Number(r.tiers[i - 1].minimum)))) add('tiers', '请配置1至20个阶梯，门槛须按数量递增且不重复', r.key)
    }
    if (!sets.length || sets.some(set => !set.length || set.length > 20 || new Set(set.map(g => g.sku_sn)).size !== set.length || set.some(g => !g.sku_sn || !integer(g.quantity, 100000)))) add('gifts', '每条规则或阶梯请选择1至20种赠品，赠送数量须为正整数', r.key)
    sets.forEach(set => set.forEach(g => all.add(g.sku_sn)))
  })
  if (all.size > 200) add('rules', '一个策略组最多涉及200种购买或赠品SKU')
  return issues
}
