const fs = require('fs')
const assert = require('assert/strict')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/purchase')
function definition(name, api = {}) {
  const c = compiler.parseComponent(fs.readFileSync(path.join(root, name + '.vue'), 'utf8'))
  assert.deepEqual(compiler.compile(c.template.content).errors, [], name)
  const scope = { exports: {}, require: () => ({ checkPermi: () => true, ...api }) }
  vm.runInNewContext(babel.transformSync(c.script.content, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code, scope)
  return scope.exports.default
}
for (const name of ['PurchaseWorkspace', 'ProcurementDetail', 'PurchaseDifferencePanel', 'WarehouseInteractionCell', 'SupplychainOperations', 'Arrivals', 'ReceiptBatches']) definition(name)
const detail = definition('ProcurementDetail')
assert.equal(detail.methods.metricText.call({ summary: { units: [{ unit: '件', planned: 2 }, { unit: '套', planned: 1 }] } }, 'planned'), '2 件 + 1 套')
assert.equal(detail.methods.metricText.call({ summary: { units: [{ unit: '件', confirmed: 0 }] } }, 'confirmed'), '0')
const cell = definition('WarehouseInteractionCell')
let emitted
cell.methods.open.call({ $emit: (name, row) => { emitted = row } }, { id: 10, ticketId: 21, sn: 'CG_TEST' })
assert.equal(emitted.id, 21, 'A SKU row must open its actual warehouse ticket')
async function main() {
  const pending = []
  const ops = definition('SupplychainOperations', { operations: () => new Promise(resolve => pending.push(resolve)) })
  const state = ops.data()
  const first = ops.methods.load.call(state)
  const second = ops.methods.load.call(state)
  pending[1]({ data: { rows: [{ issue_key: 'CURRENT' }], total: 1, counts: { '': 1 } } })
  await second
  pending[0]({ data: { rows: [{ issue_key: 'OLD' }], total: 20, counts: { '': 20 } } })
  await first
  assert.equal(state.rows[0].issue_key, 'CURRENT')
  assert.equal(state.total, 1)
  console.log('PASS supplychain templates, separate units, actual ticket routing and stale-query protection')
}
main().catch(e => { console.error(e); process.exitCode = 1 })
