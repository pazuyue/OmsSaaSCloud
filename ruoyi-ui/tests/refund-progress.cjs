const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(name) {
  const module = { exports: {} }
  const source = fs.readFileSync(path.join(root, name + '.js'), 'utf8')
  const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(dependency => load(dependency.replace('./', '')), module, module.exports)
  return module.exports
}
const h = load('refundProgressPresentation')
const done = { id: 85, state: 'COMPLETED', review_state: 'APPROVED', goods_state: 'DONE', goods_action: 'RETURN', funds_state: 'SUCCESS', refund_fee: '40.00', gift_assessment_required: true,
  platform: { platform_status: 'SUCCESS', source_state: 'VALID', refund_fee: '40.00' },
  purchase_progress: { return_quantity: 3, accepted_quantity: 3, posted_quantity: 3, remaining_quantity: 0, unit_progress: [{ physical_unit: '件', return_quantity: 3, accepted_quantity: 3, posted_quantity: 3 }] },
  gift_progress: { plan_count: 2, return_quantity: 8, accepted_quantity: 8, posted_quantity: 8, remaining_quantity: 0, unit_progress: [{ physical_unit: '件', return_quantity: 7, accepted_quantity: 7, posted_quantity: 7 }, { physical_unit: '套', return_quantity: 1, accepted_quantity: 1, posted_quantity: 1 }] },
  progress_context: { plans: [{ id: 1, quantity: 3, accepted_quantity: 3, posted_quantity: 3, active_documents: 0 }, { id: 2, gift_plan_id: 9, quantity: 1, accepted_quantity: 1, posted_quantity: 1, active_documents: 0 }] } }
