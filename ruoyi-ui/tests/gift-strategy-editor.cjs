const assert = require('assert/strict'), fs = require('fs'), path = require('path')
const babel = require('@babel/core'), compiler = require('vue-template-compiler')
const base = path.join(__dirname, '../src/views/oms/order')
function moduleFrom(source, dependencies = {}) {
  const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  const mod = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => dependencies[name] || {}, mod, mod.exports)
  return mod.exports
}
const helpers = moduleFrom(fs.readFileSync(path.join(base, 'giftStrategy.js'), 'utf8'))
function component(name, api) {
  return moduleFrom(compiler.parseComponent(fs.readFileSync(path.join(base, name), 'utf8')).script.content, { './giftStrategy': helpers, '../goods/goodsPresentation': moduleFrom(fs.readFileSync(path.join(base, '../goods/goodsPresentation.js'), 'utf8')), '@/api/order/gift': api }).default
}
function context(view, extra = {}) {
  const ctx = { ...view.data(), ...extra }
  for (const [name, fn] of Object.entries(view.methods || {})) ctx[name] = fn.bind(ctx)
  for (const [name, fn] of Object.entries(view.computed || {})) Object.defineProperty(ctx, name, { get: () => fn.call(ctx) })
  return ctx
}
function form() {
  const f = helpers.draftFrom();f.name = 'Campaign';f.policy.rules[0].name = 'Amount'
  f.policy.rules[0].gifts = [{ sku_sn: 'G', goods_name: 'Gift', quantity: 1 }]
  return f
}
async function run() {
  const f = form(), period = ['2026-10-01 00:00:00', '2026-11-01 00:00:00']
  f.policy.rules[0].skus = ['A']
  const payload = helpers.strategyPayload(f, period)
  assert.deepEqual(payload.policy.rules[0].skus, []);assert.deepEqual(f.policy.rules[0].skus, ['A'])
  assert.ok(!('purchase_scope' in payload.policy.rules[0]));assert.deepEqual(helpers.draftIssues(f, period), [])
  f.policy.rules[0].max_count = 12
  assert.equal(helpers.strategyPayload(f, period).policy.rules[0].max_count, 12)
  assert.ok(helpers.ruleSummary(f.policy.rules[0]).includes('累计最多赠送12次'))
  for (const invalid of [-1, 1.5, 1000000001]) { f.policy.rules[0].max_count = invalid;assert.ok(helpers.draftIssues(f, period).some(e => e.field === 'max_count')) }
  f.policy.rules[0].max_count = 0
  const original = { ...payload, id: 9, revision: 4, enabled: 1, name: 'A'.repeat(100) }
  const copy = helpers.draftFrom(original, true);copy.policy.rules[0].gifts[0].quantity = 5
  assert.equal(original.policy.rules[0].gifts[0].quantity, 1);assert.ok(!copy.id);assert.equal(copy.enabled, 0);assert.ok(copy.name.length <= 100)
  original.quota_usage = { [original.policy.rules[0].key]: { used_count: 5 } }
  assert.ok(!helpers.draftFrom(original, true).quota_usage)
  const tier = helpers.draftFrom();tier.name = 'Tier';Object.assign(tier.policy.rules[0], { name: 'Tier', type: 'TIER', purchase_scope: 'SELECTED', skus: ['A'], tiers: [{ minimum: 2, gifts: [{ sku_sn: 'G', quantity: 1 }] }, { minimum: 2, gifts: [{ sku_sn: 'G', quantity: 2 }] }] })
  assert.ok(helpers.draftIssues(tier, period).some(e => e.field === 'tiers'))
  tier.policy.rules[0].tiers[1].minimum = 4
  assert.equal(helpers.strategyPayload(tier, period).policy.rules[0].gifts[0].quantity, 1)
  let finishTrial, trialCalls = 0, saves = 0
  const api = { simulateGiftStrategy: request => { trialCalls++;assert.equal(request.policy.rules[0].amount, 250);return new Promise(resolve => { finishTrial = resolve }) }, saveGiftGroup: () => { saves++;throw Error('Trial must not save') } }
  const view = component('GiftStrategyEditor.vue', api)
  const ctx = context(view, { form: form(), period, $route: { query: {} }, $message: { warning: message => { throw Error(message) } } })
  ctx.activeKey = ctx.form.policy.rules[0].key;ctx.form.policy.rules[0].amount = 250
  ctx.quotaUsage = { [ctx.activeKey]: { used_count: 1500 } };ctx.setQuotaLimit(true);assert.equal(ctx.activeRule.max_count, 1500);ctx.setQuotaLimit(false);assert.equal(ctx.activeRule.max_count, 0)
  ctx.baseline = ctx.fingerprint;ctx.quotaUsage = { [ctx.activeKey]: { used_count: 1501 } };assert.equal(ctx.dirty, false)
  ctx.cart = [{ sku_sn: 'A', quantity: 2, payment: 300 }];ctx.paidTime = '2026-10-20 12:00:00'
  const trial = ctx.simulate();ctx.form.policy.rules[0].amount = 350
  finishTrial({ data: { simulated: true, gifts: [], trace: [] } });await trial
  assert.equal(trialCalls, 1);assert.equal(saves, 0);assert.equal(ctx.trialStale, true)
  ctx.duplicateRule(0);ctx.form.policy.rules[1].gifts[0].quantity = 7
  assert.equal(ctx.form.policy.rules[0].gifts[0].quantity, 1);assert.notEqual(ctx.form.policy.rules[0].key, ctx.form.policy.rules[1].key)
  const savedBodies = [], links = [], moves = []
  const scope = { id: 3, revision: 8, name: 'Shops', channels: [11], links: [{ group_id: 2, continue_after_match: true }] }
  const savingApi = { saveGiftGroup: async body => { savedBodies.push(body);return { data: { id: 17, revision: 1, enabled: body.enabled } } }, saveGiftShopGroup: async body => { links.push(body);throw Error('Stale shop revision') }, giftShopGroups: async () => ({ data: [scope] }) }
  const saving = context(component('GiftStrategyEditor.vue', savingApi), { form: form(), period, scopes: [scope], activeKey: '', loading: false, linkAfterSave: true, $route: { query: { scope: '3' } }, $router: { replace: async () => {}, push: route => moves.push(route) }, $message: { success: () => {} } })
  saving.baseline = saving.fingerprint;await saving.save(true)
  assert.equal(saving.form.id, 17);assert.equal(saving.form.revision, 1);assert.equal(saving.savedState, true);assert.equal(saving.dirty, false)
  assert.equal(links[0].revision, 8);assert.equal(links[0].links.length, 2);assert.equal(scope.links.length, 1)
  assert.ok(saving.associationError.includes('策略已保存'));assert.equal(moves.length, 0)
  await saving.save(false);assert.equal(savedBodies[1].id, 17);assert.equal(savedBodies[1].revision, 1);assert.equal(savedBodies[1].enabled, 0)
  let response
  const picker = context(component('GiftProductPicker.vue', { resolveGiftGoods: () => new Promise(resolve => { response = resolve }) }), { value: [], max: 2, giftOnly: false, $set: (obj, key, value) => { obj[key] = value }, $emit: () => {}, $message: { warning: () => {}, success: () => {} } })
  picker.paste = 'A\nMISSING';const paste = picker.resolvePaste();picker.paste = 'B';response({ data: [{ skuSn: 'A', valid: true }] });await paste
  assert.deepEqual(picker.pasteRows, [])
  picker.pending = ['A'];picker.pasteRows = [{ skuSn: 'B', valid: true }, { skuSn: 'MISSING', valid: false }];picker.addPaste();assert.deepEqual(picker.pending, ['A', 'B'])
  console.log('PASS draft normalization, rule copies, stale trial results, save/link recovery and bulk SKU validation')
}
run().catch(error => { console.error(error);process.exitCode = 1 })
