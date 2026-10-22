const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
const context = { exports: {} }
vm.runInNewContext(babel.transformSync(fs.readFileSync(path.join(root, 'refundReturnPresentation.js'), 'utf8'), {
  configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')]
}).code, context)
const h = context.exports
const rows = [
  { id: 1, plan_id: 1, refund_id: 85, task_id: 101, quantity: 3, accepted_quantity: 2, posted_quantity: 1, stock_unit: '件', return_sn: 'THK1', sku_sn: 'A' },
  { id: 2, plan_id: 2, refund_id: 85, task_id: 102, quantity: 1, accepted_quantity: 1, posted_quantity: 1, stock_unit: '套', return_sn: 'THK1', sku_sn: 'S' },
  { id: 3, plan_id: 3, refund_id: 86, task_id: 103, quantity: 2, accepted_quantity: 0, posted_quantity: 0, stock_unit: '件', return_sn: 'THK1', sku_sn: 'A' }
]
assert.equal(h.quantityText(rows), '5 件 + 1 套')
assert.equal(h.quantityText(rows, 'posted_quantity'), '1 件 + 1 套')
assert.equal(h.quantityText(rows, r => r.quantity - r.accepted_quantity), '3 件')
assert.equal(h.quantityText(rows.map(r => ({ ...r, quantity: 0 }))), '0')
assert.equal(h.quantityText([{ quantity: 2 }]), '2 库存单位', 'missing metadata must never invent a piece unit')
const parcel = { id: 1, members: rows, warehouse_document: { return_sn: 'THK1', cancel_state: 'NONE', dispatch_state: 'ACCEPTED' } }
const duplicatePlanDoc = { id: 1, actual: { shared_document: true, documents: [{ return_sn: 'THK1' }] } }
let docs = h.returnDocuments([duplicatePlanDoc], [parcel], 85)
assert.equal(docs.length, 1, 'the same shared ticket must appear once')
assert.equal(h.quantityText(docs[0].rows), '5 件 + 1 套')
assert.equal(h.quantityText(docs[0].ownRows), '3 件 + 1 套', 'other refund must not enter own scope')
const rebuilt = [...rows, { ...rows[0], id: 4, task_id: 104, quantity: 1, accepted_quantity: 1, posted_quantity: 1, return_sn: 'THK2' }]
assert.equal(h.quantityText(h.planScopeRows(rebuilt)), '5 件 + 1 套', 'rebuild must not increase the plan scope')
assert.equal(h.quantityText(h.planScopeRows(rebuilt), 'accepted_quantity'), '3 件 + 1 套')
docs = h.returnDocuments([], [{ ...parcel, members: rebuilt, warehouse_document: { return_sn: 'THK2' } }], 85)
assert.equal(docs.length, 2)
assert.equal(docs[0].return_sn, 'THK2')
assert.equal(docs[1].current, false, 'earlier ticket remains clearly distinct')
assert.equal(h.dispatchText({ dispatch_state: 'ACCEPTED', cancel_state: 'NONE' }), '仓库已接单')
assert.equal(h.receiveText({ quantity: 3, accepted_quantity: 0 }), '待收货')
assert.equal(h.receiveText({ quantity: 3, accepted_quantity: 2, receipt_state: 'COMPLETED' }), '收货结束，存在差异')
assert.equal(h.receiveText({ quantity: 3, accepted_quantity: 3, cancel_state: 'CONFIRMED' }), '全部实收')
assert.equal(h.postingText({ accepted_quantity: 3, posted_quantity: 1 }), '部分入账')
assert.equal(h.postingText({ accepted_quantity: 0, posted_quantity: 0 }), '暂无实收')
assert.equal(h.quantityText(h.receiptQuantities(parcel, [{ payload: { ticketSn: 'THK1', lines: [{ sku: 'A', good: 2 }, { sku: 'S', good: 1 }] } }])), '2 件 + 1 套')
assert.equal(h.quantityText(h.receiptQuantities({ ...parcel, members: [...rows, { ...rows[1], sku_sn: 'A' }] }, [{ payload: { ticketSn: 'THK1', lines: [{ sku: 'A', good: 2 }] } }])), '2 库存单位')
for (const file of ['RefundGoods.vue', 'RefundParcels.vue', 'RefundReturnDocuments.vue', 'RefundDetail.vue']) {
  const component = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(component.template.content).errors, [], file)
}
console.log('PASS mixed stock units, shared/own document scope, rebuilt tickets and separate receipt/posting states')
