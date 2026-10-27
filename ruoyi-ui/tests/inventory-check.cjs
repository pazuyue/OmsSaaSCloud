const fs=require('fs'),path=require('path'),assert=require('assert/strict'),vm=require('vm'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const transform=source=>babel.transformSync(source,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code
function moduleOf(source,api={}){const env={exports:{},require:()=>api,console,setTimeout,clearTimeout};vm.runInNewContext(transform(source),env);return env.exports}
const util=moduleOf(fs.readFileSync(path.join(__dirname,'../src/utils/inventoryCheck.js'),'utf8'))
assert.equal(util.skuList('0001,A,A,a').join(','),'0001,A,a')
assert.equal(util.warehouseCsv('\uFEFFsku_sn\r\n"0001"\r\nA\r\nA').join(','),'0001,A')
assert.throws(()=>util.warehouseCsv('sku_sn,quantity\nA,2'))
assert.throws(()=>util.warehouseCsv('sku_sn\n"A,B"'))
assert.throws(()=>util.skuList(''))
assert.throws(()=>util.skuList(Array.from({length:101},(_,i)=>'S'+i).join('\n')))
assert.equal(util.quantity(null),'未知');assert.equal(util.quantity(0),'0');assert.equal(util.signed(-3),'-3')
const definitions={}
for(const name of ['InventoryCheckCreate','InventoryCheckResults','InventoryCheckNextStep','WarehouseControl']){const c=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/purchase/'+name+'.vue'),'utf8'));assert.deepEqual(compiler.compile(c.template.content).errors,[]);definitions[name]=moduleOf(c.script.content,util).default}
async function main(){
 const def=definitions.InventoryCheckCreate,created={...def.data(),initial:{},loadScope:()=>{throw Error('Unexpected scope read')}}
 def.watch.visible.call(created,true);assert.equal(created.form.scope_mode,'FULL');assert.equal(created.form.coverage,'OMS_LIST');assert.equal(created.form.skuText,'')
 const result=definitions.InventoryCheckResults
 assert.equal(result.computed.percentage.call({progress:{successful:1,failed:1,total:4}}),50)
 assert.ok(result.methods.advice('MISSING_WMS').includes('不等于零'))
 assert.equal(result.methods.grade('JS'),'质量待确认')
 const selections={};result.methods.selection.call(selections,[{sku_sn:'A'},{sku_sn:'A'},{sku_sn:'0001'}]);assert.equal(selections.selected.join(','),'A,0001')
 const pending=[],api={checkScope:()=>new Promise(resolve=>pending.push(resolve))},c=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/purchase/InventoryCheckCreate.vue'),'utf8')),d=moduleOf(c.script.content,api).default,state={...d.data(),form:{store_code:'ONE'}}
 const first=d.methods.loadScope.call(state);state.form.store_code='TWO';const second=d.methods.loadScope.call(state);pending[1]({data:{real_store:'TWO'}});await second;pending[0]({data:{real_store:'ONE'}});await first;assert.equal(state.range.real_store,'TWO');assert.equal(state.scopeLoading,false)
 const errors=[],resultFile=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/purchase/InventoryCheckResults.vue'),'utf8')),exportDef=moduleOf(resultFile.script.content,{...util,checkExport:async()=>({type:'application/json',text:async()=>JSON.stringify({code:500,msg:'导出最多100000行'})})}).default,exportState={document:{id:1},filters:{},$message:{error:s=>errors.push(s)}};await exportDef.methods.exportRows.call(exportState);assert.equal(errors[0],'导出最多100000行');assert.equal(exportState.exporting,false)
 const transfer={document_sn:'DB4886221bb8c448a19e1f0be60da24da4'},pendingDoc={id:195,parent_id:0,result:{rows:[{status:'WAITING_POST',difference:-2}],pending_documents:[transfer]},adjustments:[]}
 assert.equal(util.inventoryCheckNextStep(pendingDoc,true).key,'pending')
 assert.ok(util.inventoryCheckNextStep(pendingDoc,false).message.includes('尚不能认定'))
 assert.equal(definitions.InventoryCheckNextStep.computed.singleTransfer.call({document:pendingDoc}).document_sn,transfer.document_sn)
 assert.equal(util.inventoryCheckNextStep({result:{rows:[{status:'MATCHED'}]},adjustments:[]},false).key,'matched')
 assert.equal(util.inventoryCheckNextStep({result:{rows:[{status:'PHYSICAL_DIFFERENCE',difference:-2}]}},true).key,'adjust')
 assert.equal(util.inventoryCheckNextStep({parent_id:1,result:{rows:[{status:'PHYSICAL_DIFFERENCE',difference:-2}]}},false).key,'fresh')
 assert.equal(util.inventoryCheckNextStep({result:{rows:[{status:'MISSING_WMS'}]}},false).key,'evidence')
 assert.equal(util.inventoryCheckNextStep({result:{rows:[{status:'FROZEN_CHECK'}]}},false).key,'evidence')
 assert.equal(util.inventoryCheckNextStep({result:{rows:[{status:'MATCHED'}]},adjustments:[{state:'DRAFT'}]},false).key,'adjustment')
 const source=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/purchase/WarehouseControl.vue'),'utf8')),nav=[],lookup=[],navApi={...util,list:()=>new Promise(r=>lookup.push(r))},navDef=moduleOf(source.script.content,navApi).default,navState={document:{id:195},showDetail:true,pendingLoading:false,$router:{push:async route=>nav.push(route)},$message:{warning:()=>{throw Error('Unexpected missing transfer')}}}
 const inspect=navDef.methods.inspectPending.call(navState,transfer);lookup[0]({data:{total:1,rows:[{id:13}]}});await inspect;assert.equal(nav[0].path,'/oms-supplychain/warehouseTransfers');assert.equal(nav[0].query.return_check_id,'195');assert.equal(nav[0].query.id,'13')
 const stale=navDef.methods.inspectPending.call(navState,transfer);navState.document={id:196};lookup[1]({data:{total:1,rows:[{id:13}]}});await stale;assert.equal(nav.length,1);assert.equal(navState.pendingLoading,false)
 console.log('PASS inventory-check assertions: templates, default full scope, CSV identity and validation, unknown-vs-zero, progress, deduplication and stale scope responses')
}
main().catch(e=>{console.error(e);process.exitCode=1})
