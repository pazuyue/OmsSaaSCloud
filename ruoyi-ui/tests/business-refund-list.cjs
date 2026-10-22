const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const compiler = require('vue-template-compiler')
const babel = require('@babel/core')
const root = path.join(__dirname, '../src/views/oms/order')
function js(source, imports = {}) {
  const code=babel.transformSync(source,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code
  const module={exports:{}};new Function('require','module','exports',code)(name=>imports[name]||{},module,module.exports);return module.exports
}
const detail=js(fs.readFileSync(path.join(root,'refundDetailPresentation.js'),'utf8'))
const helpers=js(fs.readFileSync(path.join(root,'refundListPresentation.js'),'utf8'),{'./refundDetailPresentation':detail})
const parsed=compiler.parseComponent(fs.readFileSync(path.join(root,'BusinessRefundList.vue'),'utf8'))
assert.deepEqual(compiler.compile(parsed.template.content).errors,[])
async function main(){
  assert.equal(helpers.applicationSummary({platform_quantity:2,application_products:[{goods_name:'Kit',sku_sn:'KIT',is_bundle:1}]}).quantity,'2 套')
  assert.equal(helpers.applicationSummary({platform_quantity:0}).quantity,'0 件')
  assert.equal(helpers.applicationSummary({}).quantity,'数量未提供')
  const gift=helpers.progressSummary({return_quantity:1,accepted_quantity:1,posted_quantity:0,remaining_quantity:0,unposted_quantity:1,pending_count:0},'')
  assert.equal(gift.label,'待入账')
  assert.equal(helpers.progressSummary({return_quantity:1,accepted_quantity:1,posted_quantity:1,remaining_quantity:0,pending_count:1},'').label,'实收待核对')
  assert.equal(helpers.progressSummary(null,'无关联').label,'无关联')
  const done={id:8,state:'COMPLETED',review_state:'APPROVED',funds_state:'SUCCESS',goods_state:'DONE',goods_action:'KEEP'}
  assert.equal(helpers.businessRefundTask(done),null)
  assert.equal(helpers.businessRefundTask({...done,gift_pending:true}).button,'处理赠品')
  const closed={...done,state:'CLOSED',review_state:'CLOSED',funds_state:'CLOSED',goods_state:'CANCELED',goods_action:'RETURN'}
  assert.equal(helpers.businessRefundTask(closed),null)
  assert.equal(helpers.businessRefundTask({...closed,gift_pending:true}).button,'处理赠品')
  const auto={...done,state:'PROCESSING',goods_state:'WAIT_ACTION',goods_action:'RETURN',return_auto:{state:'MANUAL',message:'多个发货来源'}}
  assert.equal(helpers.businessRefundTask(auto).title,'商品退回安排需人工核对')
  assert.equal(helpers.businessRefundTask(auto).message,'多个发货来源')
  assert.equal(helpers.businessRefundTask({...auto,return_auto:{state:'PENDING'}}).title,'商品退回安排自动创建中')
  assert.equal(helpers.businessRefundTask({...done,review_state:'STALE',state:'BLOCKED'}).tab,'approval')
  const intent = { id: 9, platform: { dispute_type: 'REFUND' }, review_state: 'APPROVED', goods_action: 'RETURN', goods_state: 'WAIT_ACTION', has_purchase_return: 1, has_gift_return: 0, purchase_return_pending: 1, missing_purchase_document: 1 }
  assert.equal(detail.refundScenario(intent).platform, '仅退款')
  assert.equal(detail.refundScenario(intent).text, '商品需退回')
  assert.equal(detail.refundCurrentTask(intent, [], '').title, '商品退回单尚未创建')
  assert.equal(detail.refundScenario({ ...intent, has_purchase_return: 0, has_gift_return: 1, purchase_return_pending: 0, goods_action: 'KEEP' }).text, '仅赠品退回')
  assert.equal(detail.refundScenario({ ...intent, purchase_return_pending: 0 }).text, '商品退回已处理')
  const mixedUnits = { return_quantity: 4, accepted_quantity: 1, posted_quantity: 1, unit_progress: [{ physical_unit: '套', return_quantity: 1, accepted_quantity: 0, posted_quantity: 0 }, { physical_unit: '件', return_quantity: 3, accepted_quantity: 1, posted_quantity: 1 }] }
  assert.equal(helpers.progressQuantity(mixedUnits, 'return_quantity'), '1 套、3 件')
  assert.equal(helpers.progressQuantity({ unit_progress: [mixedUnits.unit_progress[0]] }, 'accepted_quantity'), '0 套')
  assert.ok(helpers.progressSummary(mixedUnits, '').detail.includes('1 套、3 件'))
  const original = { id: 9, platform_oid: '9007199254740993123', platform: { platform_quantity: 1 }, sales_context: { line: { sales_amount: '100.00', goods_payment: '85.00' } }, lines: [{ id: 1, platform_oid: '9007199254740993123', sku_sn: 'SET', goods_name: '成品套装', quantity: 3, stocked_bundle_snapshot: { components: [{ sku_sn: 'A', quantity: 2 }] } }, { id: 2, platform_oid: '9007199254740993123', sku_sn: 'B', exchange_id: 1 }, { id: 3, platform_oid: '9007199254740993123', sku_sn: 'G', gift_plan_id: 1 }] }
  const app = detail.refundApplication(original)
  assert.equal(app.unit, '套'); assert.equal(app.original_quantity, 3); assert.equal(app.quantity, 1)
  assert.equal(app.products.length, 1); assert.equal(app.money.goods_payment, '85.00'); assert.equal(app.replacements.length, 1)
  assert.equal(app.components[0].quantity, 2)
  original.bundles = [{ id: 8, platform_oid: original.platform_oid, sku_sn: 'FD', goods_name: '福袋', quantity: 2, business_type: 'LUCKY_BAG', units: [{ unit_id: 1, return_refund_id: 9 }, { unit_id: 2, return_refund_id: 10 }] }]
  assert.equal(detail.refundApplication(original).unit, '袋'); assert.equal(detail.refundApplication(original).return_units.length, 1)
  const pending=[],calls=[]
  const vm=js(parsed.script.content,{
    './refundListPresentation':helpers,
    './refundLabels':{label:value=>value},
    '@/api/order/refund':{refundList:(_,params)=>{calls.push(params);return new Promise((resolve,reject)=>pending.push({resolve,reject}))}}
  }).default
  const opened=[]
  const context={...vm.data(),$refs:{detail:{row:{id:8},open:async(id,issue)=>opened.push([id,issue]),openGiftGoods:()=>opened.push('gift')}},$message:{success:()=>{}}}
  for(const [name,method] of Object.entries(vm.methods))if(typeof method==='function')context[name]=method.bind(context)
  context.query.pageNum=4;context.query.task='GIFT';context.createdRange=['2026-10-01','2026-10-20']
  const first=context.search();assert.equal(calls[0].pageNum,1);assert.equal(calls[0].task,'GIFT');assert.equal(calls[0].createdTo,'2026-10-20')
  context.query.scenario='REFUND_RETURN';context.query.goodsAction='RETURN';context.query.task='REVIEW';const second=context.search()
  assert.equal(calls[1].scenario, 'REFUND_RETURN'); assert.equal(calls[1].goodsAction, 'RETURN')
  pending[1].resolve({data:{rows:[{...done,id:9}],total:21}});await second
  pending[0].resolve({data:{rows:[done],total:100}});await first
  assert.equal(context.total,21);assert.equal(context.rows[0].id,9);assert.equal(context.loading,false)
  const failure=context.load();pending[2].reject(Error('unavailable'));await failure
  assert.ok(context.error);assert.equal(context.rows.length,0);assert.equal(context.loading,false)
  await context.openAction({...done,task:helpers.businessRefundTask({...done,gift_pending:true})})
  assert.equal(opened[1],'gift')
  await context.openAction({...done,task:{tab:'approval'}});assert.equal(context.$refs.detail.detailTab,'approval')
  context.$refs.detail.row={id:99};await context.openAction({...done,task:{tab:'goods',gifts:true}});assert.notEqual(opened[opened.length-1],'gift')
  console.log('PASS business refund quantities, state-specific routing, server query filters, page reset, stale results and load failure feedback')
}
main().catch(error=>{console.error(error);process.exitCode=1})
