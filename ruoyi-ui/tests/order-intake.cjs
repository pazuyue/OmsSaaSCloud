const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function load(code, api={}) { const ctx={exports:{},require:()=>api};vm.runInNewContext(babel.transformSync(code,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code,ctx);return ctx.exports }
const labels=load(fs.readFileSync(path.join(root,'intakePresentation.js'),'utf8'))
for(const file of ['management.vue','IntakeStatus.vue','IntakeTasks.vue','IntakeBackfill.vue','RefundSettings.vue','ExchangeSettings.vue','settings.vue','RefundList.vue','exchanges.vue','index.vue','PlatformExchanges.vue','PlatformExchangeDetail.vue']){const component=compiler.parseComponent(fs.readFileSync(path.join(root,file),'utf8'));assert.deepEqual(compiler.compile(component.template.content).errors,[],file)}
assert.equal(labels.intakeStatus(null,true,'REFUND').label,'未配置')
assert.equal(labels.intakeStatus(null,false,'REFUND').label,'无配置查看权限')
assert.equal(labels.intakeStatus({enabled:0,last_error:'old'},true,'REFUND').label,'已暂停自动同步')
assert.equal(labels.intakeStatus({enabled:1,source_enabled:0},true,'REFUND').label,'有异常')
assert.equal(labels.intakeStatus({enabled:1,source_enabled:1},true,'ORDER').label,'自动同步已开启')
assert.equal(labels.intakeStatus({enabled:1},true,'EXCHANGE').label,'自动同步已开启')
assert.equal(labels.retryAction({kind:'ORDER',state:'PARTIAL'}),'items')
assert.equal(labels.retryAction({kind:'EXCHANGE',state:'FAILED'}),'task')
assert.equal(labels.retryAction({kind:'REFUND',state:'COMPLETED'}),'')
async function main(){
 const calls=[];const apis={...labels,backfillOrders:async p=>{calls.push(['ORDER',p]);return{data:{}}},syncRefund:async p=>{calls.push(['REFUND',p]);return{data:{}}},exchangeSync:async p=>{calls.push(['EXCHANGE',p]);return{data:{}}}}
 const component=load(compiler.parseComponent(fs.readFileSync(path.join(root,'IntakeBackfill.vue'),'utf8')).script.content,apis).default
 for(const kind of ['ORDER','REFUND','EXCHANGE']){const ctx={...component.data(),kind,channel:55,mode:'IDS',range:null,numbers:'9007199254740993\n9007199254740993\n9007199254740994',requestKey:'SAME_REQUEST',types:['ORDER','REFUND','EXCHANGE'],$message:{warning:m=>{throw new Error(m)},success:()=>{}},$emit:()=>{}};Object.defineProperty(ctx,'limit',{get:()=>component.computed.limit.call(ctx)});Object.defineProperty(ctx,'numberLabel',{get:()=>component.computed.numberLabel.call(ctx)});await component.methods.submit.call(ctx)}
 assert.equal(calls.length,3)
 assert.deepEqual(Array.from(calls[0][1].tids),['9007199254740993','9007199254740994'])
 assert.deepEqual(Array.from(calls[1][1].refund_ids),['9007199254740993','9007199254740994'])
 assert.equal(calls[2][1].dispute_ids,'9007199254740993\n9007199254740994')
 assert.ok(calls.every(([,p])=>p.request_key==='SAME_REQUEST'))
 const source=fs.readFileSync(path.join(root,'management.vue'),'utf8');assert.ok(source.includes('syncTasksPath')&&source.includes('settingsLocation'));assert.ok(!source.includes('exchangeCapability'))
 console.log('PASS unified intake states, permissions presentation, retry routing and precise ID submission')
}
main().catch(e=>{console.error(e);process.exitCode=1})
