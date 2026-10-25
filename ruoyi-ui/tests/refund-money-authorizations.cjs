const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const compiler = require('vue-template-compiler')
const babel = require('@babel/core')
const root = path.join(__dirname, '../src/views/oms/order')
const pending = []
const request = args => new Promise((resolve, reject) => pending.push({ args, resolve, reject }))
function load(name) {
  const sfc = compiler.parseComponent(fs.readFileSync(path.join(root, name), 'utf8'))
  assert.deepEqual(compiler.compile(sfc.template.content).errors, [], name)
  const module = { exports: {} }
  const code = babel.transformSync(sfc.script.content, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require','module','exports',code)(n => n === '@/utils/request' ? request : {}, module, module.exports)
  return module.exports.default
}
const component = load('RefundMoneyAuthorizations.vue')
load('RefundFlowSettings.vue')
const ctx = { ...component.data(), channelId: 1 }
for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx)
async function verify() {
  const old = ctx.load()
  ctx.channelId = 2; component.watch.channelId.call(ctx)
  const fresh = ctx.load()
  pending[1].resolve({ data: [{ refund_id: 'new-shop' }] }); await fresh
  pending[0].resolve({ data: [{ refund_id: 'old-shop' }] }); await old
  assert.deepEqual(ctx.rows, [{ refund_id: 'new-shop' }])
  assert.ok(pending.slice(0, 2).every(x => !x.args.method), 'opening an authorization view only reads data')
  ctx.channelId = 1
  ctx.form = { refund_id: '9007199254740993', confirmed: true }
  const save = ctx.save()
  assert.equal(pending[2].args.method, 'post')
  assert.equal(pending[2].args.data.refund_id, '9007199254740993', 'platform IDs remain strings')
  ctx.channelId = 2; component.watch.channelId.call(ctx)
  ctx.form.refund_id = '9007199254740994'; ctx.saving = true
  pending[2].resolve({ data: {} }); await save
  assert.equal(ctx.form.refund_id, '9007199254740994', 'a prior-shop save cannot clear a new-shop form')
  assert.equal(ctx.saving, true, 'a prior-shop save cannot clear a new request indicator')
  assert.equal(pending.length, 3, 'a prior-shop save cannot trigger a current-shop refresh')
  assert.equal(ctx.stateLabel({ state: 'ACTIVE', expires_at: '2000-01-01 00:00:00' }), '已过期')
  assert.equal(ctx.stateLabel({ state: 'USED' }), '已采用')
  console.log('PASS difference authorization UI: read-only opening, immutable ID strings, expiry, and stale read/save protection')
}
verify().catch(e => { console.error(e); process.exitCode = 1 })
