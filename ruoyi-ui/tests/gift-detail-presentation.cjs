const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const compiler = require('vue-template-compiler')
const babel = require('@babel/core')
const root = path.join(__dirname, '../src/views/oms/order')

function moduleFrom(source, imports = {}) {
  const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  const mod = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => imports[name] || {}, mod, mod.exports)
  return mod.exports
}
const helpers = moduleFrom(fs.readFileSync(path.join(root, 'giftPresentation.js'), 'utf8'))
const detailHelpers = moduleFrom(fs.readFileSync(path.join(root, 'refundDetailPresentation.js'), 'utf8'))
function component(file, imports = {}) {
  const parsed = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [], file)
  return moduleFrom(parsed.script.content, { './giftPresentation': helpers, './refundDetailPresentation': detailHelpers, ...imports }).default
}
function mount(vm, props = {}) {
  const context = { ...vm.data(), ...props }
  for (const [key, value] of Object.entries(vm.methods || {})) if (typeof value === 'function') context[key] = value.bind(context)
  for (const [key, value] of Object.entries(vm.computed || {})) Object.defineProperty(context, key, typeof value === 'function' ? { get: () => value.call(context) } : { get: () => value.get.call(context), set: next => value.set.call(context, next) })
  return context
}

async function main() {
  for (const file of ['GiftProgressCard.vue', 'RefundApplicationGoods.vue', 'RefundGiftLines.vue', 'OrderProductLines.vue', 'BusinessStockDetail.vue', 'OrderLineIdentity.vue', 'OrderIdentity.vue', 'RefundDetail.vue', 'RefundGoods.vue', 'RefundParcels.vue', 'index.vue']) component(file)
  const lines = [
    { id: 1, quantity: 4, gift_state: 'RETURNING', return_required: 2, return_plans: [{ id: 11, refund_id: 9, quantity: 1 }, { id: 12, refund_id: 8, quantity: 1 }] },
    { id: 2, gift_state: 'WAIT_RETURN', unarranged_quantity: 1, return_required: 1 },
    { id: 3, gift_state: 'ELIGIBLE', return_required: 0 },
    { id: 4, gift_state: 'PENDING_REVIEW' }
  ]
  const groups = helpers.giftDisplayGroups(lines, '9')
  assert.deepEqual(groups.map(group => group.lines.map(line => line.id)), [[1], [2, 4], [3]])
  assert.deepEqual(helpers.scopedGiftPlans(lines[0], 9).map(plan => plan.id), [11])
  assert.deepEqual(helpers.giftDisplayGroups(lines, 0)[0].lines.map(line => line.id), [1, 2, 4])
  assert.equal(lines[0].return_required, 2, 'Cumulative entitlement must not be rewritten into a per-refund delta')

  const refundLines = [{ id: 1, quantity: 20, eligible_quantity: 5, returned_quantity: 7, return_required: 8, return_plans: [
    { id: 11, refund_id: 9, quantity: 2, accepted_quantity: 1, posted_quantity: 1, waived_quantity: 0, state: 'ACTIVE', actual: { documents: [{ quantity: 2 }, { quantity: 2 }] } },
    { id: 12, refund_id: 8, quantity: 9, accepted_quantity: 7, posted_quantity: 7, waived_quantity: 0 }
  ] }, { id: 2, gift_state: 'WAIT_STOCK', return_plans: [] }]
  let scoped = detailHelpers.refundGiftRows(refundLines, '9')
  assert.equal(scoped.length, 1)
  assert.deepEqual([scoped[0].planned, scoped[0].accepted, scoped[0].posted, scoped[0].remaining], [2, 1, 1, 1])
  assert.equal(scoped[0].quantity, 20, 'Original gift entitlement remains available separately')
  assert.equal(scoped[0].state, '待退回')
  assert.equal(detailHelpers.refundGiftRows(refundLines, null).length, 0)
  refundLines[0].return_plans[0].actual.parcel_pending_count = 1
  scoped = detailHelpers.refundGiftRows(refundLines, 9)
  assert.equal(scoped[0].pending, true)
  assert.equal(scoped[0].state, '待核对实收')
  delete refundLines[0].return_plans[0].actual.parcel_pending_count
  refundLines[0].return_plans[0].accepted_quantity = 2
  scoped = detailHelpers.refundGiftRows(refundLines, 9)
  assert.equal(scoped[0].remaining, 0)
  assert.equal(scoped[0].posting, 1)
  assert.equal(scoped[0].needs_action, true)
  assert.equal(scoped[0].state, '待入账')
  refundLines[0].return_plans[0] = { id: 11, refund_id: 9, quantity: 2, accepted_quantity: 1, posted_quantity: 1, waived_quantity: 1 }
  scoped = detailHelpers.refundGiftRows(refundLines, 9)
  assert.equal(scoped[0].needs_action, false)
  assert.equal(scoped[0].state, '已处理（含不再追回）')
  assert.equal(detailHelpers.refundCurrentTask({ review_state: 'APPROVED', goods_state: 'DONE', goods_action: 'KEEP' }, [], ''), null, 'Unbound original-order gift tasks must not claim to belong to this refund')
  assert.equal(detailHelpers.refundCurrentTask({ review_state: 'APPROVED', goods_state: 'DONE', goods_action: 'KEEP' }, [{ needs_action: true }], '').gifts, true)

  const oid = '9007199254740993123'
  const application = { id: 9, platform_oid: oid, platform: { platform_quantity: 2, refund_fee: 100 }, lines: [
    { id: 1, platform_oid: oid, goods_name: 'Original', sku_sn: 'A', quantity: 5, cancelled_quantity: 5 },
    { id: 2, platform_oid: oid, goods_name: 'Replacement', sku_sn: 'B', exchange_id: 7 },
    { id: 3, platform_oid: oid, goods_name: 'Gift', sku_sn: 'A', gift_plan_id: 7 }
  ] }
  let requested = detailHelpers.refundApplication(application)
  assert.equal(requested.platform_oid, oid)
  assert.equal(requested.quantity, 2)
  assert.deepEqual(requested.products.map(product => product.name), ['Original'])
  application.platform.platform_quantity = 0
  assert.equal(detailHelpers.refundApplication(application).quantity, 0)
  application.bundles = [{ id: 7, platform_oid: oid, goods_name: 'Kit', sku_sn: 'KIT' }]
  requested = detailHelpers.refundApplication(application)
  assert.equal(requested.unit, '套')
  assert.equal(requested.products.length, 1)
  assert.equal(requested.products[0].sku, 'KIT')
  console.log('PASS separate platform application vs refund-owned gifts, cross-refund quantities, pending receipts, unposted stock, waived returns, bundle units and replacement identity')

  const stock = mount(component('BusinessStockDetail.vue'), { order: { lines: [
    { id: 1, sku_sn: 'SAME', quantity: 4, cancelled_quantity: 1, is_gift: 1 },
    { id: 2, sku_sn: 'SAME', quantity: 2, gift_plan_id: 7 },
    { id: 3, quantity: 1, cancelled_quantity: 1, gift_plan_id: 7 }
  ], after_sales_lines: [{ id: 4, quantity: 9 }] } })
  assert.equal(stock.purchaseQuantity, 3)
  assert.equal(stock.giftQuantity, 3)
  assert.deepEqual(stock.purchaseLines.map(line => line.id), [1], 'Master gift flag must not change sale line identity')
  assert.deepEqual(stock.giftLines.map(line => line.id), [2])
  assert.deepEqual(stock.terminatedLines.map(line => line.id), [3])

  const messages = [], pending = [], loaded = []
  const gifts = component('OrderGifts.vue', { '@/api/order/gift': { orderGifts: id => new Promise(resolve => pending.push({ id, resolve })), giftReturnOptions: async () => ({ data: { warehouses: [] } }) } })
  const ctx = mount(gifts, { orderId: 1, refundId: '9', $message: { warning: message => messages.push(message) }, $emit: (event, data) => loaded.push([event, data]) })
  ctx.row = { id: 7, triggers: [{ id: 8, review_state: 'APPROVED' }, { id: 9, review_state: 'APPROVED' }] }
  await ctx.startReturn({ id: 1, unarranged_quantity: 2, sources: [{ id: 71 }] })
  assert.equal(ctx.form.refund_id, 9)
  assert.equal(ctx.form.shipment_line_id, 71)
  assert.equal(ctx.form.gift_plan_id, 7)
  ctx.row.triggers[1].review_state = 'WAIT_REVIEW'
  ctx.returnVisible = false
  await ctx.startReturn({ unarranged_quantity: 2 })
  assert.equal(ctx.returnVisible, false)
  assert.equal(messages.length, 1)
  const first = ctx.load()
  ctx.orderId = 2
  const second = ctx.load()
  pending[1].resolve({ data: { id: 22 } }); await second
  pending[0].resolve({ data: { id: 11 } }); await first
  assert.equal(ctx.row.id, 22)
  const completedLoads = loaded.filter(([event]) => event === 'loaded')
  assert.equal(completedLoads.length, 1, 'a stale request must not emit a successful loaded result')
  assert.equal(completedLoads[0][1].id, 22, 'load-start events are distinct from the final current-order result')

  let giftReads = 0, detailReads = 0
  const detail = component('RefundDetail.vue', { '@/api/order/refund': { refundDetail: async (_, id) => { detailReads++; return { data: { id, order_id: 1 } } } } })
  const refund = mount(detail, { business: true, $nextTick: async callback => callback && callback(), $refs: { gifts: { load: async () => giftReads++ } }, $emit: () => {} })
  refund.row = { id: 9 }
  refund.detailTab = 'goods'
  await refund.changed('gifts')
  assert.equal(giftReads, 0, 'Gift mutation already refreshed its report')
  assert.equal(refund.detailTab, 'goods')
  await refund.reload()
  assert.equal(giftReads, 1)
  await refund.open(10)
  assert.equal(refund.detailTab, 'current')
  assert.equal(detailReads, 3)

  const actions = []
  const goods = component('RefundGoods.vue', {
    '@/api/order/refund': { refundGoodsAction: async (...args) => actions.push(['refund', ...args]) },
    '@/api/order/gift': { giftReturnAction: async (...args) => actions.push(['gift', ...args]) }
  })
  const executor = mount(goods, { refund: { id: 9, control_version: 17 }, $auth: { hasPermi: () => true }, $emit: () => {} })
  const plan = { id: 11, gift_plan_id: 7, control_version: 4 }
  await executor.command(plan, null, 'query')
  executor.$auth.hasPermi = () => false
  await executor.command(plan, null, 'query')
  executor.$auth.hasPermi = () => true
  await executor.command(plan, null, 'waive')
  assert.deepEqual(actions.map(row => row[0]), ['gift', 'refund', 'refund'])
  assert.ok(actions.every(row => row[5].control_version === 17 && row[5].plan_version === 4))

  for (const file of ['RefundGoods.vue', 'RefundParcels.vue']) {
    const view = component(file), calls = []
    const inactive = { active: false, dirty: false, load: () => calls.push('read') }
    view.watch.refund.call(inactive)
    assert.equal(calls.length, 0)
    assert.equal(inactive.dirty, true)
    inactive.active = true
    view.watch.active.call(inactive, true)
    assert.equal(calls.length, 1)
    view.watch.active.call(inactive, true)
    assert.equal(calls.length, 1)
  }
  const workspace = mount(component('index.vue'))
  workspace.detailSection = 'fulfilment'; assert.equal(workspace.detailTab, 'shipments')
  workspace.detailTab = 'allocations'; assert.equal(workspace.detailSection, 'fulfilment')
  workspace.detailTab = 'exchanges'; assert.equal(workspace.detailSection, 'after-sale')
  workspace.detailSection = 'after-sale'; assert.equal(workspace.detailTab, 'exchanges', 'Reactivating a parent section preserves the selected child tab')
  workspace.detailTab = 'lines'; workspace.detailSection = 'after-sale'; assert.equal(workspace.detailTab, 'refunds')
  console.log('PASS refund-owned plans vs cumulative gifts, purchase/gift identities, approved refund binding, stale loads, refresh reuse and lazy detail sections')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
