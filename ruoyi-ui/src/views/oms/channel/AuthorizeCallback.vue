<template>
  <div class="callback-page"><el-card><h2>天猫店铺授权</h2><p v-loading="busy">{{ message }}</p><el-button v-if="!busy" type="primary" @click="$router.replace('/oms-channel/channel')">返回店铺管理</el-button></el-card></div>
</template>
<script>
import { platformPost } from '@/api/channel/platform'
import { getToken } from '@/utils/auth'
export default {
  data: () => ({ busy: true, message: '正在核对授权店铺，请勿重复提交…' }),
  async created() {
    const { code, state, error } = this.$route.query
    await this.$router.replace({ path: '/channel-authorize', query: {} }).catch(() => {})
    if (!getToken()) { this.busy = false; this.message = 'OMS 登录已过期，请登录后从店铺管理重新发起授权。'; return }
    if (error || !code || !state) { this.busy = false; this.message = '授权未完成，请返回店铺管理重新发起。'; return }
    try {
      await platformPost('/oauth/complete', { code, state })
      this.message = '店铺身份核验成功，授权已保存。请返回店铺管理核验服务订购状态。'
    } catch (e) { this.message = '授权未保存。请检查错误提示，使用原 OMS 账号和公司重新发起授权。' }
    finally { this.busy = false }
  }
}
</script>
<style scoped>.callback-page{max-width:680px;margin:100px auto;padding:24px}.callback-page p{padding:24px 0;line-height:1.8}</style>
