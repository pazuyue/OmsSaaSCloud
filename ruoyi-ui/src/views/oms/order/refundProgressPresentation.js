import { label } from './refundLabels'
import { money } from './orderAmounts'
import { refundAmountConflict } from './refundAmountPresentation'

const n = value => Number(value || 0)
const yes = value => value === true || n(value) === 1
const list = value => Array.isArray(value) ? value : []
const unresolved = p => n(p.pending_count) > 0 || yes(p.parcel_receipt_pending)

export function confirmedRefundFee(refund) {
  const platform = refund.platform || {}
  if (platform.source_state === 'VALID' && platform.platform_status === 'SUCCESS' && platform.refund_fee != null) return platform.refund_fee
  const observation = list((refund.platform_interaction || {}).observations).find(row => row.platform_status === 'SUCCESS' && ['NEW', 'UPDATED'].includes(row.outcome) && row.refund_fee != null)
  return observation ? observation.refund_fee : null
}

export function progressQuantity(progress, field) {
  const units = list(progress.unit_progress)
  if (!units.length) return n(progress[field]) ? `${n(progress[field])} 库存单位` : '0'
  return units.filter(row => n(row[field]) > 0).map(row => `${n(row[field])} ${row.physical_unit}`).join(' + ') || '0'
}

const task = (key, title, message, tab, action, priority = 50, extra = {}) => ({ key, title, message, tab, action, priority, owner: 'manual', ...extra })

