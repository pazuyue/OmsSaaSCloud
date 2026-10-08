import store from '@/store'
import Cookies from 'js-cookie'

const WIDTH = 992 // refer to Bootstrap's responsive design

export default {
  data() { return { desktopSidebarOpened: Cookies.get('sidebarStatus') !== '0' } },
  watch: {
    $route(route) {
      if (this.device === 'mobile' && this.sidebar.opened) {
        store.dispatch('app/closeSideBar', { withoutAnimation: false })
      }
    }
  },
  beforeMount() {
    window.addEventListener('resize', this.$_resizeHandler)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.$_resizeHandler)
  },
  mounted() {
    const isMobile = this.$_isMobile()
    if (isMobile) {
      store.dispatch('app/toggleDevice', 'mobile')
      store.dispatch('app/closeSideBar', { withoutAnimation: true })
    }
  },
  methods: {
    // use $_ for mixins properties
    // https://vuejs.org/v2/style-guide/index.html#Private-property-names-essential
    $_isMobile() {
      return window.innerWidth < WIDTH
    },
    $_resizeHandler() {
      if (!document.hidden) {
        const isMobile = this.$_isMobile()
        const wasMobile = this.device === 'mobile'
        if (isMobile && !wasMobile) this.desktopSidebarOpened = this.sidebar.opened
        store.dispatch('app/toggleDevice', isMobile ? 'mobile' : 'desktop')
        if (!isMobile && wasMobile && this.desktopSidebarOpened && !this.sidebar.opened) {
          store.commit('app/OPEN_SIDEBAR')
        }

        if (isMobile) {
          store.dispatch('app/closeSideBar', { withoutAnimation: true })
        }
      }
    }
  }
}
