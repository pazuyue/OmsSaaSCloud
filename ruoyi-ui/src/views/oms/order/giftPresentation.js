const labels = { WAIT_STOCK: '待补货', ELIGIBLE: '无需售后处理', PENDING_REVIEW: '售后待确认', WAIT_CANCEL: '待取消未发赠品', WAIT_CUTOFF: '待撤回后取消', WAIT_RETURN: '待安排退回', RETURNING: '退回处理中', RETURNED: '已退回入账', WAIVED: '已确认不再追回', CANCELED: '已取消' }

export function giftNeedsAttention(line) {
  return Number(line.cancel_required) > 0 || Number(line.return_required) > 0 || ['WAIT_STOCK', 'PENDING_REVIEW'].includes(line.gift_state)
}

export function giftStateLabel(line) { return labels[line.gift_state] || line.gift_state || '待核对' }

export function scopedGiftPlans(line, refundId) {
  return (line.return_plans || []).filter(plan => !refundId || String(plan.refund_id) === String(refundId))
}

// A return plan has explicit ownership. Order-wide eligibility has no per-refund attribution.
export function giftDisplayGroups(lines, refundId) {
  if (!refundId) return [{ key: 'action', title: '待处理赠品', lines: lines.filter(giftNeedsAttention) }, { key: 'other', title: '无需处理的赠品', collapsed: true, lines: lines.filter(line => !giftNeedsAttention(line)) }]
  const owns = line => scopedGiftPlans(line, refundId).length > 0
  return [
    { key: 'current', title: '本退款已关联的赠品', description: '退回安排归属本退款；赠送资格仍按原订单累计售后核对。', lines: lines.filter(owns) },
    { key: 'action', title: '原订单另有待处理赠品', description: '以下为整单累计待办，尚未关联本退款。安排退回时默认选择当前退款，不表示待退数量全部由本次申请产生。', lines: lines.filter(line => !owns(line) && giftNeedsAttention(line)) },
    { key: 'other', title: '原订单其他赠品', collapsed: true, lines: lines.filter(line => !owns(line) && !giftNeedsAttention(line)) }
  ]
}
