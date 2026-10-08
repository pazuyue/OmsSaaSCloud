<template>
  <el-drawer title="界面设置" size="320px" :visible.sync="visible" append-to-body>
    <div class="workspace-settings">
      <p>按你的使用习惯调整工作空间。</p>
      <div class="setting-row">
        <span>显示多页签<small>快速切换已打开的页面</small></span><el-switch v-model="tagsView" aria-label="显示多页签" />
      </div>
      <div class="setting-row">
        <span>动态浏览器标题<small>在浏览器标签显示当前页面名称</small></span><el-switch v-model="dynamicTitle" aria-label="动态浏览器标题" />
      </div>
      <el-button type="primary" @click="save">保存设置</el-button><el-button @click="reset">恢复默认</el-button>
    </div>
  </el-drawer>
</template>
<script>
export default {
  computed: {
    visible: {
      get() {
        return this.$store.state.settings.showSettings
      },
      set(value) {
        this.change('showSettings', value)
      }
    },
    tagsView: {
      get() {
        return this.$store.state.settings.tagsView
      },
      set(value) {
        this.change('tagsView', value)
      }
    },
    dynamicTitle: {
      get() {
        return this.$store.state.settings.dynamicTitle
      },
      set(value) {
        this.change('dynamicTitle', value)
      }
    }
  },
  methods: {
    change(key, value) {
      this.$store.dispatch('settings/changeSetting', { key, value })
    },
    save() {
      localStorage.setItem(
        'layout-setting',
        JSON.stringify({ layoutVersion: 2, tagsView: this.tagsView, dynamicTitle: this.dynamicTitle })
      )
      this.$message.success('界面设置已保存')
      this.visible = false
    },
    reset() {
      this.tagsView = true
      this.dynamicTitle = true
      this.save()
    }
  }
}
</script>