const gift = { available: true, lines: [] }
let progress = h.refundProgress(done, gift)
assert.equal(progress.complete, true)
assert.equal(progress.title, '本退款处理完成')
assert.equal(progress.tracks.length, 5)
assert.match(progress.tracks.find(t => t.key === 'gift').detail, /7 件 \+ 1 套/)
assert.equal(h.refundProgress(done).complete, false, 'an unloaded gift report must not mean completion')
assert.match(h.refundProgress(done).title, /赠品信息正在核对/)
assert.match(h.refundProgress(done, null, '加载失败').title, /赠品进度需重新核对/)
progress = h.refundProgress({ ...done, gift_progress: { ...done.gift_progress, unposted_quantity: 1, posted_quantity: 7, unit_progress: [{ physical_unit: '套', unposted_quantity: 1 }] } }, gift)
assert.equal(progress.complete, false)
assert.match(progress.title, /平台已退款.*关联赠品实收待入库/)
assert.equal(progress.primary.plan_id, 2)
assert.equal(progress.primary.gifts, true)
progress = h.refundProgress({ ...done, purchase_progress: { ...done.purchase_progress, pending_count: 1 } }, gift)
assert.match(progress.title, /实收待核对/)
assert.equal(progress.complete, false, 'a late unresolved receipt reopens the visible progress')
const completedSupplement = { ...done, progress_context: { plans: [...done.progress_context.plans.slice(0, 1), { ...done.progress_context.plans[1], state: 'CLOSED', parcel_state: 'NEED_CHECK', parcel_receipt_pending: 0 }] } }
assert.equal(h.refundProgress(completedSupplement, gift).complete, true, 'a retained waybill change is not an outstanding receipt after full receipt and posting')
const unresolvedSupplement = { ...completedSupplement, progress_context: { plans: [{ ...completedSupplement.progress_context.plans[1], parcel_receipt_pending: 1 }] } }
assert.match(h.refundProgress(unresolvedSupplement, gift).title, /实收待核对/, 'unallocated or ambiguous receipt must still require review')
assert.match(h.refundProgress({ ...completedSupplement, progress_context: { plans: [{ ...completedSupplement.progress_context.plans[1], pending_count: 1 }] } }, gift).title, /实收待核对/, 'a new unassigned receipt remains visible even after the original plan has posted')
progress = h.refundProgress({ ...done, review_state: 'STALE', state: 'BLOCKED' }, gift)
assert.equal(progress.primary.tab, 'approval')
assert.match(progress.title, /方案待重审/)
const pending = { ...done, gift_assessment_required: false, funds_state: 'NONE', goods_action: 'KEEP', goods_state: 'DONE', purchase_progress: null, gift_progress: null, progress_context: { plans: [] }, platform: { source_state: 'VALID', platform_status: 'WAIT_SELLER_AGREE', dispute_type: 'REFUND', refund_fee: '40.00' } }
progress = h.refundProgress(pending)
assert.match(progress.tracks.find(t => t.key === 'platform').state, /待平台同意退款/)
assert.equal(progress.primary.tab, 'platform')
assert.equal(progress.complete, false, 'local approval does not imply platform consent or a payment')
assert.match(h.platformProgress({ ...pending, platform: { ...pending.platform, dispute_type: 'REFUND_AND_RETURN' } }).state, /待平台同意退货/)
assert.match(h.platformProgress({ ...pending, platform: { ...pending.platform, platform_status: 'WAIT_BUYER_RETURN_GOODS' } }).detail, /尚不能.*完成退款/)
assert.match(h.platformProgress({ ...pending, platform: { ...pending.platform, platform_status: 'WAIT_SELLER_CONFIRM_GOODS' } }).state, /待平台确认收货退款/)
assert.equal(h.refundProgress({ ...pending, platform: { platform_status: 'CLOSED' }, funds_state: 'CLOSED', review_state: 'CLOSED', goods_state: 'CANCELED' }).complete, true)
const collecting = { ...done, goods_state: 'WAIT_RETURN', purchase_progress: { ...done.purchase_progress, remaining_quantity: 3 }, progress_context: { plans: [{ id: 11, state: 'PLANNED', auto_dispatch_state: 'COLLECTING', quantity: 3 }] } }
assert.equal(h.refundProgress(collecting, gift).primary.owner, 'system')
assert.match(h.refundProgress(collecting, gift).primary.message, /到期后.*扫描/)
const withdrawn = h.refundProgress({ ...collecting, funds_state: 'CLOSED', platform: { platform_status: 'CLOSED' } }, gift)
assert.match(withdrawn.title, /平台申请已关闭.*待处理/)
assert.equal(withdrawn.primary.owner, 'manual', 'a withdrawn application must not keep promising automatic dispatch')
assert.equal(h.confirmedRefundFee({ ...done, refund_fee: '40.00', platform: { source_state: 'VALID', platform_status: 'SUCCESS', refund_fee: '35.00' } }), '35.00')
assert.equal(h.confirmedRefundFee({ ...done, platform: { source_state: 'INVALID', platform_status: 'SUCCESS', refund_fee: '999.00' } }), null)
assert.equal(h.confirmedRefundFee({ ...done, platform: {}, platform_interaction: { observations: [{ platform_status: 'SUCCESS', outcome: 'CONFLICT', refund_fee: '999.00' }, { platform_status: 'SUCCESS', outcome: 'UPDATED', refund_fee: '35.00' }] } }), '35.00')
assert.equal(h.operationState('ACCEPTED'), '平台已受理')
assert.equal(h.operationState('SUCCESS'), '操作返回成功', 'successful API response is not final fund success')
const operationRefund = { ...pending, platform: { ...pending.platform, version: 2 }, platform_interaction: { operations: [{ action: 'AGREE', state: 'UNKNOWN', source_version: 2 }] } }
assert.equal(h.platformProgress(operationRefund).state, '平台操作结果待确认')
assert.match(h.platformProgress(operationRefund).task.message, /避免重复提交/)
assert.equal(h.platformProgress({ ...operationRefund, platform_interaction: { operations: [{ action: 'AGREE', state: 'SUCCESS', source_version: 2 }] } }).state, '操作已返回，等待平台结果')
assert.match(h.platformProgress({ ...operationRefund, platform_interaction: { operations: [{ action: 'AGREE', state: 'SUCCESS', source_version: 1 }] } }).state, /待平台同意退款/, 'a command for an earlier application version is not the current outcome')
assert.equal(h.platformProgress({ ...operationRefund, platform: { ...operationRefund.platform, platform_status: 'SUCCESS' } }).state, '平台流程已完成')
const unknownDispatch = { ...collecting, progress_context: { plans: [{ id: 11, quantity: 3, dispatch_state: 'UNKNOWN', has_document: true }] } }
assert.match(h.refundProgress(unknownDispatch, gift).title, /下发结果待确认/)
for (const file of ['RefundDetail.vue', 'RefundProgress.vue', 'RefundPlatformProgress.vue', 'RefundGoods.vue', 'OrderGifts.vue']) {
  const component = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(component.template.content).errors, [], file)
  babel.transformSync(component.script.content, { configFile: false, babelrc: false })
}
async function routing() {
  const component = compiler.parseComponent(fs.readFileSync(path.join(root, 'RefundDetail.vue'), 'utf8'))
  const module = { exports: {} }, calls = []
  const code = babel.transformSync(component.script.content, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(name => name === '@/api/order/refund' ? { refundSnapshot: async (...args) => { calls.push(args); return { data: { payload: '{}' } } } } : {}, module, module.exports)
  const methods = module.exports.default.methods
  const context = { business: true, row: { id: 85, platform_refund_id: 901, control_version: 9, purpose: 'UNKNOWN', goods_action: 'UNKNOWN' }, platform: { version: 4, dispute_type: 'REFUND' }, canReview: true, loading: false, $nextTick: async () => {}, $refs: { detailTabs: { $el: { scrollIntoView: () => calls.push('scroll-tabs') } }, goods: { openPlan: async id => calls.push(id), openIssue: id => calls.push('issue' + id) } } }
  context.prepareReview = methods.prepareReview.bind(context)
  context.locateTask = methods.locateTask.bind(context)
  await methods.snapshot.call(context, { id: 66 })
  assert.deepEqual(calls[0], [901, 66], 'business and platform refund IDs are distinct')
  await methods.locateTask.call(context, { tab: 'goods', plan_id: 43, gifts: true })
  assert.equal(context.$refs.goods.planScope, 'gift')
  assert.equal(calls[1], 43)
  await methods.locateTask.call(context, { tab: 'platform' })
  assert.equal(context.detailTab, 'platform')
  assert.equal(calls[2], 'scroll-tabs', 'viewing another progress track brings its content into the visible dialog area')
  await methods.locateTask.call(context, { key: 'review', tab: 'approval' })
  assert.equal(context.reviewVisible, true, 'the review task opens the form directly instead of only changing an offscreen tab')
  assert.equal(context.form.control_version, 9)
  assert.equal(context.form.platform_version, 4, 'adoption still uses the current source and business versions')
  assert.equal(context.confirmed, false, 'opening a review must not submit or confirm it')
  context.reviewVisible = false
  await methods.openTask.call({ ...context, currentTask: { key: 'review', tab: 'approval' } })
  assert.equal(context.reviewVisible, true, 'the footer action uses the same direct review behavior')
  context.reviewVisible = false
  context.canReview = false
  await methods.locateTask.call(context, { key: 'review', tab: 'approval' })
  assert.equal(context.reviewVisible, false, 'a user without review permission can inspect the plan but cannot open its editor')
  assert.equal(context.detailTab, 'approval')
  context.canReview = true
  await methods.openProgressTrack.call(context, { key: 'review', tab: 'approval' })
  assert.equal(context.reviewVisible, false, 'the track detail link remains a read-only navigation action')
}
routing().then(() => console.log('PASS refund progress, platform consent/funds separation, gift loading, stale plans and task navigation')).catch(error => { console.error(error); process.exitCode = 1 })

const mixed = h.refundProgress({ ...done, goods_action: 'MIXED', goods_state: 'WAIT_ACTION', purchase_progress: {}, progress_context: {plans: []}, goods_tasks: [{action: 'CANCEL',state: 'WAIT_WAREHOUSE',result_message: '原仓截单处理中'}]}, gift)
assert.equal(mixed.tracks.find(t => t.key === 'purchase').task.tab, 'current')
assert.equal(mixed.tracks.find(t => t.key === 'purchase').task.focus, 'fulfillment')
