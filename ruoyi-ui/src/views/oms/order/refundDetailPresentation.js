// The platform application is one sub-order. Fulfilment replacements are separate sources.
export function refundApplication(refund) {
  const platform = refund.platform || {}
  const oid = String(refund.platform_oid || platform.platform_oid || '')
  const source = (refund.lines || []).filter(line => !Number(line.gift_plan_id) && !Number(line.exchange_id) && line.business_type !== 'EXCHANGE' && String(line.platform_oid || '') === oid)
  const bundle = (refund.bundles || []).find(item => String(item.platform_oid || '') === oid)
  const products = bundle ? [bundle] : source
  const original = products[0] || {}, stocked = !bundle && !!original.stocked_bundle_snapshot
  let recipe = {}
  if (stocked) { try { recipe = typeof original.stocked_bundle_snapshot === 'string' ? JSON.parse(original.stocked_bundle_snapshot) : original.stocked_bundle_snapshot } catch (_) { recipe = {} } }
  const kind = bundle ? (bundle.business_type === 'LUCKY_BAG' ? 'LUCKY_BAG' : 'VIRTUAL_BUNDLE') : stocked ? 'STOCKED_BUNDLE' : 'NORMAL'
  return {
    key: oid || String(refund.id), platform_oid: oid,
    quantity: platform.platform_quantity == null ? null : platform.platform_quantity,
    unit: kind === 'LUCKY_BAG' ? '袋' : kind === 'NORMAL' ? '件' : '套',
    kind, type_label: { NORMAL: '普通商品', STOCKED_BUNDLE: '实物组合单品', VIRTUAL_BUNDLE: '虚拟组合单品', LUCKY_BAG: '福袋' }[kind],
    original_quantity: (refund.sales_context || {}).line && (refund.sales_context || {}).line.quantity != null ? refund.sales_context.line.quantity : original.quantity == null ? null : original.quantity,
    sales_spec: source.length ? source[0].platform_spec || '' : '',
    money: (refund.sales_context || {}).line || {},
    components: bundle ? bundle.components || [] : recipe.components || [],
    units: bundle ? bundle.units || [] : [],
    return_units: bundle ? (bundle.units || []).filter(u => String(u.return_refund_id) === String(refund.id)) : [],
    replacements: (refund.lines || []).filter(line => Number(line.exchange_id) && !Number(line.gift_plan_id)),
    products: products.length ? products.map(item => ({ id: item.id, name: item.goods_name || item.title || '商品名称待核对', sku: item.sku_sn || '', spec: item.platform_spec || item.goods_spec || '' })) : [{ id: 'unknown', name: '平台申请商品资料待核对', sku: '', spec: '' }]
  }
}

export function refundGiftRows(lines, refundId) {
  if (!refundId) return []
  return lines.flatMap(line => {
    const plans = (line.return_plans || []).filter(plan => String(plan.refund_id) === String(refundId))
    if (!plans.length) return []
    const sum = key => plans.reduce((value, plan) => value + Number(plan[key] || 0), 0)
    const pending = plans.some(plan => Number((plan.actual || {}).parcel_pending_count) > 0 || Number((plan.actual || {}).pending_review_count) > 0 || ((plan.actual || {}).reviews || []).some(review => review.state === 'WAIT_REVIEW'))
    const quantity = sum('quantity'), accepted = sum('accepted_quantity'), posted = sum('posted_quantity'), waived = sum('waived_quantity')
    const remaining = plans.reduce((value, plan) => value + Math.max(0, Number(plan.quantity || 0) - Number(plan.accepted_quantity || 0) - Number(plan.waived_quantity || 0)), 0)
    const posting = Math.max(0, accepted - posted)
    const state = pending ? '待核对实收' : posting ? '待入账' : remaining ? plans.some(plan => !plan.actual && plan.state === 'PLANNED') ? '待下发退回单' : '待退回' : waived ? '已处理（含不再追回）' : '已退回入账'
    return [{ ...line, plans, planned: quantity, accepted, posted, waived, remaining, posting, pending, state, needs_action: pending || posting > 0 || remaining > 0 }]
  })
}