function goodsTrack(refund, gift) {
  const progress = (gift ? refund.gift_progress : refund.purchase_progress) || {}
  const plans = list((refund.progress_context || {}).plans).filter(p => !!n(p.gift_plan_id) === gift)
  const planned = n(progress.return_quantity)
  const prefix = gift ? '关联赠品' : '购买商品'
  const details = planned ? `计划 ${progressQuantity(progress, 'return_quantity')} · 实收 ${progressQuantity(progress, 'accepted_quantity')} · 已入库 ${progressQuantity(progress, 'posted_quantity')}${n(progress.waived_quantity) ? ' · 不再追回 ' + progressQuantity(progress, 'waived_quantity') : ''}` : ''
  const value = { key: gift ? 'gift' : 'purchase', title: prefix, state: '', detail: details, tone: 'info', complete: false, tab: 'goods', gifts: gift }
  const add = (state, message, priority, owner = 'manual', plan = plans[0]) => {
    value.state = state
    value.tone = owner === 'manual' ? 'warning' : 'info'
    value.task = task(value.key, `${prefix}${state}`, message, 'goods', owner === 'manual' ? '去处理' : '查看进度', priority, { owner, gifts: gift, plan_id: plan && plan.id })
    return value
  }
  if (n(progress.pending_count) || plans.some(unresolved)) return add('实收待核对', '先核对包裹归属或实收分配，待确认部分尚未计入本退款库存。', 20, 'manual', plans.find(unresolved))
  if (n(progress.unposted_quantity)) return add('实收待入库', `已确认实收中还有 ${progressQuantity(progress, 'unposted_quantity')} 尚未入库。`, 25, 'manual', plans.find(p => n(p.accepted_quantity) > n(p.posted_quantity)))
  const closed = (refund.platform || {}).platform_status === 'CLOSED' && refund.funds_state !== 'SUCCESS'
  if (closed && (planned && (n(progress.remaining_quantity) || plans.some(p => n(p.active_documents))) || !gift && !planned && !['DONE', 'CANCELED'].includes(refund.goods_state))) return add('关闭后货物待处置', '平台申请已关闭，请核对原仓库安排、已实收入库和剩余商品处置；关闭申请不会撤销已入库库存。', 18)
  if (!gift && refund.goods_action === 'MIXED') {
    const scope = (...args) => { const track = add(...args); track.tab = 'current'; track.task.tab = 'current'; track.task.focus = 'fulfillment'; return track }
    const pending = list(refund.goods_tasks).filter(row => !['DONE', 'CANCELED', 'WAIT_RETURN'].includes(row.state))
    const manual = pending.find(row => row.state === 'MANUAL')
    if (manual) return scope('分项货物处理待核对', manual.result_message || '请核对原商品、仓库及任务依据。', 22)
    const cancellation = pending.find(row => row.action === 'CANCEL')
    if (cancellation) return scope(cancellation.state === 'WAIT_WAREHOUSE' ? '等待原仓库截单确认' : cancellation.state === 'UNKNOWN' ? '核对原库存取消结果' : '正在取消指定未发数量', cancellation.result_message || '后台按原任务处理；仓库确认前保留占用，不影响其他商品需求。', 25, 'system')
    if (pending.length) return scope('正在建立原来源退回安排', pending[0].result_message || '后台按已审核的各仓实际来源建立退回安排。', 35, 'system')
  }
  if (planned) {
    const remaining = n(progress.remaining_quantity)
    if (remaining) {
      const unknown = plans.find(p => p.dispatch_state === 'UNKNOWN')
      if (unknown) return add('下发结果待确认', '请查询原退货入库单的仓库受理结果，再决定后续处理。', 28, 'manual', unknown)
      const failed = plans.find(p => p.dispatch_state === 'FAILED')
      if (failed) return add('仓库下发失败', failed.last_error || '请核对原单下发失败原因后处理。', 30, 'manual', failed)
      const sending = plans.find(p => p.dispatch_state === 'SENDING')
      if (sending) return add('正在下发仓库', '正在核对仓库受理结果，请查看原退货入库单。', 65, 'system', sending)
      const manual = plans.find(p => ['FAILED', 'MANUAL', 'HELD', 'WAIT_SHOP'].includes(p.auto_dispatch_state) || ['STOPPED', 'WAIT_REVIEW'].includes(p.state))
      if (manual) return add('退回安排待处理', manual.auto_dispatch_message || `仍有 ${progressQuantity(progress, 'remaining_quantity')} 需要重新安排或处理差异。`, 30, 'manual', manual)
      const collecting = plans.find(p => p.auto_dispatch_state === 'COLLECTING')
      if (collecting) return add('等待合包归集', '按店铺归集时间等待，到期后由后台扫描检查下发；当前尚未下发仓库。', 65, 'system', collecting)
      const retry = plans.find(p => ['RETRY', 'READY'].includes(p.auto_dispatch_state))
      if (retry) return add('系统处理中', retry.auto_dispatch_message || '后台正在处理下发，请查看当前安排。', 65, 'system', retry)
      const waiting = plans.find(p => ['WAIT_LOGISTICS', 'WAIT_ASSOCIATION', 'WAIT_REVIEW'].includes(p.auto_dispatch_state))
      if (waiting) return add(waiting.auto_dispatch_state === 'WAIT_ASSOCIATION' ? '待确认包裹范围' : '等待寄回信息', waiting.auto_dispatch_message || '请核对寄回物流与包裹范围。', 55, waiting.auto_dispatch_state === 'WAIT_ASSOCIATION' ? 'manual' : 'buyer', waiting)
      const uncreated = plans.find(p => !p.has_document && p.state === 'PLANNED')
      if (uncreated) return add('待下发仓库', '退回安排已创建，尚未生成已受理的退货入库单。', 40, 'manual', uncreated)
      return add('待收货', `尚有 ${progressQuantity(progress, 'remaining_quantity')} 待退回或差异处理。`, 60, 'warehouse', plans.find(p => n(p.quantity) > n(p.accepted_quantity) + n(p.waived_quantity)))
    }
    if (plans.some(p => n(p.active_documents))) return add('仓库安排待结束', '数量已经核对，仍需确认仓库收货结束或剩余安排已截停。', 35)
    if (!gift && refund.goods_state !== 'DONE' && refund.goods_state !== 'CANCELED') return add('待确认处理完成', '实收、入库和差异已核对，请确认本退款货物事项处理完成。', 45)
    value.state = n(progress.waived_quantity) ? '已处理（含不再追回）' : '已处理完成'
    value.complete = true
    value.tone = 'success'
    return value
  }
  if (gift) return { ...value, state: '无退回关联', detail: '本退款当前没有已关联的赠品退回安排。', complete: true }
  if (refund.goods_state === 'DONE' || refund.goods_state === 'CANCELED') {
    return { ...value, state: refund.goods_state === 'CANCELED' ? '无需继续处理' : refund.goods_action === 'MIXED' ? '分项货物处理完成' : refund.goods_action === 'CANCEL' ? '取消已完成' : refund.goods_action === 'KEEP' ? '无需退回' : '已确认无需追回', detail: refund.goods_action === 'KEEP' ? '按已审核方案继续履约或无需追回商品。' : '以本退款已确认的货物处置方案为准。', complete: true, tone: 'success' }
  }
  if (refund.review_state !== 'APPROVED') return { ...value, state: '待确认方案', detail: '审核后明确取消、退回或无需追回。' }
  const auto = refund.return_auto || {}
  if (['PENDING', 'RETRY'].includes(auto.state)) return add('正在创建退回安排', auto.message || '后台正在核对原发货来源及退回范围。', 55, 'system')
  return add(refund.goods_action === 'CANCEL' ? '待取消未发商品' : '退回范围待明确', auto.message || '请明确本次实际商品、数量和处理方式。', 35)
}

