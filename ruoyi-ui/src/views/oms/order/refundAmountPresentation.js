export function refundAmountSummary(refund) {
  const context = refund.amount_context || {}
  const completed = refund.funds_state === 'SUCCESS'
  const platform = refund.platform || {}
  const occupied = context.other_occupied == null || Number(context.other_occupied) > 0
    ? [{ key: 'occupied', label: '其他进行中退款占用', value: context.other_occupied }] : []
  return {
    context, completed,
    requested: platform.refund_fee == null ? refund.refund_fee : platform.refund_fee,
    paidLabel: context.authoritative === false ? '原子单商品实付（计算参考）' : '原子单商品实付',
    quantityPayment: context.requested_goods_payment,
    quantityNote: context.requested_payment_note || '申请数量对应实付未提供，不按平均单价推算。',
    hasRelated: Number(context.other_confirmed) > 0 || Number(context.other_occupied) > 0,
    occupied,
    fields: completed ? [
      { key: 'other', label: '其他单已退（不含本单）', value: context.other_confirmed },
      { key: 'current', label: '本单已退', value: context.current_confirmed },
      { key: 'total', label: '累计已退', value: context.confirmed_total },
      { key: 'remaining', label: '剩余可退', value: context.remaining_unpaid }
    ] : [
      { key: 'other', label: '其他单已退（不含本单）', value: context.other_confirmed },
      ...occupied,
      { key: 'limit', label: '本次可处理上限', value: context.current_limit }
    ]
  }
}

// Only expose a definite conflict from the server's authoritative suborder budget.
// The server rechecks this budget under the original-order lock before approving/paying.
export function refundAmountConflict(refund) {
  const context = refund.amount_context || {}
  const platform = refund.platform || {}
  if (!context.reliable || refund.funds_state === 'SUCCESS' || ['SUCCESS', 'CLOSED'].includes(platform.platform_status)) return null
  const cents = value => value != null && /^\d+(\.\d{1,2})?$/.test(String(value)) ? Math.round(Number(value) * 100) : null
  const requested = cents(platform.refund_fee == null ? refund.refund_fee : platform.refund_fee)
  const limit = cents(context.current_limit)
  if (requested == null || limit == null || requested <= limit) return null
  return { requested: requested / 100, limit: limit / 100, excess: (requested - limit) / 100, original: context.original_goods_payment, confirmed: context.other_confirmed, occupied: context.other_occupied }
}
