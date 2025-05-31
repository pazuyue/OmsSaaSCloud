<template>
  <el-dialog :title="title" :visible.sync="visible" width="80%" append-to-body @close="handleClose">
    <!-- 搜索区域 -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" class="search-form">
      <el-form-item label="SKU编号" prop="skuSn">
        <el-input
          v-model="queryParams.skuSn"
          placeholder="请输入SKU编号"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="top-operation-buttons">
      <el-button v-if="showImportButton" type="primary" size="small" icon="el-icon-upload2" @click="handleImport">导入商品</el-button>
      <el-button type="primary" size="small" icon="el-icon-refresh" @click="refresh">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="list" border>
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="规则ID" align="center" prop="ruleId" />
      <el-table-column label="SKU编号" align="center" prop="skuSn" />
      <el-table-column label="公司代码" align="center" prop="companyCode" />
      <el-table-column label="创建时间" align="center" prop="createTime" width="180">
        <template slot-scope="scope">
          <span v-if="scope.row.createTime">{{ parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
          <span v-else>---</span>
        </template>
      </el-table-column>
      <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
        <template slot-scope="scope">
          <span v-if="scope.row.modifyTime">{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
          <span v-else>---</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页组件 -->
    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="loadGoodsList"
    />

    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">关 闭</el-button>
    </div>

    <!-- 导入商品对话框 -->
    <el-dialog
      title="导入商品"
      :visible.sync="uploadDialog.open"
      width="400px"
      append-to-body
      @close="closeUploadDialog"
    >
      <el-upload
        ref="upload"
        :limit="1"
        accept=".xlsx, .xls"
        :headers="uploadDialog.headers"
        :action="uploadDialog.url"
        :data="uploadDialog.data"
        :disabled="uploadDialog.isUploading"
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
        <el-button @click="closeUploadDialog">取 消</el-button>
      </div>
    </el-dialog>
  </el-dialog>
</template>

<script>
import { getRuleStockGoodsInfoList } from "@/api/ruleStock/info";
import { parseTime } from "@/utils/ruoyi";
import { getToken } from "@/utils/auth";
import Pagination from "@/components/Pagination";

export default {
  name: "GoodsListDialog",
  components: { Pagination },
  props: {
    // 弹窗显示状态
    open: {
      type: Boolean,
      default: false
    },
    // 弹窗标题
    title: {
      type: String,
      default: '导入商品列表'
    },
    // 规则ID
    ruleId: {
      type: [String, Number],
      default: null
    },
    // 是否显示导入按钮
    showImportButton: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      // 加载状态
      loading: false,
      // 商品列表数据
      list: [],
      // 总条数
      total: 0,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        skuSn: null
      },
      // 导入对话框状态
      uploadDialog: {
        open: false,
        isUploading: false,
        headers: { Authorization: "Bearer " + getToken() },
        url: process.env.VUE_APP_BASE_API + "/inventory/ruleStockGoods/import",
        data: {}
      }
    };
  },
  computed: {
    visible: {
      get() {
        return this.open;
      },
      set(val) {
        this.$emit('update:open', val);
      }
    }
  },
  watch: {
    open: {
      handler(newVal) {
        if (newVal && this.ruleId) {
          // 重置分页参数
          this.queryParams.pageNum = 1;
          this.queryParams.skuSn = null;
          this.loadGoodsList();
        }
      },
      immediate: true
    }
  },
  methods: {
    parseTime,

    /**
     * 加载商品列表数据
     */
    async loadGoodsList() {
      if (!this.ruleId) {
        this.$message.warning('规则ID不能为空');
        return;
      }

      this.loading = true;
      try {
        const response = await getRuleStockGoodsInfoList(this.ruleId, this.queryParams);
        this.list = response.rows || [];
        this.total = response.total || 0;
      } catch (error) {
        console.error('获取导入商品列表失败:', error);
        this.$message.error('获取导入商品列表失败');
        this.list = [];
        this.total = 0;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 关闭弹窗
     */
    handleClose() {
      // 重置查询参数
      this.queryParams.pageNum = 1;
      this.queryParams.skuSn = null;
      this.list = [];
      this.total = 0;
      this.visible = false;
    },

    /**
      * 刷新数据
      */
     refresh() {
       if (this.open && this.ruleId) {
         this.loadGoodsList();
       }
     },

     /**
      * 搜索按钮操作
      */
     handleQuery() {
       this.queryParams.pageNum = 1;
       this.loadGoodsList();
     },

     /**
      * 重置按钮操作
      */
     resetQuery() {
       this.queryParams.skuSn = null;
       this.queryParams.pageNum = 1;
       this.loadGoodsList();
     },

    /**
     * 打开导入商品对话框
     */
    handleImport() {
      if (!this.ruleId) {
        this.$message.warning('规则ID不能为空');
        return;
      }

      this.uploadDialog.open = true;
      this.uploadDialog.data = {
        rule_id: this.ruleId
      };
    },

    /**
     * 关闭导入对话框
     */
    closeUploadDialog() {
      this.uploadDialog.open = false;
      if (this.$refs.upload) {
        this.$refs.upload.clearFiles();
      }
    },

    /**
     * 文件上传中处理
     */
    handleFileUploadProgress(event, file, fileList) {
      this.uploadDialog.isUploading = true;
    },

    /**
     * 文件上传成功处理
     */
    handleFileSuccess(response, file, fileList) {
      this.uploadDialog.isUploading = false;
      this.$refs.upload.clearFiles();

      if (response.code === 200) {
        this.$alert("<div style='overflow: auto;overflow-x: hidden;max-height: 70vh;padding: 10px 20px 0;'>导入成功</div>", "导入结果", { dangerouslyUseHTMLString: true });
        this.closeUploadDialog();
        this.refresh(); // 刷新列表
      } else {
        this.$alert("<div style='overflow: auto;overflow-x: hidden;max-height: 70vh;padding: 10px 20px 0;'>" + response.msg + "</div>", "导入结果", { dangerouslyUseHTMLString: true });
      }
    },

    /**
      * 下载导入模板
      */
     importTemplate() {
       this.downloadTemplate();
     },

     /**
      * 下载模板文件
      */
     downloadTemplate() {
       const url = process.env.VUE_APP_BASE_API + '/inventory/ruleStockGoods/importTemplate';
       const filename = `ruleStock_template_${new Date().getTime()}.xlsx`;

       const link = document.createElement('a');
       link.href = url;
       link.download = filename;
       link.style.display = 'none';
       document.body.appendChild(link);
       link.click();
       document.body.removeChild(link);
     },

    /**
     * 提交上传文件
     */
    submitFileForm() {
      this.$refs.upload.submit();
    }
  }
};
</script>

<style scoped>
.search-form {
  margin-bottom: 15px;
}
.top-operation-buttons {
  margin-bottom: 15px;
  text-align: right;
}
/* 可以在这里添加组件特定的样式 */
</style>