export function platformProgress(refund) {
  const platform = refund.platform || {}
  const state = platform.platform_status
  const result = { key: 'platform', title: '平台处理', state: label(state), detail: '状态来自平台同步；本地审核不会向平台提交同意或拒绝操作。', tone: 'info', complete: false, tab: 'platform' }
  if (state === 'SUCCESS') return { ...result, state: '平台流程已完成', detail: '平台已同步退款成功；此结果不代表操作由OMS发起。', complete: true, tone: 'success' }
  if (state === 'CLOSED') return { ...result, state: '平台申请已关闭', detail: '关闭不等于退款成功，已发生的货物处理仍需核对。', complete: true }
  const operation = list((refund.platform_interaction || {}).operations).find(row => n(row.source_version) === n(platform.version))
  if (operation) {
    const name = operationText(operation.action)
    if (operation.state === 'UNKNOWN') return { ...result, state: '平台操作结果待确认', tone: 'warning', task: task('platform', '平台操作结果待确认', `${name}结果尚未明确，请核对原请求和平台结果，避免重复提交。`, 'platform', '核对平台交互结果', 20, { owner: 'platform' }) }
    if (operation.state === 'FAILED') return { ...result, state: '平台操作失败', tone: 'warning', task: task('platform', '平台操作失败待处理', operation.result_message || '请核对平台返回的失败原因，再决定后续处理。', 'platform', '查看平台失败原因', 30) }
    if (['PENDING', 'RUNNING', 'SENDING'].includes(operation.state)) return { ...result, state: operation.state === 'PENDING' ? '平台操作待提交' : '平台操作提交中', task: task('platform', '等待平台操作返回', `${name}尚未取得明确返回结果。`, 'platform', '查看提交记录', 65, { owner: 'system' }) }
    if (['ACCEPTED', 'SUCCESS'].includes(operation.state)) return { ...result, state: '操作已返回，等待平台结果', task: task('platform', '等待平台处理结果同步', `${name}已取得接口返回；实际平台状态和最终资金结果仍需同步确认。`, 'platform', '查看平台返回记录', 80, { owner: 'platform' }) }
  }
  if (state === 'WAIT_SELLER_AGREE') {
    const action = platform.dispute_type === 'REFUND_AND_RETURN' ? '同意退货' : '同意退款'
    return { ...result, state: `待平台${action}`, task: task('platform', `平台仍待卖家处理`, `本地审核与平台处理独立。请在平台核对并执行${action}或拒绝，再等待同步结果。`, 'platform', '查看平台处理', 50, { owner: 'platform' }) }
  }
  if (state === 'WAIT_BUYER_RETURN_GOODS') return { ...result, state: '平台等待买家退货', detail: '平台已进入退货阶段，尚不能据此认定已完成退款。' }
  if (state === 'WAIT_SELLER_CONFIRM_GOODS') return { ...result, state: '待平台确认收货退款', task: task('platform', '平台待确认收货退款', '请核对实收及业务方案后在平台处理；OMS当前同步平台结果。', 'platform', '查看平台处理', 50, { owner: 'platform' }) }
  if (state === 'SELLER_REFUSE_BUYER') return { ...result, state: '平台已拒绝申请', tone: 'warning', task: task('platform', '平台拒绝后续待核对', '请在平台核对买家后续申请、协商及最终处理结果。', 'platform', '查看平台处理', 50, { owner: 'platform' }) }
  return { ...result, state: state ? label(state) : '平台状态待同步' }
}

