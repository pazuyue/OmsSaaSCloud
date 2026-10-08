<template>
  <header class="workspace-header">
    <router-link
      to="/index"
      class="workspace-brand"
      aria-label="OMS 工作台"
    ><span class="brand-mark"><i class="el-icon-box" /></span><span>OMS<span class="brand-caption">工作空间</span></span></router-link>
    <button
      v-if="!sidebar.hide"
      type="button"
      class="icon-button mobile-sidebar-toggle"
      aria-label="展开功能菜单"
      @click="toggleSideBar"
    >
      <i class="el-icon-s-fold" />
    </button>
    <top-nav />
    <div class="workspace-account">
      <search class="workspace-search" />
      <span v-if="companyCode" class="company-indicator"><span class="status-dot" />{{ companyCode }}</span>
      <el-dropdown trigger="click" @command="accountAction">
        <button type="button" class="account-button" aria-label="账户菜单">
          <span class="account-avatar">{{ initial }}</span><span class="account-name">{{ name }}</span><i class="el-icon-arrow-down" />
        </button>
        <el-dropdown-menu
          slot="dropdown"
        ><el-dropdown-item command="profile" icon="el-icon-user">个人中心</el-dropdown-item><el-dropdown-item command="settings" icon="el-icon-setting">界面设置</el-dropdown-item><el-dropdown-item
          command="logout"
          icon="el-icon-switch-button"
          divided
        >退出登录</el-dropdown-item></el-dropdown-menu>
      </el-dropdown>
    </div>
  </header>
</template>
<script>
import { mapGetters } from 'vuex'
import TopNav from '@/components/TopNav'
import Search from '@/components/HeaderSearch'
export default {
  components: { TopNav, Search },
  computed: {
    ...mapGetters(['sidebar', 'name']),
    companyCode() {
      return this.$store.state.user.companyCode
    },
    initial() {
      return (this.name || 'U').slice(0, 1).toUpperCase()
    }
  },
  methods: {
    toggleSideBar() {
      this.$store.dispatch('app/toggleSideBar')
    },
    accountAction(command) {
      if (command === 'profile') this.$router.push('/user/profile')
      if (command === 'settings') { this.$store.dispatch('settings/changeSetting', { key: 'showSettings', value: true }) }
      if (command === 'logout') {
        this.$confirm('确定退出当前账号吗？', '退出登录', {
          confirmButtonText: '退出登录',
          cancelButtonText: '取消',
          type: 'warning'
        })
          .then(() => this.$store.dispatch('LogOut'))
          .then(() => {
            location.href = '/login'
          })
          .catch(() => {})
      }
    }
  }
}
</script>
