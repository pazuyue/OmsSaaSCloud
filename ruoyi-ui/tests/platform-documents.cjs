const assert=require('assert/strict'),fs=require('fs'),path=require('path'),vm=require('vm'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const root=path.join(__dirname,'../src/views/oms/order')
function load(code,api={}){const ctx={exports:{},require:()=>api};vm.runInNewContext(babel.transformSync(code,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code,ctx);return ctx.exports}
const helpers=load(fs.readFileSync(path.join(root,'platformDocumentsPresentation.js'),'utf8'))
function component(file,api={}){const s=compiler.parseComponent(fs.readFileSync(path.join(root,file),'utf8'));assert.deepEqual(compiler.compile(s.template.content).errors,[],file);return load(s.script.content,{...helpers,...api}).default}
for(const f of ['PlatformDocuments.vue','PlatformDocumentsRedirect.vue','PlatformDocumentRelations.vue','RefundList.vue','PlatformExchanges.vue','index.vue'])component(f)
const id='900719925474099312345678'
for(const [legacy,kind] of Object.entries(helpers.legacyDocumentKinds)){const r=helpers.documentLocation(kind,{id:'145',tid:id,oid:id+'1',channelId:'57',view:'platform',from:'2026-10-01 00:00:00',to:'2026-10-17 00:00:00'});assert.equal(r.path,'/oms-order/platformDocuments');assert.equal(r.query.type,kind);assert.equal(r.query.platformTid,id);assert.equal(r.query.platformOid,id+'1');assert.equal(r.query.channelId,'57');assert.equal(r.query.id,'145');assert.equal(r.query.modifiedFrom,'2026-10-01 00:00:00');assert.ok(!('view'in r.query)&&!('tid'in r.query))}
const parent=component('PlatformDocuments.vue')
const context={active:'EXCHANGE',types:['ORDER','REFUND','EXCHANGE'],applied:helpers.sharedDocumentFilters({platformTid:id}),channels:[],$route:{query:{type:'ORDER',id:'145'}}}
assert.equal(parent.computed.childProps.call(context).recordId,0,'Never open an order ID as an exchange ID')
context.$route.query.type='EXCHANGE';assert.equal(parent.computed.childProps.call(context).recordId,145)
async function main(){
 const related=component('PlatformDocumentRelations.vue',{relatedPlatformDocuments:async p=>({data:{groups:[{kind:'ORDER',rows:[{id:1}]}]}})});const drawer={...related.data()};await related.methods.open.call(drawer,id,'57');assert.equal(drawer.visible,true);assert.equal(drawer.groups.length,1);let opened;drawer.$emit=(_,record)=>opened=record;related.methods.openRecord.call(drawer,'ORDER',{id:1});assert.equal(drawer.visible,false);assert.equal(opened.kind,'ORDER')

 for(const [file,kind] of [['index.vue','ORDER'],['RefundList.vue','REFUND'],['PlatformExchanges.vue','EXCHANGE']]){const calls=[];const c=component(file,{platformDocumentList:async(k,p)=>{calls.push([k,p]);return{data:{rows:[],total:0}}}});const ctx={...c.data(),embedded:true,view:'platform',sharedFilters:helpers.sharedDocumentFilters({channelId:'57',platformTid:id,platformOid:id+'1'}),query:{channelId:'55',state:'FAILED',status:'EXCEPTION',pageNum:1,pageSize:20},exceptionRange:[],range:[]};await c.methods.load.call(ctx);assert.equal(calls.length,1);assert.equal(calls[0][0],kind);assert.equal(calls[0][1].channelId,'57');assert.equal(calls[0][1].platformTid,id);assert.equal(calls[0][1].state,'FAILED');assert.equal(calls[0][1].status,'EXCEPTION')}
 const pending=[];const c=component('RefundList.vue',{platformDocumentList:()=>new Promise(resolve=>pending.push(resolve))});const ctx={...c.data(),embedded:true,sharedFilters:{}};const first=c.methods.load.call(ctx),second=c.methods.load.call(ctx);pending[1]({data:{rows:[{id:2}],total:1}});await second;pending[0]({data:{rows:[{id:1}],total:1}});await first;assert.equal(ctx.rows[0].id,2,'Old response cannot replace newer shared-filter results')
 console.log('PASS platform document templates, deep links, long IDs, shared filters, typed details and request ordering')
}
main().catch(e=>{console.error(e);process.exitCode=1})
