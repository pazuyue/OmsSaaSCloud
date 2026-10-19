const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
const context = { exports: {} }
vm.runInNewContext(babel.transformSync(fs.readFileSync(path.join(root, 'refundParcelPresentation.js'), 'utf8'), {
  configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')]
}).code, context)
const h = context.exports
const members = [2, 1, 1].map((quantity, i) => ({ id: i + 11, task_id: i + 101, plan_id: i + 21, refund_id: i + 14, return_sn: 'THK1', sku_sn: 'A', quantity, accepted_quantity: 0, posted_quantity: 0 }))
const receipt = { state: 'WAIT_ALLOCATION', quantity: 3, payload: JSON.stringify({ ticketSn: 'THK1', lines: [{ sku: 'A', lineNo: '', good: 3, bad: 0 }] }) }
const parcel = { members, receipts: [receipt], warehouse_document: { return_sn: 'THK1', cancel_state: 'NONE' } }
assert.equal(h.parcelScope(parcel).planned, 4)
assert.equal(h.parcelScope(parcel, 16).planned, 1)
assert.equal(h.parcelScope(parcel, 16).accepted, 0)
assert.equal(h.parcelPending(parcel), 3)
assert.equal(h.scopePending(parcel, 16), true)
assert.equal(h.receiptText(0, true), '待确定（已确认 0 件）')
assert.match(h.allocationReason(parcel, receipt), /回传同一SKU 3 件.*合计待收 4 件/)
const allocation = [{ line_index: 0, task_id: 101, good_qty: 2, bad_qty: 0 }, { line_index: 0, task_id: 102, good_qty: 1, bad_qty: 0 }]
assert.equal(h.allocationError(parcel, receipt, allocation), '')
assert.match(h.allocationError(parcel, receipt, allocation.slice(0, 1)), /需分配良品 3 件/)
assert.match(h.allocationError(parcel, receipt, [{ line_index: 0, task_id: 102, good_qty: 3, bad_qty: 0 }]), /超过/)
assert.match(h.allocationError(parcel, receipt, [{ line_index: 0, task_id: 999, good_qty: 3, bad_qty: 0 }]), /原THK/)
const exact = { ...receipt, payload: { ticketSn: 'THK1', lines: [{ sku: 'A', lineNo: '13', good: 1, bad: 0 }] } }
assert.equal(h.eligibleSources(parcel, exact, exact.payload.lines[0]).length, 1)
assert.equal(h.scopePending({ ...parcel, receipts: [exact] }, 14), false)
assert.equal(h.scopePending({ ...parcel, receipts: [exact] }, 16), true)
assert.match(h.allocationError(parcel, exact, [{ line_index: 0, task_id: 101, good_qty: 1, bad_qty: 0 }]), /原THK/)
assert.equal(h.scopePending({ ...parcel, receipts: [{ ...receipt, state: 'REJECTED' }] }, 16), false)
const closed = { ...members[2], accepted_quantity: 1 }
assert.equal(h.sourcePending({ ...parcel, members: [closed], receipts: [exact] }, closed), true, 'explicit overreceipt must remain visible on completed source')
const previous = { ...members[0], id: 1, task_id: 90, return_sn: 'THK0', quantity: 2, accepted_quantity: 1, posted_quantity: 1 }
const rebuilt = { ...parcel, members: [previous, { ...members[0], quantity: 1 }, ...members.slice(1)] }
assert.equal(h.parcelScope(rebuilt).planned, 4, 'rebuild must not count original and remaining plan twice')
assert.equal(h.parcelScope(rebuilt, 14).accepted, 1)
assert.equal(h.eligibleSources(rebuilt, receipt, { sku: 'A' }).length, 3, 'a new receipt cannot allocate to an old THK')
for (const file of ['RefundDetail.vue', 'RefundGoods.vue', 'RefundParcels.vue', 'RefundList.vue']) {
  const c = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(c.template.content).errors, [], file)
}
console.log('PASS separate TK/parcel quantities, ambiguous and exact source receipts, conservation and THK scope')

const ruleEvent = { id: 5, state: 'ALLOCATED', allocation_metadata: JSON.stringify({ method: 'RULE_PARTIAL', policy_revision: 3, rows: [{ plan_id: 23, assigned: 0, remaining: 1 }] }) }
assert.match(h.allocationOutcome({ ...parcel, receipts: [ruleEvent] }, 23), /分配 0 件（本次未分配到实收）.*待退回 1 件.*版本 3/)
assert.equal(h.scopePending({ ...parcel, receipts: [ruleEvent] }, 16), false)
assert.match(h.allocationReason(parcel, { ...receipt, allocation_metadata: { reason: '成本不同，需人工核对' } }), /成本不同/)
assert.deepEqual(Object.keys(h.allocationMetadata({ allocation_metadata: '{bad' })), [])
console.log('PASS explicit zero allocation and persisted manual reason')
