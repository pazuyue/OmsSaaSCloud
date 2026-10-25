const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
const calls = []
function load(name) {
  const file = ['.js', '.vue'].map(ext => path.join(root, name + ext)).find(fs.existsSync)
  const raw = fs.readFileSync(file, 'utf8')
  const component = file.endsWith('.vue') ? compiler.parseComponent(raw) : null
  if (component) assert.deepEqual(compiler.compile(component.template.content).errors, [], name)
  const module = { exports: {} }
  const code = babel.transformSync(component ? component.script.content : raw, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(dependency => {
    if (dependency.startsWith('./')) return dependency === './refundAmountPresentation' || dependency === './orderAmounts' || dependency === './refundLabels' ? load(dependency.slice(2)) : {}
    if (dependency === '@/api/order/refund') return { refundReview: async (...args) => calls.push(args) }
    return {}
  }, module, module.exports)
  return module.exports
}
const { refundAmountConflict } = load('refundAmountPresentation')
const refund = { id: 122, state: 'NEEDS_REVIEW', funds_state: 'NONE', review_state: 'PENDING', platform: { version: 1, refund_fee: '100.00', platform_status: 'WAIT_SELLER_AGREE' }, amount_context: { reliable: true, original_goods_payment: '100.00', other_confirmed: '20.00', other_occupied: '0.00', current_limit: '80.00' } }
assert.equal(refundAmountConflict(refund).excess, 20)
assert.equal(refundAmountConflict({ ...refund, platform: { ...refund.platform, refund_fee: '80.01' } }).excess, 0.01)
assert.equal(refundAmountConflict({ ...refund, platform: { ...refund.platform, refund_fee: '80.00' } }), null)
assert.equal(refundAmountConflict({ ...refund, funds_state: 'SUCCESS' }), null)
assert.equal(refundAmountConflict({ ...refund, platform: { platform_status: 'CLOSED' } }), null)
assert.equal(refundAmountConflict({ ...refund, amount_context: { ...refund.amount_context, reliable: false } }), null, 'an uncertain budget must not become a definitive excess claim')
assert.equal(refundAmountConflict({ ...refund, amount_context: { reliable: true, current_limit: null } }), null)
assert.equal(refundAmountConflict({ ...refund, refund_fee: '80.00' }).requested, 100, 'the latest platform amount takes precedence over an older adopted amount')
load('RefundAmountResolution')
const progress = load('refundProgressPresentation').refundProgress(refund, { available: true, lines: [] })
assert.equal(progress.primary.key, 'amount')
assert.equal(progress.primary.action, '处理金额冲突')
assert.equal(progress.primary.focus, 'amount-resolution', 'the primary task must lead to resolution instead of repeating an impossible review')
const detail = load('RefundDetail').default
const actions = load('RefundPlatformActions').default
async function main() {
  const ctx = { row: refund, $message: { warning: value => calls.push(value) } }
  await detail.methods.review.call(ctx)
  assert.equal(calls.length, 1)
  assert.equal(typeof calls[0], 'string', 'blocked review must not invoke the review API')
  let destination
  detail.methods.openAmountPlatform.call({ reviewVisible: true, locateTask: task => { destination = task } })
  assert.deepEqual(destination, { tab: 'platform' }, 'manual resolution navigates to platform processing without submitting an operation')
  const actionContext = { refund, $message: ctx.$message, information: async code => { calls.push('read-' + code); return {} } }
  actionContext.amountBlocked = actions.methods.amountBlocked.bind(actionContext)
  assert.equal(actionContext.amountBlocked('PAY'), true)
  assert.equal(actionContext.amountBlocked('AGREE_RETURN'), true)
  assert.equal(actionContext.amountBlocked('REFUSE_RETURN'), false, 'refusing an over-limit request must not require approving it first')
  assert.equal(actionContext.amountBlocked('NEGOTIATE_SUBMIT'), false)
  await actions.methods.open.call(actionContext, 'PAY')
  assert.equal(actionContext.visible, undefined)
  actionContext.action = 'PAY'
  await actions.methods.submit.call(actionContext)
  await actions.methods.requestCode.call(actionContext)
  assert.equal(actionContext.busy, undefined, 'a stale payment dialog and code request must remain blocked before any API call')
  await actions.methods.open.call(actionContext, 'REFUSE_RETURN')
  assert.equal(actionContext.visible, true)
  assert.equal(actionContext.action, 'REFUSE_RETURN')
  assert.equal(calls.at(-1), 'read-REASONS', 'opening refusal only queries reasons; it never submits refusal')
  actionContext.refund = { ...refund, platform: { ...refund.platform, refund_fee: '80.00' } }
  assert.equal(actionContext.amountBlocked('AGREE_RETURN'), false, 'a synchronized corrected request can proceed under the latest server checks')
  console.log('PASS amount-conflict resolution, cent precision, manual navigation, safe refusal access, and stale payment guards')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
