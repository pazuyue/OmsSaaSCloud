<template>
  <div class="app-wrapper workspace-shell" :class="classObj" :style="{ '--current-color': theme }">
    <navbar />
    <div
      v-if="device === 'mobile' && sidebar.opened && !sidebar.hide"
      class="workspace-backdrop"
      @click="closeSidebar"
    />
    <sidebar v-if="!sidebar.hide" class="sidebar-container" />
    <main class="main-container" :class="{ sidebarHide: sidebar.hide }">
      <tags-view v-if="needTagsView" /><app-main />
    </main>
    <settings />
  </div>
</template>
<script>
import { AppMain, Navbar, Settings, Sidebar, TagsView } from './components'
import ResizeMixin from './mixin/ResizeHandler'
import { mapState } from 'vuex'
export default {
  name: 'Layout',
  components: { AppMain, Navbar, Settings, Sidebar, TagsView },
  mixins: [ResizeMixin],
  computed: {
    ...mapState({
      theme: (state) => state.settings.theme,
      sidebar: (state) => state.app.sidebar,
      device: (state) => state.app.device,
      needTagsView: (state) => state.settings.tagsView
    }),
    classObj() {
      return {
        hideSidebar: !this.sidebar.opened,
        openSidebar: this.sidebar.opened,
        mobile: this.device === 'mobile',
        withoutAnimation: this.sidebar.withoutAnimation
      }
    }
  },
  methods: {
    closeSidebar() {
      this.$store.dispatch('app/closeSideBar', { withoutAnimation: false })
    }
  }
}
</script>
