const assert = require('assert/strict'), fs = require('fs'), path = require('path')
const babel = require('@babel/core'), compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function readModule(source, imports = {}) {
  const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  const mod = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => imports[name] || {}, mod, mod.exports)
  return mod.exports
}
const amounts = readModule(fs.readFileSync(path.join(root, 'orderAmounts.js'), 'utf8'))
const h = readModule(fs.readFileSync(path.join(root, 'orderSalesPresentation.js'), 'utf8'), { './orderAmounts': amounts })
const oid = '9007199254740993123'
const bag = { id: 7, business_type: 'LUCKY_BAG', platform_oid: oid, sku_sn: 'FD', goods_name: '福袋', quantity: 2, payment: '10.00', shipped_sets: 2, units: [
  { unit_id: 104, unit: 1, components: [{ sku_sn: 'B', quantity: 1 }, { sku_sn: 'D', quantity: 1 }], shipped_complete: true },
  { unit_id: 105, unit: 2, components: [{ sku_sn: 'B', quantity: 1 }, { sku_sn: 'D', quantity: 1 }], shipped_complete: true, return_refund_id: 80 }
] }
const order = { id: 1, platform_tid: 'T1', bundles: [bag], lines: [
  { id: 1, platform_oid: oid, bundle_id: 7, sku_sn: 'B', goods_name: '上装B', quantity: 2, payment: 0, shipped_quantity: 2 },
  { id: 2, platform_oid: oid, bundle_id: 7, sku_sn: 'D', goods_name: '下装D', quantity: 2, payment: 0, shipped_quantity: 2 },
  { id: 3, platform_oid: 'N1', sku_sn: 'A', goods_name: '原购商品A', quantity: 3, cancelled_quantity: 1, shipped_quantity: 2, payment: '8.00', is_gift: 1 },
  { id: 4, platform_oid: 'N1', sku_sn: 'C', goods_name: '换品C', quantity: 1, shipped_quantity: 1, payment: 0, exchange_id: 9 },
  { id: 5, platform_oid: '', sku_sn: 'B', goods_name: '同SKU赠品', quantity: 2, payment: 0, gift_plan_id: 5 }
] }
const snapshot = JSON.stringify(order)
let rows = h.salesRows(order)
assert.equal(rows.length, 2, 'one bag parent and one original sale; components, replacements and gifts must not become duplicate sales')
assert.equal(rows[0].oid, oid, 'large platform IDs must stay as strings')
assert.equal(rows[0].quantity, 2); assert.equal(rows[0].physical_quantity, 4); assert.equal(rows[0].unit, '袋')
assert.equal(rows[0].return_units.length, 1)
assert.equal(rows[1].quantity, 3); assert.equal(rows[1].physical_quantity, 3); assert.equal(rows[1].canceled, 0)
assert.equal(rows[1].has_replacement, true); assert.equal(rows[1].name, '原购商品A')
assert.deepEqual(h.salesSummary(rows), { quantity_text: '2袋、3件', components: 4, payment: '18.00' })
assert.equal(JSON.stringify(order), snapshot, 'presentation must not rewrite original order quantities or amounts')
const origin = h.salesOrigin(order.lines[0], order.bundles)
assert.deepEqual(origin.units.map(u => u.unit_id), [104, 105], 'aggregate B2 must reference both original bags')
assert.equal(origin.units.reduce((sum, u) => sum + u.quantity, 0), 2)

