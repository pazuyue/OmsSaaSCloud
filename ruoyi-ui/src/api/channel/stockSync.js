import request from '@/utils/request'
export function stockGet(path = '', params) { return request({ url: '/channel/stockSync' + path, method: 'get', params }) }
export function stockPost(path = '', data) { return request({ url: '/channel/stockSync' + path, method: 'post', data, timeout: 180000, headers: { repeatSubmit: false } }) }
