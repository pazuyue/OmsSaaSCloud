<template>
  <div class="workspace-home">
    <div class="home-intro">
      <div>
        <div class="page-eyebrow">你的业务，从这里开始</div>
        <h1>{{ greeting }}，{{ name }}</h1>
        <p>在一个工作空间，有序处理每一天的业务。</p>
      </div>
      <span class="home-date"><i class="el-icon-date" /> {{ today }}</span>
    </div>
    <div class="workspace-summary">
      <div class="summary-copy">
        <span class="summary-label">OMS WORKSPACE</span>
        <h2>连接业务的每一个环节。</h2>
        <p>商品、供应链、库存与渠道。<br>从常用入口出发，让协作更简单。</p>
        <button v-if="firstBusiness" class="summary-action" type="button" @click="open(firstBusiness)">
          进入{{ firstBusiness.meta.title }} <i class="el-icon-arrow-right" />
        </button>
      </div>
      <div class="summary-facts">
        <div>
          <span class="fact-icon"><i class="el-icon-office-building" /></span><span
            class="fact-label"
          >当前企业<strong>{{ companyCode || '未设置' }}</strong></span>
        </div>
        <div>
          <span class="fact-icon"><i class="el-icon-menu" /></span><span
            class="fact-label"
          >可用模块<strong>{{ modules.length }} <small>个业务模块</small></strong></span>
        </div>
        <div>
          <span class="fact-icon"><i class="el-icon-user" /></span><span
            class="fact-label"
          >当前账号<strong>{{ name }}</strong></span>
        </div>
      </div>
    </div>
    <div class="home-section-title">
      <div>
        <h2>业务空间</h2>
        <p>选择模块，开始处理工作。</p>
      </div>
      <span>{{ modules.length }} 个可用模块</span>
    </div>
    <div class="module-grid">
      <button
        v-for="(item, index) in modules"
        :key="item.path"
        type="button"
        class="module-card"
        @click="open(item)"
      >
        <span class="module-card-icon" :class="'tone-' + (index % 4)"><i :class="detail(item).icon" /></span><span
          class="module-card-copy"
        ><strong>{{ item.meta.title }}</strong><span>{{ detail(item).description }}</span></span><i class="el-icon-arrow-right module-arrow" />
      </button>
    </div>
    <div class="home-bottom-grid">
      <section class="home-panel">
        <div class="home-panel-title">
          <h2>快捷入口</h2>
          <span>常用功能，一步直达</span>
        </div>
        <div class="quick-grid">
          <button v-for="item in shortcuts" :key="item.path" type="button" @click="open(item)">
            <i class="el-icon-document" /><span>{{ item.meta.title }}</span><i class="el-icon-top-right" />
          </button>
        </div>
        <p v-if="!shortcuts.length" class="home-empty">当前账号暂无可用功能。</p>
      </section>
      <section class="home-panel">
        <div class="home-panel-title">
          <h2>最近访问</h2>
          <span>本次会话</span>
        </div>
        <div v-if="recent.length" class="recent-list">
          <router-link
            v-for="item in recent"
            :key="item.path"
            :to="item.fullPath"
          ><span class="recent-dot" /><span>{{ item.title }}</span><i
            class="el-icon-arrow-right"
          /></router-link>
        </div>
        <div v-else class="home-empty">
          <i class="el-icon-time" />
          <p>从一个业务模块开始</p>
          <span>访问过的页面会显示在这里</span>
        </div>
      </section>
    </div>
    <footer class="workspace-footer">OMS · 让业务清晰有序</footer>
  </div>
