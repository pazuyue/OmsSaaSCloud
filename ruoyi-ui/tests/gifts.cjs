const assert=require('assert/strict'),fs=require('fs'),path=require('path'),compiler=require('vue-template-compiler'),babel=require('@babel/core')
for(const file of ['GiftStrategyEditor.vue','GiftProductPicker.vue','GiftRewardTable.vue','GiftShopGroups.vue','ShipmentDispatchLines.vue','GiftManagement.vue','GiftSkuPicker.vue','OrderGifts.vue','OrderIdentity.vue','OrderLineIdentity.vue','RefundDetail.vue','index.vue']){
 const s=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/order',file),'utf8'))
 assert.deepEqual(compiler.compile(s.template.content).errors,[],file)
 babel.transformSync(s.script.content,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]})
}
// Exercise the real view-model actions: preserve source identity, quantities and plan versions.
const source=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/order/OrderGifts.vue'),'utf8')).script.content
const code=babel.transformSync(source,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code
const mod={exports:{}};new Function('require','module','exports',code)(()=>({}),mod,mod.exports)
const view=mod.exports.default,ctx={...view.data(),row:{id:7,control_version:3,triggers:[{id:11,review_state:'APPROVED'}]},eligibleRefunds:[{id:11}],options:async()=>{}}
view.methods.startReturn.call(ctx,{id:22,unarranged_quantity:1,sources:[{id:33,shipped_quantity:2}]})
assert.equal(ctx.form.gift_plan_id,7);assert.equal(ctx.form.shipment_line_id,33);assert.equal(ctx.form.quantity,1);assert.equal(ctx.form.refund_id,11);assert.equal(ctx.form.confirmed,false)
view.methods.startWaive.call(ctx,{id:22,unarranged_quantity:1})
assert.equal(ctx.waiver.order_line_id,22);assert.equal(ctx.waiver.quantity,1);assert.equal(ctx.waiver.confirmed,false)
console.log('PASS gift rule editor, order/refund pages and return/waiver view-model identities')
function component(file,api={}){
 const parsed=compiler.parseComponent(fs.readFileSync(path.join(__dirname,'../src/views/oms/order',file),'utf8'))
 const js=babel.transformSync(parsed.script.content,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code
 const module={exports:{}};new Function('require','module','exports',js)(name=>name==='@/api/order/gift'?api:{},module,module.exports)
 return module.exports.default
}
async function shopGroups(){
 const pending=[],saved=[]
 const ui=component('GiftShopGroups.vue',{giftShopEffective:id=>new Promise(resolve=>pending.push({id,resolve})),saveGiftShopGroup:async body=>{saved.push(JSON.parse(JSON.stringify(body)));return {data:{id:7}}}})
 const scopes=[{id:7,name:'First',enabled:1,revision:3,channels:[11],links:[{group_id:1,continue_after_match:true},{group_id:2,continue_after_match:false}]},{id:8,name:'Second',enabled:1,revision:1,channels:[22],links:[{group_id:2,continue_after_match:true},{group_id:1,continue_after_match:false}]}]
 const ctx={...ui.data(),scopes,groups:[{id:1},{id:2},{id:3}],shops:[{channel_id:11,channel_name:'Shop A'},{channel_id:22,channel_name:'Shop B'}],$message:{warning:message=>{throw Error(message)},success:()=>{}},$emit:()=>{}}
 for(const [key,method] of Object.entries(ui.methods))ctx[key]=method.bind(ctx)
 for(const [key,get] of Object.entries(ui.computed))Object.defineProperty(ctx,key,{get:()=>get.call(ctx)})
 assert.equal(ctx.selected.id,7)
 ctx.selectedId=7;ctx.keyword='Shop B';assert.equal(ctx.selected.id,8)
 ctx.keyword='missing';assert.equal(ctx.selected,undefined);ctx.keyword=''
 ctx.edit(scopes[0]);ctx.move(0,1);ctx.form.links[0].continue_after_match=true
 assert.deepEqual(scopes[0].links.map(l=>l.group_id),[1,2]);assert.equal(scopes[0].links[1].continue_after_match,false)
 assert.deepEqual(scopes[1].links.map(l=>l.group_id),[2,1]);assert.deepEqual(ctx.available.map(g=>g.id),[3])
 ctx.adding=2;ctx.add();assert.equal(ctx.form.links.length,2)
 assert.equal(ctx.owner(11),undefined);assert.equal(ctx.owner(22).id,8)
 await ctx.save();assert.equal(saved[0].revision,3);assert.deepEqual(saved[0].links.map(l=>l.group_id),[2,1]);assert.equal(ctx.selectedId,7)
 ctx.previewShop=11;const old=ctx.loadEffective();ctx.previewShop=22;const current=ctx.loadEffective()
 pending[1].resolve({data:{channel_id:22,shop_groups:[]}});await current
 pending[0].resolve({data:{channel_id:11,shop_groups:scopes}});await old
 assert.equal(ctx.effective.channel_id,22);assert.equal(ctx.previewLoading,false)
 const navigation=[];const management=component('GiftManagement.vue')
 management.methods.copy.call({$router:{push:route=>navigation.push(route)}},{id:9})
 assert.deepEqual(navigation[0].query,{copy:'9'})
 console.log('PASS shop group selection, independent ordering, revision submission, duplicate links, preview race and strategy copy')
}
shopGroups().catch(error=>{console.error(error);process.exitCode=1})
require('./gift-strategy-editor.cjs')
require('./gift-catalog.cjs')
