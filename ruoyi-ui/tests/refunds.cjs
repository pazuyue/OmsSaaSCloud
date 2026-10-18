const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
for (const file of ['PlatformRefunds.vue', 'BusinessRefunds.vue', 'RefundSettings.vue', 'RefundList.vue', 'RefundDetail.vue', 'RefundGoods.vue', 'OrderRefundRecords.vue', 'index.vue']) {
  const c = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8'))
  assert.deepEqual(compiler.compile(c.template.content).errors, [], file)
}
// Check the rendered workflow makes the actual authority and separate progress visible.
const detail = fs.readFileSync(path.join(root, 'RefundDetail.vue'), 'utf8')
for (const label of ['平台资金', '货物处理', '实际资金结果来自平台同步', '原子订单的实际商品', '实际发货来源', '平台已退款']) assert.ok(detail.includes(label), label)
assert.ok(detail.includes('control_version') && detail.includes('platform_version'))
const list = fs.readFileSync(path.join(root, 'RefundList.vue'), 'utf8')
assert.ok(list.includes('openSync') && list.includes("kind:'REFUND'") && list.includes('/oms-order/management'))
assert.ok(list.includes('s.row.refund_id') && list.includes('s.row.platform_tid') && list.includes('s.row.platform_oid'))
console.log('PASS refund Vue templates and authority/progress presentation')
