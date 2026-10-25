const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(file, vue = false) {
 const raw = fs.readFileSync(path.join(root, file), 'utf8')
 const source = vue ? compiler.parseComponent(raw).script.content : raw
 const module = { exports: {} }
 const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
 new Function('require', 'module', 'exports', code)(() => ({}), module, module.exports)
 return module.exports.default || module.exports
}
for (const name of ['RefundFulfillment.vue', 'RefundGoodsScopeEditor.vue', 'RefundFlowSettings.vue', 'RefundPlatformActions.vue']) {
 const component = compiler.parseComponent(fs.readFileSync(path.join(root, name), 'utf8'))
 assert.deepEqual(compiler.compile(component.template.content).errors, [], name)
 load(name, true)
}
assert.equal((fs.readFileSync(path.join(root, 'RefundDetail.vue'), 'utf8').match(/<refund-fulfillment\b/g) || []).length, 1, 'show the actual fulfillment scope once')
const h = load('refundFulfillmentPresentation.js')
assert.equal(h.fulfillmentAdvice({ refund_fee: '1.00', fulfillment: { stage: 'UNSHIPPED' } }).type, 'info')
assert.equal(h.fulfillmentAdvice({ fulfillment: { stage: 'UNKNOWN', uncertain: true } }).type, 'warning')
assert.equal(h.fulfillmentAdvice({ review_state: 'APPROVED', goods_action: 'KEEP' }).type, 'success')
const editor = load('RefundGoodsScopeEditor.vue', true)
let adopted
const task = { action: 'CANCEL', order_line_id: 15, shipment_line_id: null, quantity: 3, state: 'DONE', plan_input: '{"action":"CANCEL","order_line_id":15,"quantity":3}' }
editor.created.call({ executed: true, activeTasks: [task], parsePlan: editor.methods.parsePlan, $emit: (name, value) => { adopted = value } })
assert.equal(adopted[0].quantity, 3, 're-review retains already executed quantity even when no unshipped goods remain')
assert.equal(adopted[0].order_line_id, 15)
console.log('PASS new refund forms, amount-independent advice and immutable executed re-review scope')
