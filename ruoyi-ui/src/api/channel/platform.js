import request from '@/utils/request'
export function platformGet(path, params) {
  return request({ url: '/channel/platform' + path, method: 'get', params })
}
export function platformPost(path, data) {
  // Credential forms and OAuth codes must never enter the duplicate-submit session cache.
  return request({ url: '/channel/platform' + path, method: 'post', data, timeout: 60000, headers: { repeatSubmit: false } })
}
