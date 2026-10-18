import request from '@/utils/request'
export const intakeOverview = () => request({ url: '/order/intake/overview' })
export const intakeTasks = params => request({ url: '/order/intake/tasks', params })
export const exchangeSyncTask = id => request({ url: `/order/exchanges/tasks/${id}` })
export const retryExchangeSync = id => request({ url: `/order/exchanges/tasks/${id}/retry`, method: 'post' })
