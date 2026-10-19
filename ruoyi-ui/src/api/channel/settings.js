import request from '@/utils/request'
export function settingsGet(path, params) { return request({ url:'/channel/channelSettings'+path, method:'get', params }) }
export function settingsPost(path, data) { return request({ url:'/channel/channelSettings'+path, method:'post', data, headers:{repeatSubmit:false} }) }
