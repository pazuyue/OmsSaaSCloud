const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/channel')
function load(source, api = {}) { const context = { exports: {}, require: () => api }; vm.runInNewContext(babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code, context); return context.exports }
const labels = load(fs.readFileSync(path.join(root, 'platformLogLabels.js'), 'utf8'))
for (const file of ['PlatformLogCenter.vue', 'PlatformLogLink.vue', 'PlatformWorkspace.vue']) { const component = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8')); assert.deepEqual(compiler.compile(component.template.content).errors, [], file) }
const source = compiler.parseComponent(fs.readFileSync(path.join(root, 'PlatformLogCenter.vue'), 'utf8')).script.content
const component = load(source, labels).default
const progress = load(fs.readFileSync(path.join(root, '../order/exchangePlatformProgress.js'), 'utf8')).platformProgress
const statusTemplate = compiler.parseComponent(fs.readFileSync(path.join(root, '../order/ExchangePlatformStatus.vue'), 'utf8'))
assert.deepEqual(compiler.compile(statusTemplate.template.content).errors, [])
const ready = { source_kind: 'PLATFORM', state: 'PROCESSING', approved_version: 1, platform_status: '12', platform_operation: 'NONE', requested_quantity: 2, platform_shipped_quantity: 2, platform_packaged_quantity: 2, platform_tracking_count: 1, platform_write_enabled: 1 }
assert.equal(progress(ready).code, 'READY')
assert.equal(progress({ ...ready, source_kind: 'INTERNAL', state: 'COMPLETED' }).code, 'LOCAL_ONLY', 'Local fulfillment completion is not a platform acknowledgement')
assert.equal(progress({ ...ready, source_kind: 'INTERNAL', fulfillment_mode: 'UNSHIPPED' }).code, 'ORIGINAL_ORDER')
assert.equal(progress({ ...ready, platform_shipped_quantity: 1 }).code, 'WAIT_SHIPMENT')
assert.equal(progress({ ...ready, platform_packaged_quantity: 1 }).code, 'WAIT_PACKAGE')
assert.equal(progress({ ...ready, platform_tracking_count: 2 }).code, 'MANUAL_TRACKING')
assert.equal(progress({ ...ready, platform_write_enabled: 0 }).code, 'WRITE_DISABLED')
assert.equal(progress({ ...ready, platform_operation: 'UNKNOWN', platform_action: 'CONSIGN' }).code, 'UNKNOWN')
assert.equal(progress({ ...ready, platform_operation: 'PENDING', platform_action: 'AGREE' }).title, '同意换货待执行')
assert.equal(progress({ ...ready, platform_operation: 'FAILED' }).code, 'FAILED')
assert.equal(progress({ ...ready, state: 'COMPLETED', platform_status: '13' }).code, 'CONFIRMED')
assert.notEqual(progress({ ...ready, fulfillment_round: 2, platform_status: '13' }).code, 'CONFIRMED', 'An old platform state must not confirm a new fulfillment round')
assert.equal(progress({ ...ready, linked_exchange_id: 13 }).code, 'MANUAL')
assert.equal(progress({ ...ready, fulfillment_hold: 1 }).code, 'PAUSED')
assert.equal(progress({ ...ready, state: 'BLOCKED' }).code, 'BLOCKED')
assert.equal(component.data().query.numberType, 'AUTO')
const filters = { query: { category: 'EXCHANGE', method: 'old', allTime: false }, businessActions: labels.businessActions }
assert.ok(component.computed.availableActions.call(filters).every(item => item.category === 'EXCHANGE'))
assert.ok(component.computed.availableActions.call(filters).some(item => item.label === '回传换出物流'))
component.methods.numberChanged.call(filters, 'HH_TEST')
assert.equal(filters.query.allTime, true)
component.methods.numberChanged.call(filters, '')
assert.equal(filters.query.allTime, false)
component.methods.categoryChanged.call(filters)
assert.equal(filters.query.method, '')
assert.equal(labels.primaryRef({ refs: [{ type: 'OMS_ORDER', number: 'SO1' }, { type: 'EXCHANGE', number: 'HH1' }] }).number, 'HH1')
assert.equal(labels.primaryRef({ refs: [{ type: 'PLATFORM_EXCHANGE', number: '1' }, { type: 'PLATFORM_EXCHANGE', number: '2' }] }), null, 'Bulk query must not show only its first document as the overall business state')
assert.equal(component.methods.transport({ environment: 'SIMULATION', http_status: 200 }), '本地模拟，未请求真实平台')
assert.equal(component.methods.transport({ environment: 'REAL', entry_kind: 'API', request_sent: 1 }), '已发送，未收到明确响应')
const link = load(compiler.parseComponent(fs.readFileSync(path.join(root, 'PlatformLogLink.vue'), 'utf8')).script.content).default
let route
link.methods.open.call({ number: 'HH_TEST', numberType: 'EXCHANGE', $router: { push: v => { route = v } } })
assert.equal(route.query.numberType, 'EXCHANGE')
assert.equal(route.query.allTime, 'true')
const routed = { $route: { query: { number: 'HH_TEST', category: 'EXCHANGE', method: 'tmall.exchange.consigngoods' } }, search() {} }
component.methods.fromRoute.call(routed)
assert.equal(routed.query.numberType, 'AUTO')
assert.equal(routed.query.allTime, true)
assert.equal(routed.query.category, 'EXCHANGE')
assert.equal(routed.query.method, 'tmall.exchange.consigngoods')
async function main() {
 let resolve
 const deferred = new Promise(r => { resolve = r })
 const definition = load(source, { ...labels, platformGet: () => deferred }).default
 const context = { detail: { id: 1 }, payload: null, payloadLoading: false, payloadError: '' }
 const pending = definition.methods.loadPayload.call(context)
 context.detail = { id: 2 }
 resolve({ data: { request_json: 'old' } })
 await pending
 assert.equal(context.payload, null, 'A previous log payload cannot overwrite the newly selected log')
 console.log('PASS platform log center templates, environment labels, exact links and stale payload isolation')
}
main().catch(e => { console.error(e); process.exitCode = 1 })
