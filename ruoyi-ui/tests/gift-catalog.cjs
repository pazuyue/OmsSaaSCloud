const assert=require('assert/strict'),fs=require('fs'),path=require('path'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const opened=[]
function component(file,dependencies={}){
  const parsed=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms',file),'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors,[],file)
  const code=babel.transformSync(parsed.script.content,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code
  const module={exports:{}};new Function('require','module','exports','window',code)(name=>dependencies[name]||{},module,module.exports,{open:(...args)=>opened.push(args)})
  return module.exports.default
}
async function run(){
  const queries=[]
  const picker=component('order/GiftProductPicker.vue',{'@/api/order/gift':{giftGoodsSearch:async query=>{queries.push(query);return {data:{rows:[],total:0}}}}})
  const ctx={...picker.data(),giftOnly:true,keyword:'GIFT',field:'skuSn',$router:{resolve:route=>({href:JSON.stringify(route)})}}
  await picker.methods.search.call(ctx,true);ctx.giftOnly=false;await picker.methods.search.call(ctx,true)
  assert.deepEqual(queries.map(q=>q.use),['GIFT','PURCHASE'])
  picker.methods.openGoods.call(ctx,'G');assert.equal(opened[0][1],'_blank');assert.equal(JSON.parse(opened[0][0]).query.skuSn,'G')
  const pending=[]
  const goods=component('goods/components/GoodsInfo.vue',{'@/api/goods/info':{giftReferences:id=>new Promise(resolve=>pending.push({id,resolve}))},'@/utils/auth':{getToken:()=>''}})
  const form={id:1,goodsName:'Unsaved name'}
  const view={...goods.data(),form,$router:ctx.$router}
  const old=goods.methods.loadGiftReferences.call(view);view.form={id:2,goodsName:'New draft'};const current=goods.methods.loadGiftReferences.call(view)
  pending[1].resolve({data:[{group_id:22,group_name:'Latest'}]});await current
  pending[0].resolve({data:[{group_id:11,group_name:'Old'}]});await old
  assert.equal(view.giftRefs[0].group_id,22);assert.equal(view.giftRefsLoading,false);assert.equal(view.form.goodsName,'New draft')
  goods.methods.openGiftStrategy.call(view,{group_id:22});assert.equal(opened[1][1],'_blank');assert.equal(JSON.parse(opened[1][0]).query.id,'22');assert.equal(view.form.goodsName,'New draft')
  console.log('PASS gift-only server search scope, purchase scope, goods/strategy links preserving drafts and stale reference response protection')
}
run().catch(error=>{console.error(error);process.exitCode=1})
