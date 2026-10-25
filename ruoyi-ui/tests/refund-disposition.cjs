const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(name, imports = {}) {
  const raw = fs.readFileSync(path.join(root, name), 'utf8')
  const sfc = name.endsWith('.vue') ? compiler.parseComponent(raw) : null
  if (sfc) assert.deepEqual(compiler.compile(sfc.template.content).errors, [], name)
  const module = { exports: {} }
  const code = babel.transformSync(sfc ? sfc.script.content : raw, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require','module','exports',code)(name => imports[name] || {}, module, module.exports)
  return module.exports
}
const quantities = load('refundReturnPresentation.js')
const h = load('refundDispositionPresentation.js', { './refundReturnPresentation': quantities })
assert.equal(h.cancellationResult({ state:'DONE', quantity:2 }).label, '已执行，原回执待核对')
assert.equal(h.valueQuantity(null,{stock_unit:'件'}), '待核对')
assert.equal(h.valueQuantity(0,{stock_unit:'件'}), '0 件')
assert.equal(h.cancellationResult({state:'CANCELED',receipt_confirmed:true}).label, '原任务已停止')
assert.equal(h.cancellationResult({state:'DONE',receipt_confirmed:true,logical_released_quantity:null}).label,'取消已确认')
assert.match(h.cancellationResult({state:'DONE',receipt_confirmed:true,logical_released_quantity:null}).detail,/数量未记录/)
assert.equal(h.scopeQuantity([{quantity:2,stock_unit:'件'},{quantity:1,stock_unit:'套'}]),'2 件 + 1 套')
assert.equal(h.scopeQuantity([{quantity:null,stock_unit:'件'}]),'数量待确认')
assert.equal(h.returnResult({quantity:2,accepted_quantity:1,posted_quantity:1,remaining_quantity:1}).label,'部分退回')
assert.equal(h.returnResult({quantity:2,accepted_quantity:1,posted_quantity:0,remaining_quantity:1,unposted_quantity:1}).label,'已收，部分待入库')
assert.equal(h.returnResult({quantity:2,accepted_quantity:2,posted_quantity:2,remaining_quantity:0,state:'CLOSED'}).label,'已退齐并入库')
assert.equal(h.returnResult({quantity:2,accepted_quantity:1,posted_quantity:1,remaining_quantity:0,waived_quantity:1}).label,'含不再追回处理')
assert.equal(h.returnResult({parcel_pending:true,accepted_quantity:0,posted_quantity:0,quantity:100}).label,'另有实收待分配')
assert.equal(h.returnResult({state:'STOPPED',remaining_quantity:1}).label,'收货已停止，仍有未收')
for(const file of ['RefundDispositionResults.vue','RefundDetail.vue','RefundFulfillment.vue'])load(file)
async function staleRead(){
  const pending=[]
  const component=load('RefundDispositionResults.vue',{'@/api/order/refund':{refundDisposition:id=>new Promise(resolve=>pending.push({id,resolve}))}}).default
  const ctx={...component.data(),refund:{id:1}}
  const first=component.methods.load.call(ctx);ctx.refund={id:2};const second=component.methods.load.call(ctx)
  pending[1].resolve({data:{refund_id:2}});await second;pending[0].resolve({data:{refund_id:1}});await first
  assert.equal(ctx.result.refund_id,2,'an older refund response cannot overwrite the current result')
  assert.equal(ctx.loading,false)
  const source=fs.readFileSync(path.join(root,'RefundDispositionResults.vue'),'utf8')
  assert.ok(!source.includes('refreshRefundGoods'),'reading the results must not trigger warehouse queries or stock posting')
}
staleRead().then(()=>console.log('PASS refund disposition evidence, stopped tasks, mixed units, own receipt scope and stale reads')).catch(e=>{console.error(e);process.exitCode=1})
