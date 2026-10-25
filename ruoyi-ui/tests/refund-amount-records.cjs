const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
const reads = []
function load(file) {
  const raw = fs.readFileSync(path.join(root, file), 'utf8')
  const component = file.endsWith('.vue') ? compiler.parseComponent(raw) : null
  if (component) assert.deepEqual(compiler.compile(component.template.content).errors, [], file)
  const module = { exports: {} }
  const code = babel.transformSync(component ? component.script.content : raw, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(dependency => {
    if (dependency.startsWith('./')) return load(dependency.slice(2) + '.js')
    if (dependency === '@/api/order/refund') return { refundAmountRecords: (id, params) => new Promise((resolve, reject) => reads.push({ id, params, resolve, reject })) }
    return {}
  }, module, module.exports)
  return module.exports
}
const { refundGoodsProcessing } = load('refundGoodsActionPresentation.js')
const refund = { goods_action: 'MIXED', goods_state: 'DONE', goods_tasks: [{ action: 'RETURN', quantity: 1, state: 'DONE' }] }
assert.deepEqual(refundGoodsProcessing(refund), { action: '退货入库', state: '已完成' })
assert.deepEqual(refundGoodsProcessing({ ...refund, goods_tasks: [...refund.goods_tasks, { action: 'CANCEL', state: 'CANCELED', quantity: 1 }] }), refundGoodsProcessing(refund), 'a superseded canceled task must not invent an unshipped cancellation')
assert.match(refundGoodsProcessing({ ...refund, goods_tasks: [...refund.goods_tasks, { action: 'CANCEL', state: 'DONE', quantity: 1 }] }).action, /分项处理.*取消未发商品/)
assert.equal(refundGoodsProcessing({ ...refund, goods_tasks: [] }).state, '任务结果待核对')
assert.equal(refundGoodsProcessing({ ...refund, goods_tasks: [{ action: 'RETURN', quantity: 1, state: 'PENDING' }] }).state, '任务结果待核对')
const component = load('RefundAmountRecords.vue').default
async function main() {
  const ctx = { ...component.data(), refund: { id: 121, platform_oid: '8620261024185730081' } }
  for (const [key, method] of Object.entries(component.methods)) if (typeof method === 'function') ctx[key] = method.bind(ctx)
  assert.equal(reads.length, 0, 'mounting a refund must not request related-refund records')
  const first = ctx.open()
  assert.equal(reads[0].id, 121)
  assert.deepEqual(reads[0].params, { pageNum: 1, pageSize: 20 })
  ctx.refund = { id: 122 }
  component.watch['refund.id'].call(ctx)
  reads[0].resolve({ data: { rows: [{ business_refund_id: 118 }], has_more: false } })
  await first
  assert.deepEqual(ctx.rows, [], 'a late response from a different refund must be discarded')
  const second = ctx.open()
  reads[1].resolve({ data: { rows: [{ refund_id: '9007199254740993123', confirmed_amount: '20.00' }], has_more: true } })
  await second
  assert.equal(ctx.rows[0].refund_id, '9007199254740993123')
  assert.equal(ctx.more, true)
  const next = ctx.load(2)
  assert.equal(reads[2].params.pageNum, 2)
  reads[2].reject(new Error('本次读取失败'))
  await next
  assert.equal(ctx.page, 1, 'failed pagination preserves the displayed page')
  assert.equal(ctx.error, '本次读取失败')
  const third = ctx.load(2)
  ctx.visible = false
  ctx.invalidate()
  reads[3].resolve({ data: { rows: [{ refund_id: 'not-current' }], has_more: false } })
  await third
  assert.deepEqual(ctx.rows, [], 'closing prevents a delayed request from filling a reopened view')
  assert.equal(ctx.loading, false)
  console.log('PASS task-based goods labels, on-demand refund records, bounded pagination and stale-response isolation')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
