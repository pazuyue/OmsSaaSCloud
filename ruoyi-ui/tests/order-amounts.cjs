const assert = require('assert/strict'), fs = require('fs'), path = require('path')
const babel = require('@babel/core'), compiler = require('vue-template-compiler')
const root = path.join(__dirname, '../src/views/oms/order')
function readModule(source, imports = {}) {
  const code = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
  const module = { exports: {} }; new Function('require', 'module', 'exports', code)(name => imports[name] || {}, module, module.exports); return module.exports
}
const h = readModule(fs.readFileSync(path.join(root, 'orderAmounts.js'), 'utf8'))
assert.equal(h.money(null), '未提供'); assert.equal(h.money(undefined), '未提供'); assert.equal(h.money(''), '未提供')
assert.equal(h.money(0), '¥0.00'); assert.equal(h.money('-2.10'), '¥-2.10'); assert.equal(h.money('n/a'), '待核对')
assert.equal(h.money('180143985094819.82'), '¥180143985094819.82', 'large amounts must never lose cents')
const sales = readModule(fs.readFileSync(path.join(root, 'orderSalesPresentation.js'), 'utf8'), { './orderAmounts': h })
const oid = '987654321012345679'
const order = { document: { lines: [{ platform_oid: oid, sales_amount: '100.00', goods_payment: '85.00', discount_total: '15.00' }] }, bundles: [{ id: 1, platform_oid: oid, sku_sn: 'FD', quantity: 2, payment: 85, business_type: 'LUCKY_BAG' }], lines: [{ platform_oid: oid, bundle_id: 1, sku_sn: 'A', quantity: 2, payment: 0 }, { platform_oid: oid, bundle_id: 1, sku_sn: 'B', quantity: 2, payment: 0 }, { platform_oid: '', sku_sn: 'G', quantity: 1, payment: 0, gift_plan_id: 8 }] }
const rows = sales.salesRows(order); assert.equal(rows.length, 1); assert.equal(rows[0].money.goods_payment, '85.00')
assert.equal(rows[0].money.sales_amount, '100.00'); assert.equal(rows[0].money.discount_total, '15.00')
for (const file of ['OrderAmountSummary.vue', 'OrderSaleAmounts.vue', 'OrderDocumentInfo.vue', 'OrderSalesProducts.vue', 'index.vue']) {
  const c = compiler.parseComponent(fs.readFileSync(path.join(root, file), 'utf8')); assert.deepEqual(compiler.compile(c.template.content).errors, [], file)
  readModule(c.script.content, { './orderAmounts': h, './orderSalesPresentation': sales })
}
console.log('PASS missing vs zero, negative adjustments, exact cents, one commercial amount per sale, gifts excluded and five Vue templates')
