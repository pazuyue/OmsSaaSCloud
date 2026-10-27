const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(source, api = {}) {
  const scope = { exports: {}, require: () => api, window: { innerHeight: 1100 }, setTimeout, clearTimeout }
  vm.runInNewContext(babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code, scope)
  return scope.exports
}
const p = load(fs.readFileSync(path.join(root, 'returnManagementPresentation.js'), 'utf8'))
const d = load(fs.readFileSync(path.join(root, 'returnDocumentPresentation.js'), 'utf8'))
assert.equal(d.warehouseText({ warehouse_documents: [{ warehouse_state: 'ACCEPTED', interaction: { result: 'FAILED' } }] }), '仓库已受理')
assert.equal(d.receiveLabel({ pending_count: 1, units: [{ quantity: 2, accepted_quantity: 0, posted_quantity: 0 }] }), '回传已接收，实收待归属')
assert.equal(d.postingLabel({ units: [{ quantity: 2, accepted_quantity: 2, posted_quantity: 1 }] }), '已实收，部分待入账')
const products = d.productRows([{ owner_kind: 'REFUND', sku_sn: 'B', unit: '件', quantity: 3, accepted_quantity: 2, posted_quantity: 1, warehouse: { task_id: 1, cancel_state: 'CONFIRMED', cancelled_quantity: 3 } }, { owner_kind: 'EXCHANGE', sku_sn: 'B', unit: '件', quantity: 1, accepted_quantity: 1, posted_quantity: 1, warehouse: { task_id: 2 } }, { owner_kind: 'REFUND', sku_sn: 'B', unit: '套', quantity: 1, accepted_quantity: 0, posted_quantity: 0, warehouse: { task_id: 3 } }], [{ owner_kind: 'REFUND', task_id: 1, good_qty: 1, bad_qty: 1 }, { owner_kind: 'EXCHANGE', task_id: 2, good_qty: 1, bad_qty: 0 }])
assert.equal(products.length, 2, 'Units remain separate even for the same SKU')
assert.equal(products[0].quantity, 4)
assert.equal(products[0].cancelled_quantity, 1, 'A historical cutoff count must not subtract goods received later')
assert.equal(products[0].unposted, 1)
assert.equal(products[0].bad_qty, 1)
assert.equal(products[1].gradesKnown, false, 'Missing quality data is not a known zero')
for (const file of ['ReturnDocumentDetail', 'ReturnReceiptLedger', 'ReturnInteractionPanel', 'ReturnWarehouseStatus']) {
  const component = compiler.parseComponent(fs.readFileSync(path.join(root, file + '.vue'), 'utf8'))
  assert.deepEqual(compiler.compile(component.template.content).errors, [], file)
}
for (const file of ['../purchase/PurchaseWorkspace.vue', 'RefundReturnDocuments.vue', 'ExchangeReturnDocuments.vue']) {
  const component = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(component.template.content).errors, [], file)
}
assert.equal(p.quantityText([{ unit: '件', quantity: 3 }, { unit: '套', quantity: 1 }], 'quantity'), '3 件 + 1 套')
assert.equal(p.quantityText([{ unit: '件', quantity: 0, unknown: 1 }], 'quantity'), '另有数量待确认')
assert.equal(p.quantityText([{ unit: '件', accepted_quantity: 0 }], 'accepted_quantity'), '0')
assert.equal(p.sourceText({ refund_count: 2, exchange_count: 1 }), '退款 2 单 + 换货 1 单')
assert.match(p.nextStep({ stage: 'COLLECT', dispatch_after: '2026-10-25 14:32:00' }), /14:32/)
assert.match(p.nextStep({ stage: 'DONE' }), /退款资金和换出发货/)
assert.equal(p.sourceLink({ owner_kind: 'EXCHANGE', owner_id: 85 }).query.exchangeId, 85)
assert.equal(p.sourceLink({ owner_kind: 'REFUND', owner_id: 133 }).query.id, 133)
assert.equal(p.csvCell('=HYPERLINK("x")'), '"\'=HYPERLINK(""x"")"')
assert.equal(p.csvCell('SO123'), '"SO123"')
const component = compiler.parseComponent(fs.readFileSync(path.join(root, 'Returns.vue'), 'utf8'))
assert.deepEqual(compiler.compile(component.template.content).errors, [])
async function main() {
  const pending = []
  const definition = load(component.script.content, { ...p, returnList: () => new Promise(resolve => pending.push(resolve)) }).default
  const state = { ...definition.data(), filters: () => ({}), sync: {} }
  const one = definition.methods.load.call(state)
  const two = definition.methods.load.call(state)
  pending[1]({ data: { rows: [{ group_key: 'CURRENT' }], total: 1, buckets: {}, synchronization: {} } })
  await two
  pending[0]({ data: { rows: [{ group_key: 'OLD' }], total: 9, buckets: {}, synchronization: {} } })
  await one
  assert.equal(state.rows[0].group_key, 'CURRENT', 'A stale request must not replace current filtered results')
  assert.equal(state.total, 1)
  console.log('PASS return management: physical units, source ownership, collection, CSV safety, template and request race')
}
main().catch(e => { console.error(e); process.exitCode = 1 })
