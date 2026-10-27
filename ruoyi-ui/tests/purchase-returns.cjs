const fs=require('fs'),path=require('path'),assert=require('assert/strict'),vm=require('vm'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const root=path.join(__dirname,'../src/views/oms')
function definition(file,api={}){const c=compiler.parseComponent(fs.readFileSync(path.join(root,file+'.vue'),'utf8'));assert.deepEqual(compiler.compile(c.template.content).errors,[],file);const scope={exports:{},require:()=>api};vm.runInNewContext(babel.transformSync(c.script.content,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code,scope);return scope.exports.default}
for(const f of ['purchase/PurchaseReturns','purchase/PurchaseReturnCreate','purchase/PurchaseReturnFollowup','purchase/PurchaseReclaimDetail','purchase/PurchaseReturnSummary','inventory/QualityInventory','purchase/ProcurementDetail'])definition(f)
const detail=definition('purchase/PurchaseReturns')
const state={detail:{lines:[{stock_unit:'件',quantity:3,accepted_quantity:2,posted_quantity:1},{stock_unit:'套',quantity:1,accepted_quantity:1,posted_quantity:1}]}}
assert.equal(detail.methods.units.call(state,'quantity'),'3 件 + 1 套')
assert.equal(detail.methods.units.call(state,'pending'),'1 件')
async function main(){for(const file of ['purchase/PurchaseReturns','inventory/QualityInventory']){const pending=[];const def=definition(file,{listReturns:()=>new Promise(r=>pending.push(r)),qualityList:()=>new Promise(r=>pending.push(r))});const state=def.data(),a=def.methods.load.call(state),b=def.methods.load.call(state);pending[1]({data:{rows:[{id:2}],total:1}});await b;pending[0]({data:{rows:[{id:1}],total:99}});await a;assert.equal(state.rows[0].id,2);assert.equal(state.total,1)}console.log('PASS purchase-return/quality templates, separate units, pending stock quantities and query race protection')}
main().catch(e=>{console.error(e);process.exitCode=1})
