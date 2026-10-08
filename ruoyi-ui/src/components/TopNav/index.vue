<template>
  <nav class="workspace-nav" aria-label="主导航">
    <div class="nav-measure" aria-hidden="true">
      <span v-for="item in modules" :key="item.path" ref="labels">{{ item.meta.title }}</span>
    </div>
    <button
      v-for="item in visibleMenus"
      :key="item.path"
      type="button"
      class="module-tab"
      :class="{ 'is-active': current && current.path === item.path }"
      :aria-current="current && current.path === item.path ? 'page' : null"
      @click="select(item)"
    >
      {{ item.meta.title }}
    </button>
    <el-dropdown v-if="overflowMenus.length" trigger="click" @command="select">
      <button
        type="button"
        class="module-tab more-modules"
        :class="{ 'is-active': overflowActive }"
        aria-label="更多业务模块"
      >
        {{ overflowActive ? current.meta.title : '更多' }} <i class="el-icon-arrow-down" />
      </button>
      <el-dropdown-menu
        slot="dropdown"
        class="module-dropdown"
      ><el-dropdown-item
        v-for="item in overflowMenus"
        :key="item.path"
        :command="item"
        :class="{ 'is-current': current && current.path === item.path }"
      >{{ item.meta.title }}</el-dropdown-item></el-dropdown-menu>
    </el-dropdown>
  </nav>
</template>
<script>
import { workspaceModules, activeModule, menuLeaves, menuLocation } from '@/utils/workspaceNavigation'
export default {
  data: () => ({ visibleNumber: 6, lastVisited: {}, observer: null, historyReady: false }),
  computed: {
    modules() {
      return workspaceModules(this.$store.state.permission.topbarRouters)
    },
    current() {
      return activeModule(this.modules, this.$route.meta.activeMenu || this.$route.path)
    },
    visibleMenus() {
      return this.modules.slice(0, this.visibleNumber)
    },
    overflowMenus() {
      return this.modules.slice(this.visibleNumber)
    },
    overflowActive() {
      return this.current && this.overflowMenus.some((item) => item.path === this.current.path)
    },
    storageKey() {
      return 'oms-module-history:' + this.$store.state.user.id
    }
  },
  watch: {
    '$route.fullPath': {
      immediate: true,
      handler() {
        this.syncNavigation()
      }
    },
    modules() {
      this.syncNavigation()
      this.$nextTick(this.measure)
    }
  },
  mounted() {
    try {
      this.lastVisited = JSON.parse(sessionStorage.getItem(this.storageKey)) || {}
    } catch (_) {
      this.lastVisited = {}
    }
    this.historyReady = true
    this.syncNavigation()
    this.observer = new ResizeObserver(this.measure)
    this.observer.observe(this.$el)
    this.measure()
  },
  beforeDestroy() {
    if (this.observer) this.observer.disconnect()
  },
  methods: {
    measure() {
      const widths = (this.$refs.labels || []).map((label) => label.getBoundingClientRect().width + 6)
      const available = this.$el.clientWidth
      if (widths.reduce((sum, width) => sum + width, 0) <= available) {
        this.visibleNumber = widths.length
        return
      }
      let used = 100
      let count = 0
      for (const width of widths) {
        if (used + width > available) break
        used += width
        count++
      }
      this.visibleNumber = count
    },
    syncNavigation() {
      const children = this.current ? this.current.children : []
      this.$store.commit('SET_SIDEBAR_ROUTERS', children)
      this.$store.dispatch('app/toggleSideBarHide', !children.length)
      if (this.current && menuLeaves(this.current).some((item) => item.path === this.$route.path)) {
        this.lastVisited[this.current.path] = this.$route.fullPath
        try {
          if (this.historyReady) sessionStorage.setItem(this.storageKey, JSON.stringify(this.lastVisited))
        } catch (_) {
          /* Storage may be unavailable. */
        }
      }
    },
    select(menu) {
      const leaves = menuLeaves(menu)
      const previous = this.lastVisited[menu.path]
      const target = leaves.find((item) => previous && item.path === previous.split('?')[0]) || leaves[0]
      if (!target) return
      if (/^https?:\/\//.test(target.path)) window.open(target.path, '_blank', 'noopener,noreferrer')
      else {
        this.$router.push(
          previous && target.path === previous.split('?')[0] ? previous : menuLocation(target)
        )
      }
    }
  }
}
</script>
