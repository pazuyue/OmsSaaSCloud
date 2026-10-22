const assert = require('assert/strict'), fs = require('fs'), path = require('path'), vm = require('vm')
const babel = require('@babel/core'), compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/goods')
const transform = source => babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
const presentation = { exports: {} }
vm.runInNewContext(transform(fs.readFileSync(path.join(root, 'luckyBagPresentation.js'), 'utf8')), presentation)
const p = presentation.exports
const form = p.emptyLuckyBag()
assert.equal(p.luckyValidation(form).tab, 'contents')
form.groups = [{ key: 'top', name: '上装', candidates: [{ sku_sn: 'A', weight: 1, clearance_priority: 0, fixed_quantity: null }] }, { key: 'bottom', name: '下装', candidates: [{ sku_sn: 'B', weight: 1, clearance_priority: 0, fixed_quantity: null }] }]
assert.equal(p.luckyValidation(form), null)
assert.ok(p.luckySummary(form).includes('每袋 2 件'))
form.groups[1].candidates[0].sku_sn = 'A'
assert.equal(p.luckyValidation(form).tab, 'contents')
form.groups[1].candidates[0].sku_sn = 'B'
form.max_cost = 10
assert.equal(p.luckyValidation(form).tab, 'stock')
form.mode = 'PRESET'; form.preset_quantity = 10
assert.equal(p.luckyValidation(form), null)
form.groups[0].candidates[0].fixed_quantity = 9
assert.equal(p.luckyValidation(form).tab, 'contents')
form.groups[0].candidates[0].fixed_quantity = 10
assert.equal(p.luckyValidation(form), null)

// Distinguish incomplete settings from an actual inventory trial.
const quotaForm = { ...p.emptyLuckyBag(), mode: 'PRESET', preset_quantity: 4, groups: [{ key: 'top', name: '上装', candidates: [{ sku_sn: 'A', weight: 1, fixed_quantity: 0 }, { sku_sn: 'B', weight: 1, fixed_quantity: 0 }] }] }
const quotaGroup = quotaForm.groups[0]
assert.match(p.luckyValidation(quotaForm).message, /所有候选商品都设为“不选”/)
quotaGroup.candidates[0].fixed_quantity = 2; quotaGroup.candidates[1].fixed_quantity = 1
assert.match(p.luckyQuotaSummary(quotaForm, quotaGroup).issue, /已固定3袋，还有1袋/)
quotaGroup.candidates[1].fixed_quantity = null
assert.equal(p.luckyValidation(quotaForm), null)
assert.equal(p.luckyQuotaSummary(quotaForm, quotaGroup).remaining, 2)
assert.match(p.luckyQuotaSummary(quotaForm, quotaGroup).title, /尚未检查库存/)
quotaGroup.candidates[1].fixed_quantity = 3
assert.match(p.luckyValidation(quotaForm).message, /超过每批4袋，请减少1袋/)
quotaGroup.candidates[1].fixed_quantity = 2
assert.equal(p.luckyValidation(quotaForm), null)
quotaForm.groups.push({ key: 'bottom', name: '下装', candidates: [{ sku_sn: 'D', weight: 1, fixed_quantity: 3 }] })
assert.match(p.luckyValidation(quotaForm).message, /下装：已固定3袋，还有1袋/)
quotaForm.groups[1].candidates[0].fixed_quantity = 4
assert.equal(p.luckyValidation(quotaForm), null)

