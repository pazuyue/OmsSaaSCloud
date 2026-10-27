const fs=require('fs'),path=require('path'),assert=require('assert/strict'),vm=require('vm'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const file=path.join(__dirname,'../src/views/oms/purchase/WarehouseControl.vue'),c=compiler.parseComponent(fs.readFileSync(file,'utf8'))
assert.deepEqual(compiler.compile(c.template.content).errors,[])
const pending=[],api={list:()=>new Promise(r=>pending.push(r))},scope={exports:{},require:()=>api}
vm.runInNewContext(babel.transformSync(c.script.content,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code,scope)
const def=scope.exports.default,m=def.methods
assert.equal(m.quantities([{stock_unit:'件',out_quantity:3},{stock_unit:'套',out_quantity:2}],'out_quantity'),'3 件 + 2 套')
assert.equal(m.value(null),'未提供');assert.equal(m.value(0),0);assert.equal(m.signed(-2),'-2');assert.equal(m.signed(null),'待核查')
assert.equal(m.label('MISSING_WMS'),'仓库未返回该库存');assert.equal(m.label('WAITING_POST'),'存在待确认单据，暂不调整')
assert.equal(m.label.call({transfer:true},'STOPPED'),'未出库剩余已停止');assert.equal(m.label.call({transfer:false},'STOPPED'),'已停止，可继续');
async function main(){const s={...def.data(),kind:'transfers'},a=m.load.call(s),b=m.load.call(s);pending[1]({data:{rows:[{id:2}],total:1}});await b;pending[0]({data:{rows:[{id:1}],total:99}});await a;assert.equal(s.rows[0].id,2);assert.equal(s.total,1);console.log('PASS warehouse-control template, mixed units, absent-vs-zero, signed differences and stale-list response protection')}
main().catch(e=>{console.error(e);process.exitCode=1})
