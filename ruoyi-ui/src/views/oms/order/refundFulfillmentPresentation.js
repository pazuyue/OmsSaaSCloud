export const fulfillmentStages = { UNSHIPPED: '全部未出库', PART_SHIPPED: '部分已出库', SHIPPED: '全部已出库', CANCELED: '已全部取消', UNKNOWN: '出库情况待核对' }
export const fulfillmentActions = { QUERY_FIRST: '先核对原仓库结果', CANCEL_WAREHOUSE: '需仓内截单', WITHDRAW_LOCAL: '可撤回本地安排', CHECK_PACKAGE: '核对原发货包裹' }
export function fulfillmentAdvice(refund) {
  if (refund.review_state === 'APPROVED' && refund.goods_action === 'KEEP') return { type: 'success', title: '已确认商品继续履约或无需追回', description: '本退款不取消商品数量、不释放库存占用；其他挂起条件仍需满足。' }
  const current = refund.fulfillment || {}
  if (current.uncertain || !current.stage || current.stage === 'UNKNOWN') return { type: 'warning', title: '实际出库结果尚待核对', description: '请先核对原仓库受理、出库回传或记账结果，再执行取消。' }
  return { type: 'info', title: '先确认是否取消商品，再按实际发货来源处理', description: '仅退款、少量退款或原因中出现“差价”，均不能单独决定取消数量。未发部分取消，已发部分按包裹拦截或退回。' }
}
