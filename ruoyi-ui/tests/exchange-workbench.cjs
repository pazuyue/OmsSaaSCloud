const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
function load(source, api = {}) {
  const context = { exports: {}, require: () => api }
  vm.runInNewContext(babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code, context)
  return context.exports
}
const root = path.join(__dirname, '../src/views/oms/order')
const presentation = load(fs.readFileSync(path.join(root, 'exchangePresentation.js'), 'utf8'))
const base = { fulfillment_mode: 'RETURN', source_kind: 'PLATFORM', state: 'APPROVED', approved_version: 2, platform_status: '1', platform_operation: 'NONE', requested_quantity: 2 }
assert.equal(presentation.unshipped({source_kind:'INTERNAL',fulfillment_mode:'RETURN'}),false)
assert.equal(presentation.phaseName({source_phase:'UNKNOWN'}),'阶段待核对')
assert.equal(presentation.progress({...base,source_kind:'INTERNAL',fulfillment_mode:'RETURN'}).action,'advance')
assert.equal(presentation.progress({...base,source_kind:'INTERNAL',fulfillment_mode:'RETURN',return_sn:'THX',return_result:{posted_quantity:2},return_qc:'PASSED',fulfillment_line_id:3,allocation_status:'FULL'}).action,'shipments')
assert.equal(presentation.progress({...base,linked_exchange_id:7}).action,null)
assert.equal(presentation.progress({...base,source_kind:'INTERNAL',fulfillment_mode:'RETURN',return_qc:'ISSUE'}).step,1)
assert.equal(presentation.progress({ ...base, platform_operation: 'UNKNOWN' }).action, 'reconcile')
assert.equal(presentation.progress({ ...base, state: 'COMPLETED', platform_status: '13' }).action, null)
assert.equal(presentation.progress({ ...base, state: 'WAIT_STOCK', platform_operation: 'STOCK_WAIT' }).action, 'advance')
assert.equal(presentation.progress({ ...base, speed_exchange: 1 }).action, null)
assert.equal(presentation.progress({ ...base, source_kind: 'INTERNAL', fulfillment_mode: 'UNSHIPPED', fulfillment_line_id: 3 }).action, 'order')
assert.equal(presentation.progress({ ...base, platform_status: '3', return_sn: 'THX', return_result: { posted_quantity: 1 } }).action, null)
assert.equal(presentation.progress({ ...base, platform_status: '3', return_sn: 'THX', return_result: { posted_quantity: 2 }, return_qc: 'PENDING' }).action, 'qc')
for (const file of ['ExchangeContinuation.vue', 'ExchangeRounds.vue', 'ExchangeControls.vue', 'exchanges.vue', 'ExchangeProductPicker.vue', 'ExchangeSourcePicker.vue', 'ExchangeReturns.vue', 'OrderExchangeRecords.vue', 'OrderLineIdentity.vue', 'OrderAfterSaleLines.vue', 'BusinessStockDetail.vue', 'index.vue']) {
  const component = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(component.template.content).errors, [], file + ' template')
}
const component = compiler.parseComponent(fs.readFileSync(path.join(root, 'ExchangeProductPicker.vue'), 'utf8'))
let resolveLookup, emits = 0
const definition = load(component.script.content, { ...presentation, exchangeGoodsSearch: () => new Promise(resolve => { resolveLookup = resolve }) }).default
async function main() {
  const workspace = load(compiler.parseComponent(fs.readFileSync(path.join(root, 'exchanges.vue'), 'utf8')).script.content, presentation).default
  const form = { createForm: {}, createReturnSources: [{source_shipment_line_id:57, order_line_id:71, platform_oid:'9001234567890123456'}] }
  workspace.methods.selectCreateSource.call(form,57)
  assert.equal(form.createForm.platform_oid,'9001234567890123456','Shipment selection uses the emitted ID before model listeners settle')
  assert.equal(form.createForm.order_line_id,71)

  const identity = load(compiler.parseComponent(fs.readFileSync(path.join(root, 'OrderLineIdentity.vue'), 'utf8')).script.content, presentation).default
  const c = identity.computed
  assert.equal(c.identity.call({ line: { business_type: 'EXCHANGE', exchange_id: 2, exchange_phase:'AFTER_SALE' }, terminated: false }), '售后换出商品')
  assert.equal(c.identity.call({line:{business_type:'EXCHANGE',exchange_phase:'DURING_SALE'},terminated:false}),'售中换出商品')
  assert.equal(c.identity.call({ line: { business_type: 'SALE', exchange_id: 2 }, terminated: false }), '换货后发出 · 原单履约')
  assert.equal(c.identity.call({ line: { was_replaced: true }, terminated: true }), '已替换 · 不再发货')
  assert.equal(c.identity.call({ line: { was_replaced: false }, terminated: true }), '需求已终止')

  const picker = { ...definition.data(), keyword: 'A', quantity: 9, context: () => ({ exchange_id: 1 }), $emit: () => { emits++ } }
  const pending = definition.methods.search.call(picker, true)
  picker.keyword = ''
  await definition.methods.search.call(picker, true)
  resolveLookup({ data: { rows: [{ skuSn: 'A' }], total: 1 } })
  await pending
  assert.equal(picker.rows.length, 0, 'Old results cannot overwrite a cleared search')
  assert.equal(picker.quantity, 9, 'Search never reduces the platform quantity')
  assert.equal(emits, 0, 'Search never silently selects a SKU')
  console.log('PASS exchange workbench: operation guards, templates, stale search and immutable quantity')
}
main().catch(error => { console.error(error); process.exit(1) })
