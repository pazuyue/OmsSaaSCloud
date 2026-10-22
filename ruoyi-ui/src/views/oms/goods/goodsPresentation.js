export const productTypes = [{ value: 'NORMAL', label: '普通商品' }, { value: 'BUNDLE', label: '固定套装' }, { value: 'LUCKY_BAG', label: '福袋' }]
export function productType(row) { return row.productType || (Number(row.isFd) === 1 ? 'LUCKY_BAG' : Number(row.isPackage) === 1 ? 'BUNDLE' : 'NORMAL') }
export function productLabel(row) { return productTypes.find(type => type.value === productType(row)).label }
export function productUnit(row) { return { NORMAL: '件', BUNDLE: '套', LUCKY_BAG: '袋' }[productType(row)] }
export function composition(row) {
  const type = productType(row)
  if (type === 'NORMAL') return { label: '独立商品库存', state: '无需配置组成', tone: 'info', ready: true }
  const revision = Number(row.compositionRevision || 0), ready = revision > 0 && Number(row.compositionEnabled) === 1
  const label = type === 'BUNDLE' ? row.inventoryMode === 'STOCKED' ? '按整套成品库存' : row.inventoryMode === 'VIRTUAL' ? '按组成商品库存' : '库存方式待配置' : row.luckyMode === 'PRESET' ? '先生成组合，再按袋领取' : row.luckyMode === 'RANDOM' ? '下单时确定内容' : '配货方式待配置'
  return { label, ready, tone: ready ? 'success' : 'warning', state: !revision ? '待配置并发布' : ready ? `已发布 · 版本 ${revision}` : `已停用 · 版本 ${revision}` }
}
export function giftEligibility(row) { return Number(row.isGift) !== 1 ? '未开启' : composition(row).ready ? '允许作为赠品' : '已开启 · 待发布配置' }
export function productPayload(form) {
  const payload = { ...form }
  payload.isFd = form.productType === 'LUCKY_BAG' ? 1 : 0
  payload.isPackage = form.productType === 'BUNDLE' ? 1 : 0
  return payload
}
