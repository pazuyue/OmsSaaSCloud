const assert = require('assert/strict'), fs = require('fs'), path = require('path'), vm = require('vm'), babel = require('@babel/core'), compiler = require('vue-template-compiler')
const base = path.join(__dirname, '../src')
const files = ['components/CarrierSelect/index.vue', ...['Standard', 'Channels', 'Wms', 'Capabilities', 'Todos', 'CarrierWorkspace', 'CarrierScope', 'CarrierCode', 'CarrierAudit', 'CarrierBatchMappings', 'CarrierConversionPreview'].map(n => `views/oms/common/carriers/${n}.vue`), ...['PurchaseReturnCreate', 'PurchaseReturnCarrierSelect', 'PurchaseReturnFollowup', 'PurchaseReturnFulfillment', 'PurchaseReclaimDetail', 'ReturnCarrierCapture'].map(n => `views/oms/purchase/${n}.vue`), ...['ShipmentCarrierDialog', 'RefundPlatformActions', 'PlatformShippingConfigDialog', 'PlatformShippingStatus', 'ShipmentDetail', 'ShipmentPackageCards', 'OrderBusinessSettings'].map(n => `views/oms/order/${n}.vue`)]
const transform = text => babel.transformSync(text, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
for (const file of files) { const source = compiler.parseComponent(fs.readFileSync(path.join(base, file), 'utf8')); assert.deepEqual(compiler.compile(source.template.content).errors, [], file); transform(source.script.content) }
const csv = { exports: {} }; vm.runInNewContext(transform(fs.readFileSync(path.join(base, 'utils/carrierCsv.js'), 'utf8')), csv)
const parse = csv.exports.parseCarrierCsv
const data = parse('\uFEFFexternal_code,external_name,enabled,disabled_reason\r\n"001/sF.a","测试,快递",1,\r\n"001/SF.a","双引号""名称",0,"核实中"\r\n')
assert.equal(data[0].external_code, '001/sF.a'); assert.equal(data[1].external_code, '001/SF.a'); assert.equal(data[0].external_name, '测试,快递'); assert.equal(data[1].external_name, '双引号"名称')
assert.throws(() => parse('external_code,external_name,enabled,disabled_reason\na,b,yes,')); assert.throws(() => parse('external_code,external_name,enabled,disabled_reason\n"unclosed'))
const presentation = { exports: {} }; vm.runInNewContext(transform(fs.readFileSync(path.join(base, 'utils/carrierPresentation.js'), 'utf8')), presentation)
assert.equal(presentation.exports.carrierInfo({ carrier_snapshot: '{"standard_name":"测试快递"}' }).standard_name, '测试快递')
assert.equal(Object.keys(presentation.exports.carrierInfo({ carrier_resolution_json: 'broken' })).length, 0)
const purpose = { exports: {} }; vm.runInNewContext(transform(fs.readFileSync(path.join(base, 'utils/carrierMappingPurpose.js'), 'utf8')), purpose)
for (const value of ['WMS_OUTBOUND_CREATE', 'WMS_RETURN_CREATE', 'WMS_OUTBOUND_ACTUAL', 'WMS_RETURN_ACTUAL']) {
  const incoming = value.endsWith('_ACTUAL'), row = purpose.exports.mappingForm({ system_type: 'WMS', purpose: value, direction: 'BOTH', is_default: 1, standard_code: 'SF', external_code: '001/sF.a' })
  assert.equal(row.direction, incoming ? 'IN' : 'OUT'); assert.equal(row.is_default, incoming ? 0 : 1)
  assert.equal(purpose.exports.mappingFlow(row), incoming ? 'WMS 001/sF.a → OMS SF' : 'OMS SF → WMS 001/sF.a')
}
for (const action of ['CHANNEL_SHIPPING', 'CHANNEL_EXCHANGE_LOGISTICS', 'CHANNEL_RETURN_IDENTIFY', 'CHANNEL_RETURN_BACKFILL']) {
  const incoming = action === 'CHANNEL_RETURN_IDENTIFY', row = purpose.exports.mappingForm({ system_type: 'CHANNEL', purpose: action, direction: 'BOTH', is_default: 1 })
  assert.equal(row.direction, incoming ? 'IN' : 'OUT'); assert.equal(row.is_default, incoming ? 0 : 1)
}
const preferences = { exports: {} }; vm.runInNewContext(transform(fs.readFileSync(path.join(base, 'utils/carrierOutputPreferences.js'), 'utf8')), preferences)
const output = preferences.exports.outputPreferences
assert.equal(output([{ standard_id: 1 }], [{ other_count: 0 }])[0].choices, 1)
assert.equal(output([{ standard_id: 1 }], [{ other_count: 1, default_count: 1 }])[0].preferred, 0)
assert.equal(output([{ standard_id: 1 }, { standard_id: 1 }], [{}, {}])[0].choices, 2)
assert.equal(output([{ standard_id: 1 }, { standard_id: 1, product_code: 'FAST' }], [{}, {}])[0].choices, 1)
const preferredJobs = [], preferredPending = []
const workspace = { exports: {}, require: name => name === '@/utils/carrierMappingPurpose' ? purpose.exports : name === '@/utils/carrierOutputPreferences' ? preferences.exports : name === '@/api/common/carriers' ? { mappingOutputOptions: () => new Promise((resolve, reject) => preferredPending.push({resolve, reject})) } : {}, setTimeout: fn => { preferredJobs.push(fn); return preferredJobs.length }, clearTimeout: () => {} }
vm.runInNewContext(transform(compiler.parseComponent(fs.readFileSync(path.join(base, 'views/oms/common/carriers/CarrierWorkspace.vue'), 'utf8')).script.content), workspace)
assert(workspace.exports.default.methods.validateEditor.call({type:'mapping',system:'WMS',form:{connection_id:'1',scope_level:'WAREHOUSE',owner_code:'O1',real_store_code:''}}).includes('实体仓'))
assert(workspace.exports.default.methods.validateEditor.call({type:'mapping',system:'CHANNEL',form:{scope_level:'SHOP',shop_id:''}}).includes('店铺'))
let refreshed = 0
const editor = { type: 'mapping', form: { system_type: 'WMS', purpose: 'WMS_OUTBOUND_CREATE', direction: 'OUT', directory_id: 9, is_default: 1 }, directories: [{ id: 9 }], loadDirectories() { refreshed++ } }
workspace.exports.default.methods.updateScope.call(editor, { ...editor.form, purpose: 'WMS_OUTBOUND_ACTUAL' })
assert.equal(editor.form.direction, 'IN'); assert.equal(editor.form.is_default, 0); assert.equal(editor.form.directory_id, ''); assert.equal(editor.directories.length, 0); assert.equal(refreshed, 1)
const previews = [], batchScope = { exports: {}, require: name => name === '@/api/common/carriers' ? { mappingBatchPreview: () => new Promise(resolve => previews.push(resolve)) } : {} }
vm.runInNewContext(transform(compiler.parseComponent(fs.readFileSync(path.join(base, 'views/oms/common/carriers/CarrierBatchMappings.vue'), 'utf8')).script.content), batchScope)
let jobs = [], pending = [], emitted = []
const scope = { exports: {}, require: () => ({ carrierOptions: () => new Promise((resolve, reject) => pending.push({ resolve, reject })) }), setTimeout: fn => { jobs.push(fn); return jobs.length }, clearTimeout: () => {} }
vm.runInNewContext(transform(compiler.parseComponent(fs.readFileSync(path.join(base, files[0]), 'utf8')).script.content), scope)
const def = scope.exports.default, component = { ...def.data(), context: { system_type: 'WMS', connection_id: '1' }, standardOnly: false, $emit: (...args) => emitted.push(args) }
Object.assign(component, Object.fromEntries(Object.entries(def.methods).map(([k, fn]) => [k, fn.bind(component)])))
const management = { exports: {} }; vm.runInNewContext(transform(fs.readFileSync(path.join(base, 'utils/carrierManagement.js'), 'utf8')), management)
const history = management.exports.auditDetails({ object_type:'MAPPING',action:'UPDATE',object_id:9,before_json:'{"enabled":1,"evidence":"清单V1"}',after_json:'{"enabled":0,"evidence":"仓库确认V2"}',display_standard_name:'测试承运商' })
assert(history.summary.includes('测试承运商')); assert.equal(history.changes.find(c=>c.field==='配置状态').before,'已启用');assert.equal(history.changes.find(c=>c.field==='配置状态').after,'已停用');assert.equal(history.evidence,'仓库确认V2')
const sceneRequests=[],sceneEvents=[],sceneModule={exports:{},require:name=>name==='@/api/common/carriers'?{carrierPreview:()=>new Promise(resolve=>sceneRequests.push(resolve))}:name==='@/utils/carrierManagement'?management.exports:name==='@/utils/carrierMappingPurpose'?purpose.exports:{}}
vm.runInNewContext(transform(compiler.parseComponent(fs.readFileSync(path.join(base,'views/oms/common/carriers/CarrierConversionPreview.vue'),'utf8')).script.content),sceneModule)
async function main() {
  const sceneDef=sceneModule.exports.default,scene={...sceneDef.data(),$nextTick:fn=>fn(),$refs:{},metadata:{},visible:true,seed:{id:9,revision:1,system_type:'CHANNEL',purpose:'CHANNEL_SHIPPING'},scenario:'CURRENT',source:{system_type:'OMS',standard_id:1},target:{system_type:'CHANNEL',provider:'TMALL',purpose:'CHANNEL_SHIPPING'},$emit:(...args)=>sceneEvents.push(args)}
  for(const [key,fn] of Object.entries(sceneDef.methods))scene[key]=fn.bind(scene)
  for(const [key,fn] of Object.entries(sceneDef.computed))Object.defineProperty(scene,key,{get:()=>fn.call(scene)})
  const obsoleteScene=scene.check();scene.source.standard_id=2;sceneRequests.shift()({data:{source:{available:true},target:{available:true,mapping_id:9,revision:1}}});await obsoleteScene;assert.equal(scene.result,null);assert.equal(sceneEvents.length,0,'a changed scene cannot publish an obsolete success badge')
  const currentScene=scene.check();sceneRequests.shift()({data:{source:{available:true},target:{available:true,mapping_id:9,revision:1}}});await currentScene;assert.equal(sceneEvents.length,1);assert.equal(sceneEvents[0][1].available,true)
  const firstPreferred={standard_id:1,product_code:'',is_default:1},sameGroup={standard_id:'1',product_code:'',is_default:1},otherProduct={standard_id:1,product_code:'FAST',is_default:1}
  batchScope.exports.default.methods.choosePreferred.call({rows:[firstPreferred,sameGroup,otherProduct]},firstPreferred);assert.equal(sameGroup.is_default,0);assert.equal(otherProduct.is_default,1)
  const batchEditor = { busy: false, preferredLoading: false, preferredError: '', scope: { purpose: 'CHANNEL_SHIPPING' }, evidence: 'first', rows: [{ directory_id: 1, standard_id: 2, is_default: 1 }], preview: null }
  batchEditor.batchInput = batchScope.exports.default.methods.batchInput.bind(batchEditor)
  const obsoletePreview = batchScope.exports.default.methods.check.call(batchEditor)
  batchEditor.evidence = 'changed while request pending'; previews.shift()({ data: { request_key: 'OLD' } }); await obsoletePreview
  assert.equal(batchEditor.preview, null, 'pending preview cannot authorize data edited after the check started')
  const currentPreview = batchScope.exports.default.methods.check.call(batchEditor); previews.shift()({ data: { request_key: 'CURRENT' } }); await currentPreview
  assert.equal(batchEditor.preview.request_key, 'CURRENT')
  const preferenceEditor = { editor: true, form: { standard_id: 1, is_default: 1 }, preferredKey: 'first', preferredEpoch: 0 }
  workspace.exports.default.methods.loadPreferred.call(preferenceEditor); const oldPreferred = preferredJobs.shift()()
  preferenceEditor.form = { standard_id: 2, is_default: 1 }; preferenceEditor.preferredKey = 'second'
  workspace.exports.default.methods.loadPreferred.call(preferenceEditor); const newPreferred = preferredJobs.shift()()
  preferredPending[1].resolve({ data: { rows: [{ other_count: 0 }] } }); await newPreferred
  preferredPending[0].resolve({ data: { rows: [{ other_count: 4, default_count: 1 }] } }); await oldPreferred
  assert.equal(preferenceEditor.preferredStats.choices, 1, 'stale candidate lookup must not change the newly selected standard or action')
  component.search('旧连接'); const first = jobs.shift()(); component.context = { system_type: 'WMS', connection_id: '2' }; component.search('新连接'); const second = jobs.shift()()
  pending[1].resolve({ data: { rows: [{ id: 2, standard_name: '第二连接', available: true }] } }); await second
  pending[0].resolve({ data: { rows: [{ id: 1, standard_name: '第一连接', available: true }] } }); await first
  assert.equal(component.rows[0].id, 2, 'stale response must not overwrite the changed warehouse context')
  component.select(2); assert.equal(emitted[0][1], 2, 'stable standard identity is submitted')
  component.search('失败'); const fail = jobs.shift()(); pending[2].reject(new Error('网络失败')); await fail
  assert.equal(component.rows.length, 0); assert.equal(component.error, '网络失败'); assert.equal(component.loaded, false, 'failed query is different from empty options')
  assert(!fs.readFileSync(path.join(base, files[0]), 'utf8').includes('allow-create'), 'business selector cannot create a formal carrier from text')
  console.log(`PASS ${files.length} Vue templates/scripts, purpose-derived channel/WMS directions, preferred candidate grouping/response ordering, cleared directory, exact CSV codes/quotes/validation, changed-context response ordering, stable identity and query failure states`)
}
main().catch(e => { console.error(e); process.exitCode = 1 })
