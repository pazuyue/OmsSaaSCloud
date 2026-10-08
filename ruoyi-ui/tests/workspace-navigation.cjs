const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const babel = require('@babel/core')
const source = fs.readFileSync(path.join(__dirname, '../src/utils/workspaceNavigation.js'), 'utf8')
const compiled = babel.transformSync(source, { configFile: false, babelrc: false, plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')] }).code
const context = { exports: {} }
vm.runInNewContext(compiled, context)
const { workspaceModules, activeModule, menuLeaves, menuLocation } = context.exports
const permitted = [
  { path: '/system', meta: { title: '系统管理' }, children: [{ path: 'user', meta: { title: '用户管理' } }, { path: 'hidden', hidden: true }] },
  { path: '/oms-goods', meta: { title: '商品管理' }, children: [{ path: 'info', meta: { title: '商品资料' }, query: '{"pageNum":2}' }, { path: 'spec', children: [{ path: '/oms-goods/size', meta: { title: '尺码' } }] }] },
  { path: '/forbidden', hidden: true },
  { path: 'https://example.com', meta: { title: '合作平台' } }
]
const before = JSON.stringify(permitted)
const menus = workspaceModules(permitted)
assert.equal(JSON.stringify(permitted), before, 'Menu normalization must not mutate permission routes')
assert.equal(menus[0].path, '/index')
assert.equal(menus[1].path, '/oms-goods')
assert(!menus.some(item => item.path === '/forbidden'))
assert(!menuLeaves(menus.find(item => item.path === '/system')).some(item => item.path.includes('hidden')))
assert.equal(menuLeaves(menus[1])[1].path, '/oms-goods/size', 'Absolute nested paths must stay absolute')
assert.equal(activeModule(menus, '/oms-goods/size').path, '/oms-goods')
assert.equal(activeModule(menus, '/system/user-auth/role/1').path, '/system')
assert.equal(activeModule(menus, '/system-other/user'), undefined, 'Prefix matching must respect path boundaries')
assert.equal(menuLocation(menuLeaves(menus[1])[0]).query.pageNum, 2)
assert.equal(menuLeaves(menus[3])[0].path, 'https://example.com', 'External links must be preserved')
assert.equal(workspaceModules([]).length, 1, 'Accounts with no menu permissions only get the workspace')
console.log('PASS navigation: permissions, immutable paths, nested routes, queries, active modules and external links')
