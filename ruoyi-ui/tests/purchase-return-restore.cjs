const fs=require('fs'),path=require('path'),assert=require('assert/strict'),vm=require('vm'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const transform=s=>babel.transformSync(s,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code
const vue=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/purchase/PurchaseReturnRestore.vue'),'utf8'))
assert.deepEqual(compiler.compile(vue.template.content).errors,[])
const pending=[],api={checkReturnRestore:()=>new Promise(resolve=>pending.push(resolve)),approveReturnRestore:async()=>{}},scope={exports:{},require:()=>api}
vm.runInNewContext(transform(vue.script.content),scope)
const def=scope.exports.default,m=def.methods,row={stock_unit:'件',required_quantity:3,source_available_quantity:1,batch_available_quantity:1,inventory_known:true}
assert.equal(def.computed.recoverableLabel.call({single:row}),'1 件')
assert.equal(def.computed.shortageLabel.call({single:row}),'2 件')
assert.equal(def.computed.recoverableLabel.call({single:{...row,inventory_known:false}}),'库存待确认')
assert.equal(def.computed.requiredUnits.call({check:{lines:[row,{stock_unit:'套',required_quantity:2}]}}),'3 件 + 2 套')

async function main(){
 const state={...def.data(),...m,visible:true,detail:{id:36},requestId:5},first=m.refresh.call(state)
 m.closed.call(state);state.detail={id:40};state.requestId=6;const second=m.refresh.call(state)
 pending[1]({data:{return_id:40,can_approve:true}});await second;pending[0]({data:{return_id:36,can_approve:false}});await first
 assert.equal(state.check.return_id,40,'a late response must not replace a different return review')
 const shortage={lines:[row],can_approve:false,inventory_groups:[]};let approvals=0,reads=0,events=0
 api.approveReturnRestore=async()=>{approvals++;throw Object.assign(new Error('恢复3件，可恢复1件，缺口2件'),{data:shortage})}
 api.checkReturnRestore=async()=>{reads++;return {data:shortage}}
 const review={...def.data(),...m,visible:true,detail:{id:36},requestId:5,check:{can_approve:true,control_version:8},$emit:()=>events++}
 const approval=m.approve.call(review);await m.approve.call(review);await approval
 assert.equal(approvals,1);assert.equal(reads,1);assert.equal(events,0);assert.equal(review.check.can_approve,false);assert.match(review.error,/缺口2件/);assert.equal(review.submitting,false)

 let resolveResponse,rejectResponse,messages=0,notifications=0
 const service={interceptors:{request:{use:()=>{}},response:{use:(a,b)=>{resolveResponse=a;rejectResponse=b}}}},axios={defaults:{headers:{}},create:()=>service}
 const modules={axios,'element-ui':{Message:()=>messages++,Notification:{error:()=>notifications++},MessageBox:{confirm:()=>Promise.resolve()},Loading:{}},'@/utils/errorCode':{}}
 const requestScope={exports:{},require:n=>modules[n]||{},process:{env:{}},console:{log:()=>{}}}
 vm.runInNewContext(transform(fs.readFileSync(path.join(__dirname,'../src/utils/request.js'),'utf8')),requestScope)
 const response={data:{code:500,msg:'缺口2件',data:shortage},request:{},config:{silentError:true}}
 await assert.rejects(resolveResponse(response),e=>e.data.can_approve===false&&e.message==='缺口2件');assert.equal(messages,0)
 await assert.rejects(resolveResponse({...response,config:{}}));assert.equal(messages,1,'ordinary requests retain their default global error')
 await assert.rejects(rejectResponse(Object.assign(new Error('Network Error'),{config:{silentError:true}})));assert.equal(messages,1)
 await assert.rejects(resolveResponse({...response,data:{code:601,msg:'状态已改变'},config:{silentError:true}}));assert.equal(messages,1);assert.equal(notifications,0)
 console.log('PASS restore quantities, unknown stock, stale responses, duplicate clicks, concurrent quota refresh and single-owner error presentation')
}
main().catch(e=>{console.error(e);process.exitCode=1})
