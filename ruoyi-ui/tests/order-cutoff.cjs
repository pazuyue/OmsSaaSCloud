const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const compiler = require('vue-template-compiler')

const source = fs.readFileSync(path.join(__dirname, '../src/views/oms/order/OrderCutoffDialog.vue'), 'utf8')
const component = compiler.parseComponent(source)
const template = compiler.compile(component.template.content)
assert.deepEqual(template.errors, [], 'The dialog template must compile')
const compiled = babel.transformSync(component.script.content, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
let report, order, failRead = false, resolveRetry, retryCalls = 0
const api = {
  orderDetail: async() => { if (failRead) throw new Error('network'); return { data: order } },
  cutoffReport: async() => ({ data: { operations: [report] } }),
  retryCutoff: () => { retryCalls++; return new Promise(resolve => { resolveRetry = resolve }) }
}
const context = { exports: {}, require: () => api, setTimeout: () => 1, clearTimeout: () => {} }
vm.runInNewContext(compiled, context)
const definition = context.exports.default

function setup() {
  failRead = false; retryCalls = 0
  order = { id: 47, cutoff_id: 5, cutoff_state: 'PROCESSING', control_version: 9, shipments: [
    { id: 34, shipment_sn: 'XS_A', quantity: 6, receipt_review_pending_count: 1 },
    { id: 35, shipment_sn: 'XS_B', quantity: 4, cancelled_quantity: 4 },
    { id: 36, shipment_sn: 'XS_OTHER', quantity: 2, receipt_review_pending_count: 1 }
  ] }
  report = { id: 5, state: 'PARTIAL', check_version: 2, checked_version: 1, last_checked_at: '2026-10-15 18:00:00', items: [
    { shipment_id: 34, state: 'UNKNOWN', last_error: '存在待人工核对的出库回传，截单不能释放库存' },
    { shipment_id: 35, state: 'COMPLETED' }
  ] }
  const messages = [], events = []
  const message = entry => messages.push(entry)
  message.info = text => messages.push({ type: 'info', message: text })
  const instance = Object.assign(definition.data(), { id: 47, visible: true, order, operations: [report], $message: message, $emit: (...args) => events.push(args) })
  for (const [name, method] of Object.entries(definition.methods)) instance[name] = method.bind(instance)
  for (const [name, getter] of Object.entries(definition.computed)) Object.defineProperty(instance, name, { get: getter.bind(instance) })
  return { instance, messages, events }
}

async function main() {
  let { instance, messages } = setup()
  assert.equal(instance.reviewShipments.length, 1, 'Only unfinished shipments within the current cutoff can be linked')
  assert.match(instance.progressText, /已截停 4 件，剩余 6 件/)
  const pending = instance.retry()
  assert.equal(instance.saving, true)
  await instance.retry()
  assert.equal(retryCalls, 1, 'Double click must not submit duplicate retries')
  resolveRetry({ data: { cutoff_id: 5, check_version: 2 } })
  await pending
  assert.equal(instance.notice.type, 'info')
  assert.equal(instance.pendingCheck.check_version, 2, 'A stale result cannot finish the newly submitted check')
  assert.equal(messages.length, 1, 'Submission feedback must appear before a result')
  report.checked_version = 2
  await instance.refresh()
  assert.equal(instance.pendingCheck, null)
  assert.equal(instance.notice.type, 'warning')
  assert.match(instance.notice.text, /本次核对后仍未完成.*待人工核对/)
  await instance.refresh()
  assert.equal(messages.length, 2, 'Polling must not repeat the same toast')

  ;({ instance } = setup())
  instance.pendingCheck = { cutoff_id: 5, check_version: 2 }
  report.id = 6; report.checked_version = 10
  await instance.refresh()
  assert(instance.pendingCheck, 'A different operation cannot satisfy this retry')

  ;({ instance } = setup())
  instance.pendingCheck = { cutoff_id: 5, check_version: 2 }
  delete report.checked_version
  await instance.refresh()
  assert(instance.pendingCheck, 'A missing completion marker must not confirm this retry')

  ;({ instance } = setup())
  const staleRetry = instance.retry()
  instance.generation++; instance.id = 48; instance.saving = false
  resolveRetry({ data: { cutoff_id: 5, check_version: 2 } })
  await staleRetry
  assert.equal(instance.notice, null, 'An old response cannot alter a reopened dialog')
  assert.equal(instance.pendingCheck, null)

  ;({ instance } = setup())
  instance.pendingCheck = { cutoff_id: 5, check_version: 2 }
  report.checked_version = 2; report.state = 'COMPLETED'; order.cutoff_state = 'COMPLETED'
  await instance.refresh()
  assert.equal(instance.notice.type, 'success')
  assert.equal(instance.pendingCheck, null)

  ;({ instance } = setup())
  instance.pendingCheck = { cutoff_id: 5, check_version: 2 }
  failRead = true
  await instance.refresh()
  assert(instance.pendingCheck, 'Read failure must preserve the submitted retry')
  assert.match(instance.refreshError, /读取失败.*请勿重复申请/)
  assert.equal(instance.loading, false)

  const navigation = setup()
  navigation.instance.openReview(order.shipments[0])
  assert.equal(navigation.instance.visible, false)
  assert.deepEqual(navigation.events[0], ['open-review', 34])
  console.log('PASS cutoff: submission feedback, duplicate prevention, fresh-result correlation, completion, read failure, scoped review navigation and template compilation')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
