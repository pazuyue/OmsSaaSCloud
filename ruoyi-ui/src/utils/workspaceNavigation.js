// Build full paths without mutating the permission store's route objects.
export function menuPath(parent, path) {
  if (/^https?:\/\//.test(path) || path.startsWith('/')) return path
  return `${parent}/${path}`.replace(/\/+/g, '/')
}
export function normalizeMenus(routes, parent = '') {
  return routes
    .filter((route) => !route.hidden)
    .map((route) => {
      const path = menuPath(parent, route.path)
      return { ...route, path, children: route.children ? normalizeMenus(route.children, path) : [] }
    })
}
export function menuLeaves(menu) {
  return menu.children && menu.children.length ? menu.children.flatMap(menuLeaves) : [menu]
}
const moduleOrder = [
  '/oms-goods',
  '/oms-supplychain',
  '/oms-inventory',
  '/oms-channel',
  '/system',
  '/monitor',
  '/tool'
]
const moduleNames = {
  '/oms-goods': '商品',
  '/oms-supplychain': '供应链',
  '/oms-inventory': '库存',
  '/oms-channel': '渠道'
}
export function workspaceModules(routes) {
  return [{ path: '/index', meta: { title: '工作台', icon: 'dashboard' }, children: [] }].concat(
    normalizeMenus(routes)
      .filter((route) => route.path !== '/index' && !/^https?:\/\/(www\.)?ruoyi\.vip\/?$/.test(route.path))
      .sort((a, b) => {
        const rank = (path) => (moduleOrder.includes(path) ? moduleOrder.indexOf(path) : 100)
        return rank(a.path) - rank(b.path)
      })
      .map((route) => ({
        ...route,
        meta: { ...route.meta, title: moduleNames[route.path] || (route.meta && route.meta.title) || '应用' }
      }))
  )
}
export function activeModule(modules, path) {
  return (
    modules.find((menu) => menu.path === path || menuLeaves(menu).some((item) => item.path === path)) ||
    modules
      .filter((menu) => path.startsWith(menu.path + '/'))
      .sort((a, b) => b.path.length - a.path.length)[0]
  )
}
export function menuLocation(menu) {
  let query = menu.query || {}
  if (typeof query === 'string') {
    try {
      query = JSON.parse(query)
    } catch (_) {
      query = {}
    }
  }
  return { path: menu.path, query }
}
