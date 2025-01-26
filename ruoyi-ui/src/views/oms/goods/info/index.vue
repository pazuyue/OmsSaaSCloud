<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="sku" prop="skuSn">
        <el-input
          v-model="queryParams.skuSn"
          placeholder="请输入sku"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="货号" prop="goodsSn">
        <el-input
          v-model="queryParams.goodsSn"
          placeholder="请输入货号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="条形码" prop="barcodeSn">
        <el-input
          v-model="queryParams.barcodeSn"
          placeholder="请输入条形码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="商品名称" prop="goodsName">
        <el-input
          v-model="queryParams.goodsName"
          placeholder="请输入商品名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="类目" prop="categoryCode">
        <el-input
          v-model="queryParams.categoryCode"
          placeholder="请输入类目"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="颜色编码" prop="colorCode">
        <el-input
          v-model="queryParams.colorCode"
          placeholder="请输入颜色编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="尺码" prop="sizeCode">
        <el-input
          v-model="queryParams.sizeCode"
          placeholder="请输入尺码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="市场价" prop="marketPrice">
        <el-input
          v-model="queryParams.marketPrice"
          placeholder="请输入市场价"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="有效期" prop="validity">
        <el-input
          v-model="queryParams.validity"
          placeholder="请输入有效期"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="商品描述" prop="goodsDesc">
        <el-input
          v-model="queryParams.goodsDesc"
          placeholder="请输入商品描述"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="是否福袋" prop="isFd">
        <el-select v-model="queryParams.isFd" placeholder="请选择是否福袋" clearable>
          <el-option
            v-for="dict in dict.type.oms_yes_no"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="是否赠品" prop="isGift">
        <el-select v-model="queryParams.isGift" placeholder="请选择是否赠品" clearable>
          <el-option
            v-for="dict in dict.type.oms_yes_no"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="公司编码" prop="companyCode">
        <el-input
          v-model="queryParams.companyCode"
          placeholder="请输入公司编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="修改时间" prop="modifyTime">
        <el-date-picker clearable
          v-model="queryParams.modifyTime"
          type="date"
          value-format="yyyy-MM-dd"
          placeholder="请选择修改时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="是否套装" prop="isPackage">
        <el-select v-model="queryParams.isPackage" placeholder="请选择是否套装" clearable>
          <el-option
            v-for="dict in dict.type.oms_yes_no"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
