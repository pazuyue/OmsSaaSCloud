import ConfigurationPanel from './ConfigurationPanel'
export default {
  components: { ConfigurationPanel },
  props: { embedded: Boolean, readOnly: Boolean },
  data: () => ({ editorBaseline: null }),
  computed: {
    editorState() { return this.form || this.policy || this.config || this.rule || this.carrier || null },
    editorReady() { return !!this.editorState && !this.loading },
    dirty() { return this.editorBaseline !== null && JSON.stringify(this.editorState) !== this.editorBaseline },
    busy() { return !!(this.loading || this.saving) }
  },
  watch: {
    saving: { immediate: true, handler(value) { this.$emit('saving', !!value) } },
    loading(value) { if (value) this.editorBaseline = null; else this.acceptBaseline() },
    visible(value) { if (value) this.$nextTick(this.acceptBaseline) },
    dirty: { immediate: true, handler(value) { this.$emit('dirty', value) } },
    busy: { immediate: true, handler(value) { this.$emit('busy', value) } }
  },
  methods: { acceptBaseline() { if (this.editorReady) this.editorBaseline = JSON.stringify(this.editorState) } }
}
