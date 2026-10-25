const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(name, imports = {}) {
  const raw = fs.readFileSync(path.join(root, name), 'utf8')
  const sfc = name.endsWith('.vue') ? compiler.parseComponent(raw) : null
  if (sfc) assert.deepEqual(compiler.compile(sfc.template.content).errors, [], name)
  const module = { exports: {} }
  const code = babel.transformSync(sfc ? sfc.script.content : raw, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(n => imports[n] || {}, module, module.exports)
  return module.exports
}
const h = load('refundLogisticsPresentation.js')
assert.equal(h.interceptApplication({ state: 'SUCCESS' }).label, '平台已受理拦截申请')
assert.equal(h.reportedOutcome({}).label, '尚未取得快递拦截结果')
assert.match(h.reportedOutcome({ logistic_status: 'DELIVERY_INTERCEPT_SUCCESS', confirmed: false }).label, /回告待确认/)
assert.match(h.reportedOutcome({ logistic_status: 'DELIVERY_INTERCEPT_SUCCESS', confirmed: true }).source, /商家核实.*退款级/)
const rows = [
  { plan_id: 1, item_kind: 'PURCHASE', quantity: 2, logistics: { state: 'BOUND', parcel_id: 31 }, documents: [{ return_sn: 'THK1' }] },
  { plan_id: 2, item_kind: 'GIFT', quantity: 1, logistics: { state: 'BOUND', parcel_id: 31 }, documents: [{ return_sn: 'THK1' }] },
  { plan_id: 3, state: 'STOPPED', logistics: { state: 'BOUND', parcel_id: 31 } },
  { plan_id: 4, logistics: { state: 'PROVIDED', waybill: 'SAME' } },
  { plan_id: 5, logistics: { state: 'PROVIDED', waybill: 'SAME' } }
]
const groups = h.groupReturns(rows)
assert.equal(groups.length, 3, 'unconfirmed matching numbers cannot imply an associated parcel')
assert.equal(groups[0].rows.length, 2)
assert.deepEqual(groups[0].rows.map(r => [r.item_kind, r.quantity]), [['PURCHASE', 2], ['GIFT', 1]])
assert.equal(groups[0].documents.length, 1, 'one shared THK should appear once')
assert.equal(h.groupReturns([{ ...rows[2], accepted_quantity: 1 }])[0].rows.length, 1, 'retain received evidence on stopped plans')
for (const file of ['RefundLogistics.vue', 'RefundDispositionResults.vue', 'BusinessRefundList.vue', 'RefundParcels.vue']) load(file)
const component = load('RefundLogistics.vue', { './refundLogisticsPresentation': h }).default
assert.equal(component.computed.canReport.call({ value: { goods_done: true }, refund: {} }), false)
assert.match(component.methods.missingMessage({ rows: [{ dispatch_state: 'WAIT_LOGISTICS' }] }), /下发正在等待退回物流/)
assert.equal(component.methods.time(null), '未单独记录')
assert.match(component.computed.simulation.call({ value: { platform_mode: 'SIMULATION' } }), /当前平台接入/)
console.log('PASS refund logistics: distinct request/outcome, source times, confirmed grouping, own quantities, missing waybill and SFC compilation')
