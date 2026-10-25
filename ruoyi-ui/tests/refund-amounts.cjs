const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(file) {
  const raw = fs.readFileSync(path.join(root, file), 'utf8')
  const sfc = file.endsWith('.vue') ? compiler.parseComponent(raw) : null
  if (sfc) assert.deepEqual(compiler.compile(sfc.template.content).errors, [], file)
  const module = { exports: {} }
  const code = babel.transformSync(sfc ? sfc.script.content : raw, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(() => ({}), module, module.exports)
  return module.exports
}
const { refundAmountSummary } = load('refundAmountPresentation.js')
const context = { authoritative: true, original_goods_payment: '100.00', current_limit: '80.00', other_confirmed: '20.00', other_occupied: '0.00', current_confirmed: '80.00', confirmed_total: '100.00', remaining_unpaid: '0.00' }
let summary = refundAmountSummary({ funds_state: 'NONE', refund_fee: '80.00', amount_context: context })
assert.equal(summary.fields[0].value, '20.00')
assert.equal(summary.fields[1].value, '80.00')
assert.match(summary.fields[1].label, /本次可处理上限/)
assert.equal(summary.fields.length, 2, 'zero in-progress occupation is hidden')
summary = refundAmountSummary({ funds_state: 'SUCCESS', refund_fee: '80.00', amount_context: context })
assert.deepEqual(summary.fields.map(f => f.value), ['20.00', '80.00', '100.00', '0.00'], 'show other refunds + current confirmed amount = cumulative, then remaining')
assert.equal(summary.fields[3].label, '剩余可退', 'completed refunds do not display the pre-adoption limit as remaining refundable money')
assert.equal(summary.hasRelated, true)
assert.equal(summary.occupied.length, 0)
assert.equal(refundAmountSummary({ amount_context: { other_occupied: '3.00' } }).fields[1].label, '其他进行中退款占用')
assert.equal(refundAmountSummary({ amount_context: {} }).occupied[0].value, undefined, 'an unknown occupation must not become zero')
assert.equal(refundAmountSummary({ refund_fee: '35.00', platform: { refund_fee: '40.00' } }).requested, '40.00')
assert.equal(refundAmountSummary({ amount_context: {} }).quantityPayment, undefined, 'partial quantity payment is not averaged in the UI')
assert.match(refundAmountSummary({ amount_context: { authoritative: false } }).paidLabel, /计算参考/)
for (const file of ['RefundAmountBasis.vue', 'RefundAmountRecords.vue', 'RefundApplicationGoods.vue', 'RefundProgress.vue', 'RefundDetail.vue']) load(file)
const detail = load('RefundDetail.vue').default
assert.equal(detail.computed.shopName.call({ channels: [{ channel_id: 83, channel_name: '验收店铺' }], row: { channel_id: 83 } }), '验收店铺')
const progress = load('RefundProgress.vue').default
assert.equal(progress.data().expanded, false, 'the detailed progress remains collapsed to prioritize order and paid amounts')
console.log('PASS refund amount scopes, completed totals, missing allocations, shop identity and compact progress templates')
