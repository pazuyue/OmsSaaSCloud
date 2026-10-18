export function documents(plan) { return plan.actual ? (plan.actual.documents || [plan.actual]) : [] }
export function activeDocuments(plan) { return documents(plan).filter(d => d.cancel_state !== 'CONFIRMED' && d.receipt_state !== 'COMPLETED') }
export function pendingReviews(plan) { return ((plan.actual || {}).reviews || []).filter(r => r.state === 'WAIT_REVIEW') }
export function pendingQuantity(plan) { return pendingReviews(plan).reduce((n, r) => n + Number(r.quantity || 0), 0) }
export function remaining(plan) { return Math.max(0, Number(plan.quantity || 0) - Number(plan.accepted_quantity || 0) - Number(plan.waived_quantity || 0)) }
export function completionBlockers(plans) {
  if (!plans.length) return ['尚未明确退回计划；无需追回请使用独立差异处理']
  const blockers = []
  plans.forEach(p => {
    const prefix = `退回计划 #${p.id}：`
    if (Number((p.actual || {}).pending_review_count) > 0 || pendingReviews(p).length) blockers.push(prefix + '有回传待人工核对，未计入实收和库存')
    if (Number(p.accepted_quantity) !== Number(p.posted_quantity)) blockers.push(prefix + '有已确认实收尚未入账')
    if (remaining(p)) blockers.push(prefix + `仍有 ${remaining(p)} 件待退回或差异处理`)
    if (activeDocuments(p).length || (!p.actual && p.state !== 'STOPPED' && p.state !== 'CLOSED')) blockers.push(prefix + '仍有未结束的退回安排')
  })
  return blockers
}