export function refundProgress(refund, giftReport = null, giftError = '') {
  const platform = refund.platform || {}
  const reviewDone = refund.review_state === 'APPROVED'
  const closed = platform.platform_status === 'CLOSED' && refund.funds_state !== 'SUCCESS'
  const review = { key: 'review', title: '本地方案', state: reviewDone ? '已审核' : closed ? '申请已关闭' : label(refund.review_state), detail: reviewDone ? '已确认本地用途与货物方案；平台同意退款等操作单独跟踪。' : '请核对当前平台申请，再确认本地处理方案。', tone: reviewDone ? 'success' : 'info', complete: reviewDone || closed, tab: 'approval' }
  const purchase = goodsTrack(refund, false)
  let gift = goodsTrack(refund, true)
  const giftRequired = yes(refund.gift_assessment_required)
  // Do not interpret a report that has not loaded as "no gifts" or "all done".
  if (giftRequired && giftReport === null) {
    gift = { ...gift, state: giftError ? '赠品核对加载失败' : '正在核对赠品', detail: giftError || '本退款关联数量已保留，正在核对原订单赠品信息。', tone: giftError ? 'warning' : 'info', complete: false, task: task('gift-check', giftError ? '赠品进度需重新核对' : '赠品信息正在核对', giftError || '等待赠品信息加载后确认本退款处理范围。', 'current', giftError ? '重新核对赠品' : '查看赠品', 70, { owner: giftError ? 'manual' : 'system', focus: 'gifts' }) }
  }
  const interaction = platformProgress(refund)
  const fundsDone = refund.funds_state === 'SUCCESS'
  const confirmedFee = confirmedRefundFee(refund)
  const funds = { key: 'funds', title: '平台资金', state: fundsDone ? '平台已退款' : closed ? '申请已关闭' : '未确认退款成功', detail: fundsDone ? confirmedFee == null ? '已确认退款成功；具体金额请核对平台成功来源记录。' : `平台确认退款 ${money(confirmedFee)}；以平台资金结果为准。` : closed ? '当前没有平台退款成功结果。' : `申请金额 ${money(platform.refund_fee == null ? refund.refund_fee : platform.refund_fee)}，等待平台处理及同步确认。`, tone: fundsDone ? 'success' : 'info', complete: fundsDone || closed, tab: 'platform' }
  const tasks = []
  const amountConflict = refundAmountConflict(refund)
  if (amountConflict) tasks.push(task('amount', '申请金额超出可退额度', `本次申请 ${money(amountConflict.requested)}，可退上限 ${money(amountConflict.limit)}；请协商修改平台申请，或核对平台协商、拒绝操作。人工审核不能突破累计额度。`, 'current', '处理金额冲突', 4, { focus: 'amount-resolution' }))
  const sourceError = refund.problem_message || platform.source_error || platform.conversion_error
  if (sourceError) tasks.push(task('source', '退款资料或方案待核对', sourceError, 'approval', '核对方案与来源', 5))
  if (!reviewDone && !closed) tasks.push(task('review', refund.review_state === 'STALE' ? '平台申请变化，方案待重审' : '本地退款方案待审核', (refund.automatic_flow || {}).state === 'MANUAL' && refund.automatic_flow.last_message ? refund.automatic_flow.last_message : '审核只确认本地方案，平台同意或拒绝结果单独跟踪。', 'approval', '核对审核方案', 10))
  if (list(refund.parcel_issues).length) tasks.push(task('parcel', '退回包裹归属待核对', refund.parcel_issues[0].next_action, 'goods', '核对包裹归属', 15, { issue: true, plan_id: refund.parcel_issues[0].plan_id }))
  ;[purchase, gift, interaction].forEach(track => { if (track.task) tasks.push(track.task) })
  if (!funds.complete && !interaction.task) tasks.push(task('funds', purchase.complete && gift.complete ? '货物已处理，等待平台资金结果' : '等待平台退款结果', '资金结果由平台同步确认，请核对平台处理状态及最近同步时间。', 'platform', '查看平台结果', 80, { owner: 'platform' }))
  if ((refund.progress_context || {}).plans_truncated) tasks.push(task('scope', '退回安排较多，需核对完整范围', '顶部展示有限数量的执行信息，全部数量以本退款汇总和商品明细为准。', 'goods', '查看完整明细', 40))
  const sorted = tasks.sort((a, b) => a.priority - b.priority)
  const primary = sorted[0] || null
  const complete = !primary && review.complete && purchase.complete && gift.complete && funds.complete
  let title = primary ? primary.title : complete ? closed ? '平台申请已关闭，货物事项已处理' : '本退款处理完成' : '正在核对本退款进度'
  if (closed && primary) title = '平台申请已关闭，仍有事项待处理'
  if (fundsDone && primary && !title.includes('平台')) title = '平台已退款，' + title
  const tone = primary ? primary.owner === 'manual' ? 'warning' : 'info' : complete && !closed ? 'success' : 'info'
  return { title, tone, complete, primary, tasks: sorted, tracks: [review, purchase, gift, interaction, funds],
    times: [{ title: 'OMS收单', value: refund.create_time }, { title: '本地审核', value: refund.reviewed_at }, { title: '最近平台同步', value: platform.last_sync }, { title: '最近处理记录', value: (list(refund.events)[0] || {}).create_time }].filter(item => item.value) }
}