export function refundCurrentTask(refund, gifts, goodsNextAction) {
  if ((refund.parcel_issues || []).length) return { title: '退回包裹归属待核对', message: refund.parcel_issues[0].next_action, action: '核对退回包裹', tab: 'goods' }
  if ((refund.parcel_followups || []).length) return { title: '退回安排有待办', message: refund.parcel_followups[0].next_action, action: '继续处理退回', tab: 'goods' }
  if (refund.problem_message) return { title: '退款有异常待核对', message: refund.problem_message, action: '查看审核与来源', tab: 'approval' }
  if (refund.review_state !== 'APPROVED' && !['COMPLETED', 'CLOSED', 'CANCELED'].includes(refund.state)) return { title: '退款方案待审核', message: '核对本次申请商品、用途与货物处理方式。', action: '核对审核方案', tab: 'approval' }
  if (flag(refund.missing_purchase_document) && (refund.platform || refund).dispute_type === 'REFUND' && refund.goods_action === 'RETURN' && !Number((refund.purchase_progress || {}).plan_count)) return { title: '商品退回单尚未创建', message: '平台申请为仅退款，已审核协商退回商品；请按原发货来源人工创建退回安排。', action: '创建商品退回安排', tab: 'goods' }
  const auto = refund.return_auto || {}
  if (refund.goods_state !== 'DONE' && refund.goods_state !== 'CANCELED' && ['PENDING','RETRY','MANUAL','CANCELED'].includes(auto.state)) return { title: auto.state === 'MANUAL' ? '商品退回安排需人工核对' : auto.state === 'CANCELED' ? '自动创建退回安排已暂停' : '商品退回安排自动创建中', message: auto.message || '后台正在核对原发货来源、平台申请数量和店铺退回仓。', action: '查看退回安排', tab: 'goods' }
  if (!['DONE', 'CANCELED'].includes(refund.goods_state) && refund.goods_action !== 'KEEP') return { title: '本退款货物处理未完成', message: goodsNextAction, action: '查看货物进度', tab: 'goods' }
  if (gifts.some(line => line.needs_action)) return { title: '本退款关联赠品待处理', message: '跟进已关联本退款的赠品退回、实收与入账。', action: '查看赠品退回', tab: 'goods', gifts: true }
  return null
}

export function refundGoodsNextAction(refund) {
  if ((refund.parcel_issues || []).length) return '先核对合包归属；核对前不下发THK'
  if ((refund.parcel_followups || []).length) return refund.parcel_followups[0].next_action
  if (refund.goods_action === 'KEEP') return '审核确认继续履约或无需追回'
  if (refund.goods_action === 'CANCEL') return '先核对原单撤回结果，再执行已审核取消'
  return ({ WAIT_ACTION: '确认实际退回商品与数量，创建退回安排', WAIT_RETURN: '跟进退回物流及仓库实收', PART_RETURNED: '部分已实收，继续跟进剩余退回', WAIT_CHECK: '实收分配或异常回传待核对；请查看本退款货物进度', WAIT_QC: '核对实收、入账与差异，确认货物处理完成', DONE: '货物处理已完成' })[refund.goods_state] || '截单、退回、入库分别处理'
}

const flag = value => value === true || Number(value) === 1
export function refundScenario(refund) {
  const platform = refund.platform || refund, purchase = flag(refund.has_purchase_return), gift = flag(refund.has_gift_return)
  const active = flag(refund.purchase_return_pending), action = refund.goods_action
  let text = '货物方案待审核', tone = 'info'
  if (purchase) {
    const progress = refund.purchase_progress || {}
    text = active ? (Number(progress.pending_count) ? '商品实收待核对' : Number(progress.unposted_quantity) ? '商品实收待入账' : Number(progress.return_quantity) > 0 && !Number(progress.remaining_quantity) ? '商品退回待结案' : action === 'REFUSAL' ? '拒收退回' : action === 'INTERCEPT' ? '拦截退回处理中' : '商品需退回') : '商品退回已处理'
    if (gift) text += ' · 含赠品退回'
    tone = active ? 'warning' : 'success'
  } else if (gift) { text = '仅赠品退回'; tone = refund.gift_pending ? 'warning' : 'info' }
  else if (refund.review_state === 'APPROVED' || ['DONE', 'CANCELED'].includes(refund.goods_state)) {
    text = action === 'CANCEL' ? '未发商品取消' : ['RETURN', 'REFUSAL', 'INTERCEPT'].includes(action) ? '已确认无需追回' : action === 'KEEP' ? '商品不退回' : '货物处理已结束'
  }
  return { platform: platform.dispute_type === 'REFUND_AND_RETURN' ? '退货退款' : platform.dispute_type === 'REFUND' ? '仅退款' : '申请类型待核对', text, tone, purchase, gift, active }
}
