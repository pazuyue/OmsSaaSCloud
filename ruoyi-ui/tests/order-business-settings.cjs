const assert = require('assert/strict'), fs = require('fs'), path = require('path')
const compiler = require('vue-template-compiler'), babel = require('@babel/core')
const root = path.join(__dirname, '../src/views/oms/order')
function load(name, imports = {}) {
  let source = fs.readFileSync(path.join(root, name), 'utf8')
  if (name.endsWith('.vue')) {
    const component = compiler.parseComponent(source)
    assert.deepEqual(compiler.compile(component.template.content).errors, [], name)
    source = component.script.content
  }
  const module = { exports: {} }
  const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  new Function('require', 'module', 'exports', code)(name => imports[name] || {}, module, module.exports)
  return module.exports.default || module.exports
}
for (const name of fs.readdirSync(root).filter(name => name.endsWith('.vue'))) load(name)
const presentation = load('orderBusinessPresentation.js')
const nav = p => presentation.permittedSections(p).flatMap(g => g.items).map(i => i.key)
assert.ok(nav(['order:refund:config']).includes('refund-returns'))
assert.ok(!nav(['order:refund:config']).includes('conversion'))
assert.ok(nav(['order:shipment:platformConfig']).includes('shipping'))
assert.equal(nav(['*:*:*']).length, 16)
assert.equal(presentation.legacySettingsLocation({path:'/oms-order/settings',query:{tab:'review',channelId:7}}).query.section, 'review')
assert.equal(presentation.legacySettingsLocation({path:'/oms-order/afterSaleRules/settings',query:{tab:'authorization',channelId:7}}).query.section, 'platform')
assert.equal(presentation.legacySettingsLocation({path:'/oms-order/settings',query:{tab:'sources'}}).query.scope, 'sources')
assert.ok(presentation.businessFlow({shop_enabled:1, order:{auto_convert:1}, shipping:{mode:'SIMULATION'}}).includes('人工确认下发仓库'))
assert.ok(presentation.businessFlow({shop_enabled:1, shipping:{mode:'SIMULATION'}}).includes('模拟回传平台'))
assert.ok(!fs.readFileSync(path.join(root,'OrderIntakeSettings.vue'),'utf8').includes('v-model="shop.auto_convert"'), 'one canonical auto-conversion editor')
function instance(component, extra) {
  const ctx = {...component.data.call(extra || {}), ...extra}
  for (const [name, fn] of Object.entries(component.methods || {})) ctx[name] = fn.bind(ctx)
  for (const [name, fn] of Object.entries(component.computed || {})) if (!Object.hasOwn(ctx,name)) Object.defineProperty(ctx,name,{get:()=>fn.call(ctx)})
  return ctx
}
async function main() {
  const mixin = load('configurationEditor.js')
  const editor = instance(mixin, {form:{revision:1,enabled:0},loading:false,saving:false})
  editor.acceptBaseline(); assert.equal(editor.dirty,false)
  editor.form.enabled=1; assert.equal(editor.dirty,true)
  editor.acceptBaseline(); assert.equal(editor.dirty,false)
  mixin.watch.loading.call(editor,true); assert.equal(editor.editorBaseline,null)
  const pending = {}
  const component = load('OrderBusinessSettings.vue', {'@/utils/request': config=>new Promise(resolve=>pending[config.url]=resolve), './orderBusinessPresentation':presentation})
  const ctx = instance(component, {$route:{query:{channelId:'7',section:'overview'}}, $store:{getters:{permissions:['order:refund:config']},state:{user:{id:2}}}, $nextTick:async()=>{}, $refs:{}, $confirm:async()=>{throw Error('cancel')}})
  ctx.dirtyParts={editor:true}; assert.equal(await ctx.canLeave(),false)
  ctx.$confirm=async()=>{}; assert.equal(await ctx.canLeave(),true)
  ctx.savingParts={editor:true}; assert.equal(await ctx.canLeave(),false)
  ctx.savingParts={}; ctx.dirtyParts={}
  const first=ctx.loadDetail();ctx.$route.query.channelId='8';const second=ctx.loadDetail()
  pending['/order/business-settings/shops/8']({data:{channel_id:8}});await second
  pending['/order/business-settings/shops/7']({data:{channel_id:7}});await first
  assert.equal(ctx.model.channel_id,8);assert.equal(ctx.loading,false)
  const model=ctx.model;ctx.$route.query.section='runtime';await ctx.routeChanged();assert.equal(ctx.model,model,'section navigation reuses the shop summary and lazily opens only the requested editor')
  assert.equal(ctx.canRuntime,false)
  ctx.$store.getters.permissions=['*:*:*'];ctx.$store.state.user.id=1;assert.equal(ctx.canRuntime,true)
  const tasks=load('IntakeTasks.vue', {'./intakePresentation':load('intakePresentation.js')})
  const task=instance(tasks,{initialQuery:{kind:'REFUND',channelId:'7',taskId:'31'}})
  assert.equal(task.query.channelId,7);assert.equal(task.query.taskId,'31')
  task.search=()=>{};task.filter('EXCHANGE','32','8');assert.equal(task.query.channelId,8);assert.equal(task.query.kind,'EXCHANGE')
  const submitted=[];const sync=instance(load('OrderSyncTasks.vue'),{$route:{query:{channelId:'7'}},$router:{replace:route=>submitted.push(route)},$refs:{}})
  sync.submitted({kind:'ORDER',data:{task_ids:[123]}});assert.equal(submitted[0].query.taskId,123);assert.equal(submitted[0].query.channelId,'7')
  sync.submitted({kind:'REFUND',data:{id:456}});assert.equal(submitted[1].query.taskId,456)
  console.log('PASS all order Vue templates/scripts; unified navigation, permission scoping, independent conversion, dirty/save guards, stale shop responses and task context')
}
main().catch(error=>{console.error(error);process.exitCode=1})