const snapshot = { store_code: 'W1', code: 'CHECKED', components: [{ sku_sn: 'A', available: 9 }, { sku_sn: 'B', available: 10 }, { sku_sn: 'D', available: 9 }] }
const largeForm = JSON.parse(JSON.stringify(quotaForm)); largeForm.preset_quantity = 200
largeForm.groups[0].candidates.forEach(c => { c.fixed_quantity = 100 }); largeForm.groups[1].candidates[0].fixed_quantity = 200
const largeCheck = p.luckyStockPrecheck(largeForm, snapshot, 200)
assert.equal(largeCheck.code, 'STOCK_SHORTAGE'); assert.equal(largeCheck.shortage, 372)
assert.equal(largeCheck.groups[0].candidates[0].shortage, 91)
assert.equal(largeCheck.groups[1].candidates[0].shortage, 191)
const flexibleForm = JSON.parse(JSON.stringify(quotaForm)); flexibleForm.preset_quantity = 25
flexibleForm.groups.forEach(g => g.candidates.forEach(c => { c.fixed_quantity = null }))
const flexibleCheck = p.luckyStockPrecheck(flexibleForm, snapshot, 25)
assert.equal(flexibleCheck.groups[0].flexible_shortage, 6)
assert.equal(flexibleCheck.groups[0].candidates[0].required, null)
assert.equal(flexibleCheck.groups[0].candidates[0].shortage, null)
assert.equal(p.luckyStockPrecheck(quotaForm, { code: 'CHECK_FAILED' }, 4), null)

const component = compiler.parseComponent(fs.readFileSync(path.join(root, 'LuckyBagEditor.vue'), 'utf8'))
assert.deepEqual(compiler.compile(component.template.content).errors, [])
const requests = [], loadResolvers = [], script = { exports: {}, require: name => name.includes('luckyBagPresentation') ? p : name.includes('permission') ? { checkPermi: () => true } : name.includes('goods/info') ? { getInfo: id => Promise.resolve({ data: { id, skuSn: 'FD' + id } }), listInfo: () => Promise.resolve({ rows: [], total: 0 }) } : { default: options => { requests.push(options); return new Promise(resolve => loadResolvers.push(resolve)) } } }
// Babel interop treats the request function as the module default.
script.require = name => name.includes('luckyBagPresentation') ? p : name.includes('permission') ? { checkPermi: () => true } : name.includes('goods/info') ? { getInfo: id => Promise.resolve({ data: { id, skuSn: 'FD' + id } }), listInfo: () => Promise.resolve({ rows: [], total: 0 }) } : options => { requests.push(options); return new Promise(resolve => loadResolvers.push(resolve)) }
script.setTimeout = setTimeout; script.clearTimeout = clearTimeout
vm.runInNewContext(transform(component.script.content), script)
const c = script.exports.default
const ctx = { ...c.data(), $route: { query: { id: '1' } }, $message: { warning: () => {}, success: () => {} }, $set: (obj, key, value) => { obj[key] = value } }
for (const [key, fn] of Object.entries(c.methods)) ctx[key] = fn.bind(ctx)
for (const [key, fn] of Object.entries(c.computed)) Object.defineProperty(ctx, key, { get: () => fn.call(ctx) })
async function main() {
  const first = ctx.load(); ctx.$route.query.id = '2'; const second = ctx.load()
  const data = revision => ({ data: { revision, draft_revision: revision, draft: { ...p.emptyLuckyBag(), groups: [] }, published: null } })
  loadResolvers[1](data(2)); await second; loadResolvers[0](data(1)); await first
  assert.equal(ctx.goods.id, 2); assert.equal(ctx.revision, 2)
  ctx.form.groups.push({ key: 'g', name: '', candidates: [] })
  assert.equal(ctx.dirty, true)
  const draft = ctx.save('draft'); assert.equal(requests.at(-1).url, '/goods/info/2/lucky-bag/draft')
  loadResolvers.at(-1)(data(3)); await draft; assert.equal(ctx.draftRevision, 3)
  const count = requests.length; await ctx.save('publish'); assert.equal(requests.length, count, 'an incomplete draft can be saved but cannot be published')
  ctx.form = JSON.parse(JSON.stringify(quotaForm))
  const candidate = ctx.form.groups[0].candidates[0]
  ctx.setQuotaMode(candidate, 'EXCLUDE'); assert.equal(candidate.fixed_quantity, 0)
  ctx.setQuotaMode(candidate, 'AUTO'); assert.equal(candidate.fixed_quantity, null)
  ctx.setQuotaMode(candidate, 'FIXED'); assert.equal(candidate.fixed_quantity, 1)
  const beforeTrial = requests.length
  await ctx.trial(); assert.equal(requests.length, beforeTrial, 'invalid quota must not be reported as an inventory shortage')
  ctx.setQuotaMode(candidate, 'AUTO')
  const trial = ctx.trial(); assert.equal(requests.at(-1).url, '/inventory/lucky-bags/trial')
  ctx.form.preset_quantity = 5
  loadResolvers.at(-1)({ data: { warehouses: [{ store_code: 'OLD', can_fulfill: true }] } }); await trial
  assert.equal(ctx.trialResult.length, 0, 'a trial for old settings must not appear after the settings change')
  ctx.trialStores = ['W1']; const scope = ctx.stockScope
  const firstStock = ctx.refreshStock(); const firstStockResolver = loadResolvers.at(-1)
  assert.equal(requests.at(-1).url, '/inventory/lucky-bags/stock-check')
  ctx.form.preset_quantity = 200; assert.equal(ctx.stockScope, scope, 'changing quota must reuse the inventory snapshot')
  ctx.trialStores = ['W2']; const secondStock = ctx.refreshStock()
  loadResolvers.at(-1)({ data: { warehouses: [{ ...snapshot, store_code: 'W2' }], checked_at: 'now' } }); await secondStock
  firstStockResolver({ data: { warehouses: [snapshot], checked_at: 'old' } }); await firstStock
  assert.equal(ctx.stockRows[0].store_code, 'W2', 'late responses must not overwrite the selected warehouse')
  ctx.stockClock = ctx.stockFetchedAt + 30001
  assert.equal(ctx.stockStale, true, 'inventory snapshots must become visibly stale')
  const stockRequests = requests.length; ctx.form.groups[0].candidates[0].fixed_quantity = 100
  assert.equal(requests.length, stockRequests, 'local demand changes must not query inventory per SKU')
  console.log('PASS lucky-bag draft/publish validation, grouped summary, stale loads, revision refresh and Vue template')
}
main().catch(error => { console.error(error); process.exitCode = 1 })