</template>
<script>
import { workspaceModules, menuLeaves, menuLocation } from '@/utils/workspaceNavigation'
const details = {
  '/oms-goods': { icon: 'el-icon-box', description: '商品资料 · 分类 · 规格' },
  '/oms-supplychain': { icon: 'el-icon-connection', description: '采购 · 仓库 · 合作伙伴' },
  '/oms-inventory': { icon: 'el-icon-data-analysis', description: '库存明细 · 分配规则' },
  '/oms-channel': { icon: 'el-icon-set-up', description: '渠道资料 · 业务配置' },
  '/system': { icon: 'el-icon-setting', description: '组织 · 权限 · 基础设置' },
  '/monitor': { icon: 'el-icon-monitor', description: '运行状态 · 任务记录' },
  '/tool': { icon: 'el-icon-folder-opened', description: '开发工具 · 代码生成' }
}
export default {
  name: 'Index',
  data: () => ({ now: new Date(), clock: null }),
  computed: {
    name() {
      return this.$store.state.user.name
    },
    companyCode() {
      return this.$store.state.user.companyCode
    },
    greeting() {
      const hour = this.now.getHours()
      return hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'
    },
    today() {
      return this.now.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
    },
    modules() {
      return workspaceModules(this.$store.state.permission.topbarRouters).filter(
        (item) => item.path !== '/index'
      )
    },
    firstBusiness() {
      return this.modules[0]
    },
    shortcuts() {
      return this.modules.flatMap((item) => menuLeaves(item).slice(0, 2)).slice(0, 8)
    },
    recent() {
      const allowed = this.modules.flatMap(menuLeaves).map((item) => item.path)
      return this.$store.state.tagsView.visitedViews
        .filter((item) => allowed.includes(item.path))
        .slice(-5)
        .reverse()
    }
  },
  mounted() {
    this.clock = setInterval(() => {
      this.now = new Date()
    }, 60000)
  },
  beforeDestroy() {
    clearInterval(this.clock)
  },
  methods: {
    detail(item) {
      return details[item.path] || { icon: 'el-icon-link', description: '打开应用与相关功能' }
    },
    open(item) {
      const target = menuLeaves(item)[0]
      if (!target) return
      if (/^https?:\/\//.test(target.path)) window.open(target.path, '_blank', 'noopener,noreferrer')
      else this.$router.push(menuLocation(target))
    }
  }
}
</script>
<style lang="scss" scoped>
.workspace-home {
  max-width: 1320px;
  margin: 0 auto;
}
.home-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 12px 0 28px;
}
.home-intro h1 {
  margin: 0;
  font-size: 30px;
  font-weight: 600;
  letter-spacing: -1px;
}
.home-intro p {
  color: #717b88;
  margin: 10px 0 0;
  font-size: 13px;
}
.home-date {
  color: #697382;
  font-size: 12px;
  white-space: nowrap;
}
.home-date i {
  margin-right: 7px;
}
.workspace-summary {
  display: grid;
  grid-template-columns: 1fr 320px;
  border: 1px solid #e3e8ee;
  border-radius: 16px;
  background: #fff;
  overflow: hidden;
}
.summary-copy {
  padding: 36px 40px;
  background: linear-gradient(115deg, #fafdff, #f1f6fc);
}
.summary-label {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #6b86a7;
}
.summary-copy h2 {
  font-size: 27px;
  font-weight: 500;
  letter-spacing: -0.6px;
  margin: 18px 0 14px;
}
.summary-copy p {
  font-size: 13px;
  line-height: 1.9;
  color: #6b7787;
  margin: 0;
}
.summary-action {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 24px;
  background: #fff;
  border: 1px solid #d9e4f2;
  color: #0066cc;
  padding: 10px 16px;
  border-radius: 8px;
  font-size: 12px;
}
.summary-action:hover {
  background: #eaf3ff;
}
.summary-facts {
  padding: 24px 30px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 26px;
}
.summary-facts > div {
  display: flex;
  align-items: center;
  gap: 14px;
}
.fact-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #f4f6f9;
  color: #7b8da6;
  font-size: 19px;
}
.fact-label {
  font-size: 11px;
  color: #78828f;
}
.fact-label strong {
  display: block;
  color: #394454;
  font-size: 17px;
  font-weight: 500;
  margin-top: 6px;
}
.fact-label small {
  font-size: 11px;
  font-weight: 400;
  color: #78828f;
}
.home-section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 30px 0 16px;
}
.home-section-title h2,
.home-panel h2 {
  font-size: 16px;
  font-weight: 600;
  margin: 0;
}
.home-section-title p {
  font-size: 12px;
  color: #78828f;
  margin: 7px 0 0;
}
.home-section-title > span {
  font-size: 11px;
  color: #78828f;
}
.module-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.module-card {
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: left;
  background: #fff;
  border: 1px solid #e5e9ef;
  padding: 21px 18px;
  border-radius: 12px;
  transition: border-color 0.18s, box-shadow 0.18s;
}
.module-card:hover {
  border-color: #b6cce7;
  box-shadow: 0 5px 18px #233e5e08;
}
.module-card-icon {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 21px;
  border-radius: 11px;
  background: #edf4ff;
  color: #5b86bd;
}
.tone-1 {
  background: #eaf5f2;
  color: #558f7d;
}
.tone-2 {
  background: #f1eefb;
  color: #8573af;
}
.tone-3 {
  background: #fbf2e9;
  color: #b08a5d;
}
.module-card-copy {
  min-width: 0;
  flex: 1;
}
.module-card-copy strong {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #333e4c;
}
.module-card-copy > span {
  display: block;
  margin-top: 8px;
  font-size: 10px;
  color: #7c8693;
  line-height: 1.6;
}
.module-arrow {
  font-size: 11px;
  color: #a2acb9;
}
.home-bottom-grid {
  display: grid;
  grid-template-columns: 1.5fr 1fr;
  gap: 20px;
  margin-top: 24px;
}
.home-panel {
  background: white;
  border: 1px solid #e5e9ef;
  border-radius: 12px;
  padding: 24px;
}
.home-panel-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 22px;
  gap: 12px;
}
.home-panel-title > span {
  font-size: 11px;
  color: #85909c;
}
.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 16px;
}
.quick-grid button {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  background: #fafbfc;
  border: 0;
  border-radius: 8px;
  font-size: 12px;
  text-align: left;
  color: #566273;
}
.quick-grid button:hover {
  background: #edf4fc;
  color: #0066cc;
}
.quick-grid span {
  flex: 1;
}
.quick-grid i {
  color: #8b9bb0;
}
.recent-list a {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  font-size: 12px;
  color: #566273;
  border-bottom: 1px solid #f0f2f5;
}
.recent-list a:hover {
  color: #0066cc;
}
.recent-list a > i {
  margin-left: auto;
  color: #a5afbc;
  font-size: 10px;
}
.recent-dot {
  background: #c2d1e5;
  width: 5px;
  height: 5px;
  border-radius: 50%;
}
.home-empty {
  padding: 26px 0;
  text-align: center;
  color: #84909e;
  font-size: 12px;
}
.home-empty > i {
  font-size: 26px;
  color: #b2bece;
}
.home-empty p {
  margin: 12px 0 8px;
}
.home-empty > span {
  font-size: 11px;
  color: #9aa3ae;
}
.workspace-footer {
  margin-top: 30px;
  text-align: center;
  color: #909ba8;
  font-size: 11px;
  letter-spacing: 1px;
}
@media (max-width: 1200px) {
  .module-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}
@media (max-width: 767px) {
  .home-intro {
    margin-top: 4px;
    align-items: flex-start;
  }
  .home-intro h1 {
    font-size: 25px;
  }
  .home-date {
    display: none;
  }
  .workspace-summary {
    grid-template-columns: 1fr;
  }
  .summary-copy {
    padding: 26px;
  }
  .summary-copy h2 {
    font-size: 23px;
  }
  .summary-facts {
    flex-direction: row;
    padding: 20px;
    gap: 18px;
    justify-content: space-between;
  }
  .fact-icon {
    display: none;
  }
  .fact-label strong {
    font-size: 15px;
  }
  .fact-label small {
    font-size: 10px;
  }
  .module-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
  .module-card {
    padding: 16px 12px;
    gap: 9px;
  }
  .module-card-icon {
    width: 32px;
    height: 32px;
    font-size: 18px;
  }
  .module-card-copy > span {
    font-size: 10px;
  }
  .module-arrow {
    display: none;
  }
  .home-bottom-grid {
    grid-template-columns: 1fr;
  }
  .home-panel {
    padding: 20px;
  }
}
</style>