const mixed = { bundles: [{ id: 8, business_type: 'BUNDLE', platform_oid: 'V1', sku_sn: 'V', goods_name: '虚拟组合', quantity: 2, payment: '12.00', shipped_sets: 1 }], lines: [
  { id: 10, bundle_id: 8, platform_oid: 'V1', sku_sn: 'A', quantity: 4, component_quantity: 2, shipped_quantity: 2, payment: 0 },
  { id: 11, platform_oid: 'S1', sku_sn: 'S', goods_name: '实物组合', quantity: 3, shipped_quantity: 1, payment: '15.00', stocked_bundle_snapshot: JSON.stringify({ inventory_mode: 'STOCKED', components: [{ sku_sn: 'A', quantity: 2 }] }) }
] }
rows = h.salesRows(mixed)
assert.deepEqual(rows.map(r => r.kind), ['VIRTUAL_BUNDLE', 'STOCKED_BUNDLE'])
assert.deepEqual(rows.map(r => r.unit), ['套', '套'])
assert.equal(h.physicalUnit(mixed.lines[1]), '套')
assert.deepEqual(h.salesSummary(rows), { quantity_text: '5套', components: 4, payment: '27.00' })
assert.equal(rows[1].recipe.components[0].quantity, 2)
assert.equal(rows[0].pending, 1); assert.equal(rows[1].pending, 2)
const canceled = h.salesRows({ ...order, status: 'VOIDED' })
assert.equal(canceled[0].pending, 0); assert.equal(canceled[0].progress, '已作废')
const splitSources = { platform_tid: 'T1', warehouse_allocations: [
  { platform_tid: 'T1', platform_oid: oid, sku_sn: 'B', batch_code: 'ORIGINAL', store_code: 'W1', real_store_name: '主仓' },
  { platform_tid: 'AFTER1', platform_oid: oid, sku_sn: 'B', batch_code: 'AFTER', store_code: 'W2' }
] }
assert.deepEqual(h.physicalSources(splitSources, order.lines[0]).map(r => r.batch_code), ['ORIGINAL'])
assert.equal(h.warehouseName(splitSources, 'W1'), '主仓')
const returnedOrder = { ...mixed, purchase_returns: [
  { id: 1, order_line_id: 10, platform_oid: 'V1', refund_id: 80, refund_sn: 'TK80', quantity: 2, accepted_quantity: 2, posted_quantity: 2, state: 'STOPPED', stock_unit: '件' },
  { id: 2, order_line_id: 11, platform_oid: 'S1', refund_id: 81, refund_sn: 'TK81', quantity: 1, accepted_quantity: 1, posted_quantity: 1, state: 'CLOSED', stock_unit: '套' },
  { id: 3, order_line_id: 999, platform_oid: 'V1', refund_id: 80, quantity: 99, posted_quantity: 99, state: 'CLOSED', stock_unit: '件' }
] }
rows = h.salesRows(returnedOrder)
assert.equal(rows[0].returns.title, '已入库 2 件', 'virtual kit returns use actual components, excluding same-SKU gift sources')
assert.equal(rows[1].returns.title, '已入库 1 套', 'stocked kits restore finished sets without expanding recipes')
assert.deepEqual(rows[0].returns.details, [], 'a fully received STOPPED plan must not be shown as still waiting')
assert.deepEqual(rows[0].returns.refunds, [{ id: 80, sn: 'TK80' }])
const partial = h.purchaseReturnProgress({ purchase_returns: [
  { order_line_id: 1, platform_oid: oid, refund_id: 80, quantity: 3, accepted_quantity: 2, posted_quantity: 1, state: 'ACTIVE', stock_unit: '件' },
  { order_line_id: 2, platform_oid: oid, refund_id: 80, quantity: 2, accepted_quantity: 0, posted_quantity: 0, state: 'STOPPED', stock_unit: '件' }
] }, h.salesRows(order)[0])
assert.equal(partial.title, '已入库 1 件', 'one returned component must never imply one entire bag returned')
assert.deepEqual(partial.details, ['待入账 1 件', '待退回 1 件', '已停止 2 件'])
assert.equal(partial.refunds.length, 1, 'multiple components link the original refund once')
const pendingReview = h.purchaseReturnProgress({ purchase_returns: [{ order_line_id: 1, platform_oid: oid, refund_id: 80, quantity: 1, accepted_quantity: 1, posted_quantity: 1, state: 'WAIT_REVIEW', stock_unit: '件' }] }, h.salesRows(order)[0])
assert.equal(pendingReview.title, '退回待核对', 'late receipt exceptions remain visible after posting')
assert.deepEqual(pendingReview.details, ['已入库 1 件'])
for (const file of ['OrderSalesProducts.vue', 'OrderSalesBundles.vue', 'OrderProductLines.vue', 'OrderPhysicalSources.vue', 'OrderLineIdentity.vue', 'BusinessStockDetail.vue']) {
  const c = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(c.template.content).errors, [], file)
  readModule(c.script.content, { './orderSalesPresentation': h, './orderAmounts': amounts })
}
const workspace = readModule(compiler.parseComponent(fs.readFileSync(path.join(root, 'index.vue'), 'utf8')).script.content).default
const navigation = { detailTab: 'inventory' }
workspace.computed.detailSection.set.call(navigation, 'fulfilment')
assert.equal(navigation.detailTab, 'inventory', 'activating the parent tab must preserve the requested inventory subtab')
navigation.detailTab = 'lines'; workspace.computed.detailSection.set.call(navigation, 'fulfilment')
assert.equal(navigation.detailTab, 'shipments')
console.log('PASS sales parents, bag/set/piece counts, one-time payments, gifts, replacements, original bag membership, shipment scope and six Vue components')
