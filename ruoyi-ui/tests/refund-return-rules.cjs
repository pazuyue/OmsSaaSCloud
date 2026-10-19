const assert=require('assert/strict'),fs=require('fs'),path=require('path'),vm=require('vm'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const root=path.join(__dirname,'../src/views/oms/order')
function load(file,api={}){const c=compiler.parseComponent(fs.readFileSync(path.join(root,file),'utf8'));assert.deepEqual(compiler.compile(c.template.content).errors,[],file);const ctx={exports:{},require:()=>api};vm.runInNewContext(babel.transformSync(c.script.content,{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code,ctx);return ctx.exports.default}
function instance(c,extra={}){const ctx={...c.data(),...extra};for(const [key,fn] of Object.entries(c.methods))ctx[key]=fn.bind(ctx);for(const [key,fn] of Object.entries(c.computed||{}))Object.defineProperty(ctx,key,{get:()=>fn.call(ctx)});return ctx}
async function main(){
 const opened=[],pageResolves=[],shop={channel_id:7,channel_name:'Shop',shop_enabled:1}
 const page=instance(load('RefundReturnRules.vue',{refundReturnRules:()=>new Promise(r=>pageResolves.push(r))}),{$route:{query:{channelId:'999'}},$refs:{rules:{open:s=>opened.push(s.channel_id)}},$nextTick:fn=>fn()})
 const response=rows=>({data:{rows,total:rows.length,summary:{shops:rows.length,configured:0,auto_enabled:0}}})
 const invalid=page.openRequestedShop();pageResolves.shift()(response([]));await invalid;assert.equal(opened.length,0);assert.ok(page.contextError)
 page.$route.query.channelId='7';const valid=page.openRequestedShop();pageResolves.shift()(response([shop]));await valid;assert.deepEqual(opened,[7]);assert.equal(page.editorReadOnly,false)
 const old=page.load(),newer=page.load();pageResolves[1](response([{...shop,channel_name:'new'}]));await newer;pageResolves[0](response([{...shop,channel_name:'old'}]));await old;assert.equal(page.rows[0].channel_name,'new')
 page.openEditor({...shop,shop_enabled:0});assert.equal(page.editorReadOnly,true)
 const resolve={},saved=[];let pending=true,revision=5
 const api={refundParcelConfig:channel=>pending?new Promise(r=>{resolve[channel]=r}):Promise.resolve({data:{policy:{revision,store_codes:[]},warehouses:[]}}),saveRefundParcelConfig:async(channel,form)=>{saved.push([channel,form.revision]);revision++}}
 const editor=instance(load('RefundParcelSettings.vue',api),{$emit:()=>{},$message:{success:()=>{},warning:m=>{throw Error(m)}}})
 const first=editor.open({channel_id:1}),second=editor.open({channel_id:2})
 resolve[2]({data:{policy:{revision:5,store_codes:[]},warehouses:[]}});await second
 resolve[1]({data:{policy:{revision:1,store_codes:[]},warehouses:[]}});await first
 assert.equal(editor.shop.channel_id,2);assert.equal(editor.form.revision,5)
 pending=false;await editor.save();assert.deepEqual(saved,[[2,5]]);assert.equal(editor.form.revision,6,'inline save must refresh revision before a second save')
 await editor.save();assert.deepEqual(saved,[[2,5],[2,6]]);editor.readOnly=true;await editor.save();assert.equal(saved.length,2,'read-only view must never submit a write')
 const intake=fs.readFileSync(path.join(root,'management.vue'),'utf8');assert.ok(!intake.includes('parcelSettings')&&!intake.includes('RefundParcelSettings'))
 for(const f of ['RefundDetail.vue','RefundList.vue','RefundParcels.vue']){const source=fs.readFileSync(path.join(root,f),'utf8');assert.ok(source.includes('/oms-order/afterSaleRules/returns'),f);load(f)}
 console.log('PASS paged rule list, contextual drawer, stale responses, read-only view and repeat saves')
}
main().catch(e=>{console.error(e);process.exitCode=1})
