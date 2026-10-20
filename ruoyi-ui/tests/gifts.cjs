const assert=require('assert/strict'),fs=require('fs'),path=require('path'),compiler=require('vue-template-compiler'),babel=require('@babel/core')
for(const file of ['ShipmentDispatchLines.vue','GiftManagement.vue','GiftSkuPicker.vue','OrderGifts.vue','OrderIdentity.vue','OrderLineIdentity.vue','RefundDetail.vue','index.vue']){
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
