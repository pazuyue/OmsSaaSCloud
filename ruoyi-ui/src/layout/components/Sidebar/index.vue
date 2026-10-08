<template>
  <aside class="workspace-sidebar" aria-label="模块功能菜单">
    <div class="sidebar-heading">
      <span v-if="!isCollapse">{{ moduleTitle }}<small>功能导航</small></span><i v-else class="el-icon-menu" />
    </div>
    <el-scrollbar wrap-class="scrollbar-wrapper">
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        background-color="transparent"
        text-color="#515763"
        active-text-color="#0066cc"
        :unique-opened="true"
        :collapse-transition="false"
        mode="vertical"
      ><sidebar-item
        v-for="route in sidebarRouters"
        :key="route.path"
        :item="route"
        :base-path="route.path"
      /></el-menu>
    </el-scrollbar>
    <button
      type="button"
      class="sidebar-collapse"
      :aria-label="isCollapse ? '展开侧栏' : '收起侧栏'"
      :aria-expanded="!isCollapse"
      @click="$store.dispatch('app/toggleSideBar')"
    >
      <i :class="isCollapse ? 'el-icon-s-unfold' : 'el-icon-s-fold'" /><span
        v-if="!isCollapse"
      >收起侧栏</span>
    </button>
  </aside>
</template>
<script>
import { mapGetters } from 'vuex'
import SidebarItem from './SidebarItem'
import { workspaceModules, activeModule } from '@/utils/workspaceNavigation'
export default {
  components: { SidebarItem },
  computed: {
    ...mapGetters(['sidebarRouters', 'sidebar']),
    activeMenu() {
      return this.$route.meta.activeMenu || this.$route.path
    },
    isCollapse() {
      return !this.sidebar.opened
    },
    moduleTitle() {
      const menu = activeModule(workspaceModules(this.$store.state.permission.topbarRouters), this.activeMenu)
      return menu ? menu.meta.title : '工作空间'
    }
  }
}
</script>