// The same units are used throughout allocation, shipment and refund screens.
for (const file of ['order/RefundGoods', 'order/OrderSalesBundles', 'order/BusinessStockDetail', 'inventory/LuckyBagLedger', 'inventory/PlatformStockSync', 'ruleStock/index', 'ruleStock/components/AllocationLines', 'ruleStock/components/DailyRuns', 'productInventory/index', 'channelInventory/index']) {
  const source = fs.readFileSync(path.join(root, '../', file + '.vue'), 'utf8')
  const component = compiler.parseComponent(source)
  assert.deepEqual(compiler.compile(component.template.content).errors, [], file)
  babel.transformSync(component.script.content, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] })
}
const refundSource = fs.readFileSync(path.join(root, '../order/RefundGoods.vue'), 'utf8')
const refundModule = { exports: {}, require: () => ({}) }
vm.runInNewContext(transform(compiler.parseComponent(refundSource).script.content), refundModule)
const refund = refundModule.exports.default
const selected = { form: { return_scope: 'BUNDLE', bundle_id: 7 }, refund: { bundles: [{ id: 7, business_type: 'LUCKY_BAG' }] } }
selected.selectedBundle = refund.computed.selectedBundle.call(selected)
assert.equal(refund.computed.selectedLucky.call(selected), true)
selected.selectedBundle = { business_type: 'BUNDLE' }
assert.equal(refund.computed.selectedLucky.call(selected), false)
console.log('PASS 10 fulfillment/inventory templates, script compilation and original-bag refund selection')
const diagnosticComponent = compiler.parseComponent(fs.readFileSync(path.join(root, 'LuckyBagTrialDiagnostics.vue'), 'utf8'))
assert.deepEqual(compiler.compile(diagnosticComponent.template.content).errors, [])
console.log('PASS stock shortage calculation, grouped random stock, warehouse response fencing and diagnostics template')
