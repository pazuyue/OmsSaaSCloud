export const emptyLuckyBag = () => ({ enabled: 1, mode: 'RANDOM', selection: 'WEIGHTED', max_cost: 0, preset_quantity: 0, groups: [] })
export const candidateSkus = form => (form.groups || []).flatMap(g => (g.candidates || []).map(c => c.sku_sn))
export const luckySummary = form => {
  const groups = form.groups || []
  return groups.length ? `每袋 ${groups.length} 件：${groups.map(g => `${g.name || '未命名分组'} 1件`).join('、')}。同袋同仓，内容确定后保持不变。` : '添加分组，确定每袋包含哪些类型的商品。'
}
export function luckyQuotaSummary(form, group) {
  const batch = form.preset_quantity, candidates = group.candidates || []
  const fixedCandidates = candidates.filter(c => c.fixed_quantity != null)
  const flexible = candidates.length - fixedCandidates.length
  const fixed = fixedCandidates.reduce((sum, c) => sum + (Number.isInteger(c.fixed_quantity) ? c.fixed_quantity : 0), 0)
  const remaining = batch - fixed
  let issue = ''
  if (!Number.isInteger(batch) || batch < 1 || batch > 5000) issue = '请先设置每批生成袋数（1至5000袋），再分配本组商品。'
  else if (!candidates.length) issue = '请至少选择一个候选商品。'
  else if (fixedCandidates.some(c => !Number.isInteger(c.fixed_quantity) || c.fixed_quantity < 0 || c.fixed_quantity > 5000)) issue = '固定袋数须为1至5000的整数；无需固定数量时请选择“按规则分配剩余”。'
  else if (fixed > batch) issue = `固定袋数合计${fixed}袋，超过每批${batch}袋，请减少${fixed - batch}袋。`
  else if (!flexible && remaining > 0) issue = fixed === 0
    ? `所有候选商品都设为“不选”，每批${batch}袋无法选出本组商品。请将至少一个商品改为“按规则分配剩余”，或设置固定袋数合计${batch}袋。`
    : `已固定${fixed}袋，还有${remaining}袋没有商品可选。请将至少一个商品改为“按规则分配剩余”，或将固定袋数补足到${batch}袋。`
  return { batch, fixed, flexible, remaining, issue, title: issue ? '配额设置有误，尚未检查库存' : '配额设置通过，尚未检查库存', message: issue || (remaining > 0 ? `剩余${remaining}袋由${flexible}种候选商品按商品选择规则分配；是否有足够库存，请到“试算与发布”检查。` : `每批${batch}袋已全部由固定配额覆盖；是否有足够库存，请到“试算与发布”检查。`) }
}
export function luckyValidation(form) {
  if (!form.groups.length || form.groups.length > 10) return { tab: 'contents', message: '请配置1至10个商品分组' }
  if (form.groups.some(g => !g.name.trim() || !g.candidates.length)) return { tab: 'contents', message: '每组填写名称，并至少选择一个候选商品' }
  const skus = candidateSkus(form)
  if (skus.length > 200 || new Set(skus).size !== skus.length) return { tab: 'contents', message: '候选SKU不能重复或跨组重叠，合计最多200种' }
  if (form.groups.some(g => g.candidates.some(c => !Number.isInteger(c.weight) || c.weight < 1 || c.weight > 10000))) return { tab: 'contents', message: '候选权重须为1至10000的整数' }
  if (form.mode === 'PRESET') {
    if (!Number.isInteger(form.preset_quantity) || form.preset_quantity < 1 || form.preset_quantity > 5000) return { tab: 'contents', message: '请在内容规则中设置每批生成袋数（1至5000袋）' }
    for (const g of form.groups) {
      const quota = luckyQuotaSummary(form, g)
      if (quota.issue) return { tab: 'contents', message: `${g.name}：${quota.issue}` }
    }
  } else if (Number(form.max_cost) > 0 || form.groups.some(g => g.candidates.some(c => c.fixed_quantity !== null && c.fixed_quantity !== undefined))) return { tab: 'stock', message: '每批固定袋数和每袋成本上限需要选择“分货时预先确定内容”' }
  return null
}

// Recalculate demand locally; the warehouse snapshot does not depend on quotas.
export function luckyStockPrecheck(form, warehouse, quantity) {
  if (!warehouse || warehouse.code !== 'CHECKED') return null
  const stock = new Map(warehouse.components.map(c => [c.sku_sn, c]))
  const configurationIssue = luckyValidation(form)
  const groups = form.groups.map(g => {
    let fixed = 0, fixedShortage = 0, flexibleAvailable = 0
    const candidates = g.candidates.map(c => {
      const fact = stock.get(c.sku_sn) || { available: 0 }
      const isFixed = form.mode === 'PRESET' && c.fixed_quantity != null
      const required = isFixed ? Number(c.fixed_quantity) : null
      const available = Number(fact.available || 0)
      const shortage = isFixed ? Math.max(0, required - available) : null
      if (isFixed) { fixed += required; fixedShortage += shortage } else flexibleAvailable += available
      return { ...fact, sku_sn: c.sku_sn, goods_name: c.goods_name, required, available, shortage, mode: isFixed ? required === 0 ? 'EXCLUDE' : 'FIXED' : 'AUTO' }
    })
    const flexibleRequired = Math.max(0, quantity - fixed)
    const flexibleShortage = Math.max(0, flexibleRequired - flexibleAvailable)
    return { key: g.key, name: g.name, required: quantity, fixed_shortage: fixedShortage, flexible_required: flexibleRequired, flexible_available: flexibleAvailable, flexible_shortage: flexibleShortage, shortage: fixedShortage + flexibleShortage, candidates }
  })
  const shortage = groups.reduce((sum, g) => sum + g.shortage, 0)
  return { store_code: warehouse.store_code, groups, shortage, code: configurationIssue ? 'CONFIGURATION_ERROR' : shortage ? 'STOCK_SHORTAGE' : 'STOCK_READY', message: configurationIssue ? configurationIssue.message : shortage ? `本仓缺少${shortage}件组件，当前无法配齐${quantity}袋；请查看各组缺口，补货、调整配额或切换仓库。` : `本仓组件数量满足${quantity}袋需求，逐袋组合与成本仍需完整试算；此预检不锁定库存。` }
}

export const luckyTrialStatus = code => ({ READY: '本次可配齐', STOCK_SHORTAGE: '组件库存不足', COST_MISSING: '必需批次成本缺失', COST_LIMIT: '成本超过上限', COMBINATION_UNRESOLVED: '未找到可行组合', CHECK_FAILED: '检查失败，无法判断库存' }[code] || '本次未配齐')