<!--      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['goods:info:add']"
        >新增</el-button>
      </el-col>-->
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleImport"
          v-hasPermi="['goods:info:import']"
        >导入</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['goods:info:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['goods:info:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['goods:info:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="infoList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
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
      <el-table-column label="备注" align="center" prop="description" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['goods:info:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['goods:info:remove']"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 添加或修改产品信息对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="sku" prop="skuSn">
          <el-input v-model="form.skuSn" placeholder="请输入sku" />
        </el-form-item>
        <el-form-item label="货号" prop="goodsSn">
          <el-input v-model="form.goodsSn" placeholder="请输入货号" />
        </el-form-item>
        <el-form-item label="条形码" prop="barcodeSn">
          <el-input v-model="form.barcodeSn" placeholder="请输入条形码" />
        </el-form-item>
        <el-form-item label="商品名称" prop="goodsName">
          <el-input v-model="form.goodsName" placeholder="请输入商品名称" />
        </el-form-item>
        <el-form-item label="类目" prop="categoryCode">
          <el-input v-model="form.categoryCode" placeholder="请输入类目" />
        </el-form-item>
        <el-form-item label="颜色编码" prop="colorCode">
          <el-input v-model="form.colorCode" placeholder="请输入颜色编码" />
        </el-form-item>
        <el-form-item label="尺码" prop="sizeCode">
          <el-input v-model="form.sizeCode" placeholder="请输入尺码" />
        </el-form-item>
        <el-form-item label="市场价" prop="marketPrice">
          <el-input v-model="form.marketPrice" placeholder="请输入市场价" />
        </el-form-item>
        <el-form-item label="有效期" prop="validity">
          <el-input v-model="form.validity" placeholder="请输入有效期" />
        </el-form-item>
        <el-form-item label="商品描述" prop="goodsDesc">
          <el-input v-model="form.goodsDesc" placeholder="请输入商品描述" />
        </el-form-item>
        <el-form-item label="是否福袋" prop="isFd">
          <el-radio-group v-model="form.isFd">
            <el-radio
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="parseInt(dict.value)"
            >{{dict.label}}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="是否赠品" prop="isGift">
          <el-radio-group v-model="form.isGift">
            <el-radio
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="parseInt(dict.value)"
            >{{dict.label}}</el-radio>
          </el-radio-group>
        </el-form-item>
<!--        <el-form-item label="公司编码" prop="companyCode">
          <el-input v-model="form.companyCode" placeholder="请输入公司编码" />
        </el-form-item>
        <el-form-item label="修改时间" prop="modifyTime">
          <el-date-picker clearable
            v-model="form.modifyTime"
            type="date"
            value-format="yyyy-MM-dd"
            placeholder="请选择修改时间">
          </el-date-picker>
        </el-form-item>-->
        <el-form-item label="是否套装" prop="isPackage">
          <el-radio-group v-model="form.isPackage">
            <el-radio
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="parseInt(dict.value)"
            >{{dict.label}}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="description">
          <el-input v-model="form.description" type="textarea" placeholder="请输入内容" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

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
      </el-table>
      <pagination
        v-show="upload.exportTotal>0"
        :total="upload.exportTotal"
        :page.sync="upload.queryExportParams.pageNum"
        :limit.sync="upload.queryExportParams.pageSize"
        @pagination="getList"
      />
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="toExamine">确 定</el-button>
        <el-button @click="upload.exportOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {listInfo, exportListInfo, getInfo, delInfo, addInfo, updateInfo, toExamine} from "@/api/goods/info";
import {getToken} from "@/utils/auth";

export default {
  name: "Info",
  dicts: ['oms_yes_no'],
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 产品信息表格数据
      infoList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
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
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        skuSn: null,
        goodsSn: null,
        barcodeSn: null,
        goodsName: null,
        categoryCode: null,
        colorCode: null,
        sizeCode: null,
        marketPrice: null,
        validity: null,
        goodsDesc: null,
        isFd: null,
        isGift: null,
        companyCode: null,
        modifyTime: null,
        isPackage: null,
        description: null
      },

      // 表单参数
      form: {},
      // 表单校验
      rules: {
        skuSn: [
          { required: true, message: "sku不能为空", trigger: "blur" }
        ],
        goodsSn: [
          { required: true, message: "货号不能为空", trigger: "blur" }
        ],
        barcodeSn: [
          { required: true, message: "条形码不能为空", trigger: "blur" }
        ],
        goodsName: [
          { required: true, message: "商品名称不能为空", trigger: "blur" }
        ],
        categoryCode: [
          { required: true, message: "类目不能为空", trigger: "blur" }
        ],
        colorCode: [
          { required: true, message: "颜色编码不能为空", trigger: "blur" }
        ],
        sizeCode: [
          { required: true, message: "尺码不能为空", trigger: "blur" }
        ],
        marketPrice: [
          { required: true, message: "市场价不能为空", trigger: "blur" }
        ],
        goodsDesc: [
          { required: true, message: "商品描述不能为空", trigger: "blur" }
        ],
        isFd: [
          { required: true, message: "是否福袋不能为空", trigger: "change" }
        ],
        isGift: [
          { required: true, message: "是否赠品不能为空", trigger: "change" }
        ],
        companyCode: [
          { required: true, message: "公司编码不能为空", trigger: "blur" }
        ],
        createTime: [
          { required: true, message: "添加时间不能为空", trigger: "blur" }
        ],
        modifyTime: [
          { required: true, message: "修改时间不能为空", trigger: "blur" }
        ],
        isPackage: [
          { required: true, message: "是否套装不能为空", trigger: "change" }
        ],
        createBy: [
          { required: true, message: "创建者不能为空", trigger: "blur" }
        ],
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询产品信息列表 */
    getList() {
      this.loading = true;
      listInfo(this.queryParams).then(response => {
        this.infoList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    // 表单重置
    reset() {
      this.form = {
        id: null,
        skuSn: null,
        goodsSn: null,
        barcodeSn: null,
        goodsName: null,
        categoryCode: null,
        colorCode: null,
        sizeCode: null,
        marketPrice: null,
        validity: null,
        goodsDesc: null,
        isFd: null,
        isGift: null,
        companyCode: null,
        createTime: null,
        modifyTime: null,
        isPackage: null,
        createBy: null,
        description: null
      };
      this.resetForm("form");
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length!==1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加产品信息";
    },
    handleImport() {
      this.upload.title = "产品导入";
      this.upload.open = true;
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getInfo(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改产品信息";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateInfo(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addInfo(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
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
        //this.$alert("<div style='overflow: auto;overflow-x: hidden;max-height: 70vh;padding: 10px 20px 0;'>导入成功</div>", "导入结果", { dangerouslyUseHTMLString: true });
        this.showImportList(response.msg)
      }else {
        this.$alert("<div style='overflow: auto;overflow-x: hidden;max-height: 70vh;padding: 10px 20px 0;'>导入失败</div>", "导入结果", { dangerouslyUseHTMLString: true });
      }
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
    // 提交上传文件
    submitFileForm() {
      this.$refs.upload.submit();
    },
    importTemplate() {
      this.download('goods/goodsAdministration/importTemplate', {
      }, `goods_template_${new Date().getTime()}.xlsx`)
    },

    toExamine(){
      console.log(this.upload.queryExportParams.import_batch)
      toExamine(this.upload.queryExportParams).then(response => {
        this.$modal.msgSuccess("审核成功");
        this.upload.exportOpen = false;
        this.upload.exportInfoList = []
        this.upload.queryExportParams.import_batch =''
        this.getList();
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除产品信息编号为"' + ids + '"的数据项？').then(function() {
        return delInfo(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('goods/info/export', {
        ...this.queryParams
      }, `info_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
