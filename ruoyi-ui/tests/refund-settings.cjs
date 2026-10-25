const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(name, imports = {}) {
  const raw = fs.readFileSync(path.join(root, name), 'utf8')
  let source = raw
  if (name.endsWith('.vue')) {
    const vue = compiler.parseComponent(raw)
    assert.deepEqual(compiler.compile(vue.template.content).errors, [], name)
    source = vue.script.content
  }
  const module = { exports: {} }
  const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(name => imports[name] || {}, module, module.exports)
  return module.exports
}
const presentation = load('refundConfigurationPresentation.js')
const base = () => ({ channel_id: 1, channel_name: '测试店铺', shop_enabled: 1, intake: {}, api_sync: { enabled: 0, work_version: 0 }, flow: { enabled: 0, funds_owner: 'PLATFORM', auto_pay: 0, auto_review: 0, payment_limit: 0 }, returns: { enabled: 0, merge_mode: 'NONE', auto_dispatch: 0 }, access: { available: true, can_query: true, read_enabled: 1, write_enabled: 1, payment_authorized: false } })
const check = (row, id) => presentation.configurationChecks(row).find(c => c.id === id)
let row = base()
row.flow.enabled = 1; row.flow.auto_full_cancel = 1; row.access.can_query = false
assert.equal(check(row, 'cancel').state, 'READY', 'local cancellation policy does not depend on platform credentials')
row.flow.auto_review = 1
assert.equal(check(row, 'review').state, 'WAIT')
row.access.can_query = true; row.flow.funds_owner = 'OMS'; row.flow.auto_pay = 1; row.flow.payment_limit = 30
assert.equal(check(row, 'pay').state, 'WAIT', 'selecting OMS payer never implies financial authorization')
row.access.payment_enabled = 1; row.access.payment_authorized = true; row.access.ignore_code = 1
assert.equal(check(row, 'pay').state, 'READY')
row.flow.enabled = 0; row.returns.auto_dispatch = 1; row.returns.store_codes = ['W']
assert.equal(check(row, 'pay').state, 'WAIT')
assert.equal(check(row, 'dispatch').state, 'READY', 'warehouse automation is independent of the platform automation master')
row.api_sync.enabled = 1
assert.equal(check(row, 'api').state, 'READY', 'API intake is independent of automation')
row.access.available = false
assert.equal(check(row, 'api').state, 'WAIT')
assert.equal(presentation.configurationOverview(row).access, '状态暂不可用')
row.shop_enabled = 0
assert.equal(check(row, 'dispatch').state, 'WAIT')
assert.ok(!presentation.configurationOverview(row).flow.join('').includes('OMS提交付款（'), 'disabled master never previews automatic payment')

const calls = []
let responder = async config => { calls.push(config); return { data: {} } }
const component = load('RefundAfterSaleSettings.vue', { '@/utils/request': config => responder(config), '@/utils/ruoyi': { parseTime: () => '2026-10-22 00:00:00' }, '@/api/order/refund': { saveRefundConfig: async data => { calls.push({ kind: 'JDP', data }) } }, './refundConfigurationPresentation': presentation }).default
function context() {
  const ctx = { ...component.data(), channelId: '1', $refs: {}, $nextTick: fn => fn(), $message: { success() {} }, $confirm: async () => {}, $route: { query: {} } }
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx)
  for (const [name, method] of Object.entries(component.computed)) if (!Object.hasOwn(ctx, name)) Object.defineProperty(ctx, name, { get: () => method.call(ctx) })
  return ctx
}
async function main() {
  const ctx = context(); ctx.model = base(); ctx.saved = structuredClone(ctx.model); ctx.tab = 'automation'; ctx.model.flow.enabled = 1
  ctx.$confirm = async () => { throw new Error('cancel') }
  assert.equal(await ctx.allowTab('intake', 'automation'), false)
  await assert.rejects(ctx.beforeTabLeave('intake', 'automation'), /尚未保存/, 'Element UI requires a rejected promise to stop an asynchronous tab change')
  assert.equal(ctx.model.flow.enabled, 1, 'canceling navigation preserves edits')
  ctx.$confirm = async () => {}
  assert.equal(await ctx.allowTab('intake', 'automation'), true)
  assert.equal(ctx.model.flow.enabled, 0)
  ctx.returnSaving = true
  assert.equal(await ctx.canDiscard(), false, 'cannot leave a warehouse save in progress')
  ctx.returnSaving = false
  let resolves = {}
  responder = config => new Promise(resolve => { resolves[config.url] = resolve })
  const first = ctx.loadEditor(); ctx.channelId = '2'; const second = ctx.loadEditor()
  function respond(id) { resolves['/order/refunds/settings/' + id]({ data: { ...base(), channel_id: id, sources: [], flow: { ...base().flow, api_sync: { enabled: 0 } } } }); resolves['/channel/refundPlatform/' + id + '/capability']({ data: base().access }) }
  respond(2); await second; respond(1); await first
  assert.equal(ctx.model.channel_id, 2, 'late shop response cannot overwrite another shop')
  assert.equal(ctx.loading, false)
  assert.equal(ctx.model.flow.api_sync, undefined, 'intake state is not part of the automation form')
  responder = async config => { calls.push(config); return { data: {} } }; ctx.loadEditor = async () => {}
  ctx.tab = 'automation'; ctx.model.flow.enabled = 1; await ctx.save()
  assert.equal(calls[0].url, '/order/refunds/flow-configs/2')
  assert.equal(Object.hasOwn(calls[0].data, 'api_sync_enabled'), false, 'saving automation cannot disable separately configured API intake')
  calls.length = 0; ctx.saved = structuredClone(ctx.model); ctx.tab = 'intake'; ctx.model.api_sync.enabled = 1; await ctx.save()
  assert.equal(calls.length, 1, 'API-only save does not create a partial JDP configuration')
  assert.equal(calls[0].url, '/order/refunds/settings/2/api-sync')
  assert.equal(calls[0].data.enabled, 1)
  const flow = load('RefundFlowSettings.vue', 'RefundMoneyAuthorizations.vue').default; let emitted
  flow.methods.ownerChanged.call({ value: { funds_owner: 'OMS', auto_pay: 1 }, $emit: (_, value) => { emitted = value } }, 'PLATFORM')
  assert.equal(emitted.auto_pay, 0)
  console.log('PASS refund settings prerequisites, independent saves, unsaved navigation and stale shop response protection')
}
main().catch(error => { console.error(error); process.exitCode = 1 })

const extra = base(); extra.flow.enabled = 1; extra.flow.small_refund_enabled = 1; extra.flow.small_refund_limit = 0
assert.equal(check(extra, 'small').state, 'WAIT')
extra.flow.small_refund_limit = 20; assert.equal(check(extra, 'small').state, 'READY')
extra.flow.auto_shipped_cancel = 1; assert.equal(check(extra, 'shipped').state, 'WAIT')
extra.returns = { auto_create: 1, create_store_code: 'TEST', create_return_address: 'TEST', create_return_contact: 'TEST' }; assert.equal(check(extra, 'shipped').state, 'READY')
assert.ok(presentation.configurationOverview(extra).flow.join('').includes('20.00'))
load('RefundFlowSettings.vue', 'RefundMoneyAuthorizations.vue')