export const ownerText = owner => ({ manual: '需要人工处理', system: '系统处理中', buyer: '等待买家', warehouse: '等待仓库', platform: '平台处理 / 等待同步' })[owner] || '待核对'
export const operationText = action => ({ PAY_CODE: '请求退款验证码', REVIEW: '平台退款审核', PAY: '提交退款付款', NEGOTIATE_SUBMIT: '提交协商方案', REFUSE_RETURN: '拒绝退货', CANCEL_FEEDBACK: '回告取消发货', INTERCEPT: '申请在途拦截', INTERCEPT_FEEDBACK: '回告物流结果', RETURN_LOGISTICS: '回填退回物流', MESSAGE: '提交留言与凭证', AGREE: '同意退款', AGREE_REFUND: '同意退款', AGREE_RETURN: '同意退货', REFUSE: '拒绝退款', REFUSE_REFUND: '拒绝退款', CONFIRM_RECEIVE: '确认收货退款', QUERY: '查询平台结果' })[action] || action || '操作类型未提供'
export const operationState = state => ({ PENDING: '待提交', RUNNING: '提交中', SENDING: '提交中', ACCEPTED: '平台已受理', SUCCESS: '操作返回成功', FAILED: '操作失败', UNKNOWN: '结果待确认', CLOSED: '已结束' })[state] || state || '结果未提供'
