import request from '@/utils/request'
const root = '/inventory/allocation'
export const listRules = params => request({ url: root, params })
export const ruleDetail = id => request({ url: `${root}/${id}` })
export const ruleOptions = (type, keyword = '') => request({ url: `${root}/options/${type}`, params: { keyword }})
export const saveRule = data => request({ url: data.id ? `${root}/${data.id}` : root, method: data.id ? 'put' : 'post', data })
export const submitRule = (id, revision, withdraw = false) => request({ url: `${root}/${id}/${withdraw ? 'withdraw' : 'submit'}`, method: 'post', params: { revision }})
export const deleteRule = (id, revision) => request({ url: `${root}/${id}`, method: 'delete', params: { revision }})
export const previewRule = (id, params) => request({ url: `${root}/${id}/preview`, params })
export const startRule = (id, revision, action) => request({ url: `${root}/${id}/start`, method: 'post', params: { revision, action }, timeout: 30000, headers: { repeatSubmit: false }})
export const stepRule = id => request({ url: `${root}/${id}/step`, method: 'post', timeout: 30000, headers: { repeatSubmit: false }})
export const ruleResults = (id, params) => request({ url: `${root}/${id}/results`, params })
export const ruleGoods = (id, params) => request({ url: `${root}/${id}/goods`, params })
export const dailyCommand = (id, revision, action) => request({ url: `${root}/${id}/daily/${action}`, method: 'post', params: { revision }})
export const dailyRuns = (id, params) => request({ url: `${root}/${id}/daily-runs`, params })
export const dailyRun = (id, runId) => request({ url: `${root}/${id}/daily-runs/${runId}` })
export const dailyItems = (id, runId, params) => request({ url: `${root}/${id}/daily-runs/${runId}/items`, params })
export const importRuleGoods = (id, revision, file) => {
  const data = new FormData()
  data.append('file', file)
  return request({ url: `${root}/${id}/goods`, method: 'post', params: { revision }, data, timeout: 60000 })
}
