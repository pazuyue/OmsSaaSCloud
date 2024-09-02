<template>
<el-row>
  <el-card class="app-container" style="margin: 5px">
    <!-- 用户导入对话框 -->
    <el-dialog :title="upload.title" :visible.sync="upload.open" width="400px" append-to-body @close="handleClose">
      <el-upload
        ref="upload"
        :limit="1"
        accept=".xlsx, .xls"
        :headers="upload.headers"
        :action="upload.url"
        :disabled="upload.isUploading"
        :on-progress="handleFileUploadProgress"
        :on-success="handleFileSuccess"
        :auto-upload="false"
        drag
      >
        <i class="el-icon-upload"></i>
        <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
        <div class="el-upload__tip text-center" slot="tip">
          <span>仅允许导入xls、xlsx格式文件。</span>
          <el-link type="primary" :underline="false" style="font-size:12px;vertical-align: baseline;" @click="importTemplate">下载模板</el-link>
        </div>
      </el-upload>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitFileForm">确 定</el-button>
        <el-button @click="upload.open = false">取 消</el-button>
      </div>
    </el-dialog>
  </el-card>
</el-row>
</template>

<script>
import {getToken} from "@/utils/auth";

export default {
  name: "noTicketsUpload",
  props: {
    title: {
      type: String,
      default: ''
    },
    open: {
      type: Boolean,
      default: false
    },
  },
  watch: {
    open(newValue) {
      this.upload.open = newValue;
    }
  },
  data: function() {
    return {
      loading:false,
      upload: {
        // 是否显示弹出层（用户导入）
        open: this.open,
        // 是否显示弹出层（用户导入）
        exportOpen: false,
        // 弹出层标题（用户导入）
        title: this.title,
        // 是否禁用上传
        isUploading: false,
        // 设置上传的请求头部
        headers: { Authorization: "Bearer " + getToken() },
        // 上传的地址
        url: process.env.VUE_APP_BASE_API + "/supplychain/noTickets/import",
        // 导入产品信息表格数据
        exportInfoList: [],
        exportTotal : 0,
        // 查询参数
        queryExportParams: {
          pageNum: 1,
          pageSize: 10,
          import_batch:'',
        },
      },
    }
  },
  created() {
  },
  methods: {
    // 文件上传中处理
    handleFileUploadProgress(event, file, fileList) {
      this.upload.isUploading = true;
    },

    // 文件上传成功处理
    handleFileSuccess(response, file, fileList) {
      this.upload.open = false;
      this.upload.isUploading = false;
      this.$refs.upload.clearFiles();
      if (response.code==200){
        this.$alert("<div style='overflow: auto;overflow-x: hidden;max-height: 70vh;padding: 10px 20px 0;'>导入成功</div>", "导入结果", { dangerouslyUseHTMLString: true });
        this.showImportList(response.msg)
      }else {
        this.$alert("<div style='overflow: auto;overflow-x: hidden;max-height: 70vh;padding: 10px 20px 0;'>导入失败</div>", "导入结果", { dangerouslyUseHTMLString: true });
      }
    },
    importTemplate() {
      this.download('supplychain/noTickets/importTemplate', {
      }, `noTickets_template_${new Date().getTime()}.xlsx`)
    },
    handleClose() {
      this.$emit('update:open', false); // 通知父组件关闭
    },
    // 提交上传文件
    submitFileForm() {
      this.$refs.upload.submit();
    },
  }
}
</script>

<style scoped>

</style>
