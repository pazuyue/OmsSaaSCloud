<template>
  <el-card class="app-container" style="margin: 5px">
    <!-- 用户导入对话框 -->
    <el-dialog :title="upload.title" :visible.sync="upload.open" width="400px" append-to-body>
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
    <!-- 用户导入对话框 -->
    <el-dialog :title="upload.title" :visible.sync="upload.exportOpen" width="80%" append-to-body>
      <el-table v-loading="loading" :data="this.upload.exportInfoList">
        <el-table-column label="ID" align="center" prop="id" />
        <el-table-column label="sku" align="center" prop="skuSn" />
        <el-table-column label="货号" align="center" prop="goodsSn" />
        <el-table-column label="条形码" align="center" prop="barcodeSn" />
        <el-table-column label="商品名称" align="center" prop="goodsName" />
        <el-table-column label="类目" align="center" prop="categoryCode" />
        <el-table-column label="颜色编码" align="center" prop="colorCode" />
        <el-table-column label="尺码" align="center" prop="sizeCode" />
        <el-table-column label="市场价" align="center" prop="marketPrice" />
        <el-table-column label="有效期" align="center" prop="validity" />
        <el-table-column label="商品描述" align="center" prop="goodsDesc" />
        <el-table-column label="是否福袋" align="center" prop="isFd">
          <template slot-scope="scope">
            <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.isFd"/>
          </template>
        </el-table-column>
        <el-table-column label="是否赠品" align="center" prop="isGift">
          <template slot-scope="scope">
            <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.isGift"/>
          </template>
        </el-table-column>
        <el-table-column label="公司编码" align="center" prop="companyCode" />
        <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
          <template slot-scope="scope">
            <span>{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d}') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="是否套装" align="center" prop="isPackage">
          <template slot-scope="scope">
            <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.isPackage"/>
          </template>
        </el-table-column>
        <el-table-column label="备注" align="center" prop="notes" />
      </el-table>
      <pagination
        v-show="upload.exportTotal>0"
        :total="total"
        :page.sync="upload.queryExportParams.pageNum"
        :limit.sync="upload.queryExportParams.pageSize"
        @pagination="exportListInfo"
      />
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="">确 定</el-button>
        <el-button @click="upload.exportOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </el-card>

</template>

<script>
import {exportListInfo} from "@/api/goods/info";
import {getToken} from "@/utils/auth";
export default {
  name: "upload",
  dicts: ['oms_yes_no'],
  data: function() {
    return {
      loading:false,
      upload: {
        // 是否显示弹出层（用户导入）
        open: false,
        // 是否显示弹出层（用户导入）
        exportOpen: false,
        // 弹出层标题（用户导入）
        title: "",
        // 是否禁用上传
        isUploading: false,
        // 设置上传的请求头部
        headers: { Authorization: "Bearer " + getToken() },
        // 上传的地址
        url: process.env.VUE_APP_BASE_API + "/goods/goodsAdministration/import",
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
  methods: {
    handleImport() {
      this.upload.title = "产品导入";
      this.upload.open = true;
    },
    /** 查询产品信息列表 */
    showImportList(importBatch) {
      this.loading = true;
      this.upload.title = "导入结果";
      this.upload.open = false;
      this.upload.exportOpen = true;
      this.upload.queryExportParams.import_batch = importBatch;
      console.log( this.upload.queryExportParams)
      exportListInfo(this.upload.queryExportParams).then(response => {
        this.upload.exportInfoList = response.rows;
        this.upload.exportTotal = response.total;
        this.loading = false;
      });
    },
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
    // 提交上传文件
    submitFileForm() {
      this.$refs.upload.submit();
    },
    importTemplate() {
      this.download('goods/goodsAdministration/importTemplate', {
      }, `goods_template_${new Date().getTime()}.xlsx`)
    },
  }
}
</script>

<style scoped>

</style>
