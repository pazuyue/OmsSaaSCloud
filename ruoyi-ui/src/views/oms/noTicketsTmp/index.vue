<template>
  <el-card class="app-container" style="margin: 5px">
    <!-- 用户导入对话框 -->
    <el-dialog :title="noTicketsTmpTitle" :visible.sync="noTicketsTmpOpen" width="80%" append-to-body @close="handleClose">
      <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">

        <el-form-item label="sku_sn" prop="skuSn">
          <el-input
            v-model="queryParams.skuSn"
            placeholder="请输入sku_sn"
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
        <el-form-item label="货号" prop="goodsSn">
          <el-input
            v-model="queryParams.goodsSn"
            placeholder="请输入货号"
            clearable
            @keyup.enter.native="handleQuery"
          />
        </el-form-item>
        <el-form-item label="产品名称" prop="goodsName">
          <el-input
            v-model="queryParams.goodsName"
            placeholder="请输入产品名称"
            clearable
            @keyup.enter.native="handleQuery"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-row :gutter="10" class="mb8">
        <el-col :span="1.5">
<!--          <el-button
            type="primary"
            plain
            icon="el-icon-plus"
            size="mini"
            @click="handleAdd"
            v-hasPermi="['system:tmp:add']"
          >新增</el-button>-->
        </el-col>
        <el-col :span="1.5">
          <el-button
            type="success"
            plain
            icon="el-icon-edit"
            size="mini"
            :disabled="single"
            @click="handleUpdate"
            v-hasPermi="['warehouse:noTicketsTmp:edit']"
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
            v-hasPermi="['warehouse:noTicketsTmp:remove']"
          >删除</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button
            type="warning"
            plain
            icon="el-icon-download"
            size="mini"
            @click="handleExport"
            v-hasPermi="['warehouse:noTicketsTmp:export']"
          >导出</el-button>
        </el-col>
        <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
      </el-row>

      <el-table v-loading="loading" :data="tmpList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="ID" align="center" prop="id" />
        <el-table-column label="入库单号" align="center" prop="noSn" />
        <el-table-column label="sku_sn" align="center" prop="skuSn" />
        <el-table-column label="条形码" align="center" prop="barcodeSn" />
        <el-table-column label="货号" align="center" prop="goodsSn" />
        <el-table-column label="产品名称" align="center" prop="goodsName" />
        <el-table-column label="采购价格" align="center" prop="purchasePrice" />
        <el-table-column label="计划入库-正品数量" align="center" prop="zpNumberExpected" />
        <el-table-column label="公司编码" align="center" prop="companyCode" />
        <el-table-column label="错误信息" align="center" prop="errorInfo" />
        <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
          <template slot-scope="scope">
            <span>{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d}') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
          <template slot-scope="scope">
            <el-button
              size="mini"
              type="text"
              icon="el-icon-edit"
              @click="handleUpdate(scope.row)"
              v-hasPermi="['system:tmp:edit']"
            >修改</el-button>
            <el-button
              size="mini"
              type="text"
              icon="el-icon-delete"
              @click="handleDelete(scope.row)"
              v-hasPermi="['system:tmp:remove']"
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

      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitExamine">提交审核</el-button>
        <el-button @click="cancelM">取 消</el-button>
      </div>


      <!-- 添加或修改入库通知单明细-未送审对话框 -->
      <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
        <el-form ref="form" :model="form" :rules="rules" label-width="80px">
          <el-form-item label="入库单号" prop="noSn">
            <el-input v-model="form.noSn" placeholder="请输入入库单号" readonly />
          </el-form-item>
          <el-form-item label="sku_sn" prop="skuSn">
            <el-input v-model="form.skuSn" placeholder="请输入sku_sn" />
          </el-form-item>
          <el-form-item label="采购价格" prop="purchasePrice">
            <el-input v-model="form.purchasePrice" placeholder="请输入采购价格" />
          </el-form-item>
          <el-form-item label="计划入库-正品数量" prop="zpNumberExpected">
            <el-input v-model="form.zpNumberExpected" placeholder="请输入计划入库-正品数量" />
          </el-form-item>
        </el-form>
        <div slot="footer" class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </el-dialog>
    </el-dialog>
  </el-card>
</template>

<script>
import {listTmp, getTmp, delTmp, addTmp, updateTmp,submitExamine} from "@/api/noTicketTmp/noTicketTmp";

export default {
  name: "noTicketsTmpList",
  props: {
    noTicketsTmpTitle: {
      type: String,
      default: ''
    },
    data: {
      type: Object,
      default: ''
    },
    noTicketsTmpOpen: {
      type: Boolean,
      default: false
    },
  },
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
      // 入库通知单明细-未送审表格数据
      tmpList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        noSn: null,
        skuSn: null,
        barcodeSn: null,
        goodsSn: null,
        goodsName: null,
        purchasePrice: null,
        zpNumberExpected: null,
        companyCode: null,
        errorInfo: null,
        modifyTime: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        noSn: [
          { required: true, message: "入库单号不能为空", trigger: "blur" }
        ],
        skuSn: [
          { required: true, message: "sku_sn不能为空", trigger: "blur" }
        ],
        barcodeSn: [
          { required: true, message: "条形码不能为空", trigger: "blur" }
        ],
        goodsSn: [
          { required: true, message: "货号不能为空", trigger: "blur" }
        ],
        goodsName: [
          { required: true, message: "产品名称不能为空", trigger: "blur" }
        ],
        purchasePrice: [
          { required: true, message: "采购价格不能为空", trigger: "blur" }
        ],
        companyCode: [
          { required: true, message: "公司编码不能为空", trigger: "blur" }
        ],
        modifyTime: [
          { required: true, message: "修改时间不能为空", trigger: "blur" }
        ]
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询入库通知单明细-未送审列表 */
    getList() {
      this.loading = true;
      listTmp(this.queryParams).then(response => {
        this.tmpList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    // 提交审核
    submitExamine(){
      submitExamine(this.data.noSn).then(response => {
        this.$message.success(response.msg);
        this.open = false;
        this.$emit('update:noTicketsTmpOpen', false); // 通知父组件关闭
        this.$emit('update:open', false); // 通知父组件关闭
        this.$emit('update:open2', false); // 通知父组件关闭
      });
    },
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    // 取消按钮
    cancelM() {
      this.$emit('update:noTicketsTmpOpen', false); // 通知父组件关闭
    },
    // 表单重置
    reset() {
      this.form = {
        id: null,
        noSn: null,
        skuSn: null,
        barcodeSn: null,
        goodsSn: null,
        goodsName: null,
        purchasePrice: null,
        zpNumberExpected: null,
        companyCode: null,
        errorInfo: null,
        modifyTime: null
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
      this.title = "添加入库通知单明细-未送审";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getTmp(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改入库通知单明细-未送审";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateTmp(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addTmp(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除入库通知单明细-未送审编号为"' + ids + '"的数据项？').then(function() {
        return delTmp(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('supplychain/tmp/export', {
        ...this.queryParams
      }, `tmp_${new Date().getTime()}.xlsx`)
    },
    handleClose() {
      this.$emit('update:noTicketsTmpOpen', false); // 通知父组件关闭
    }
  }
}
</script>

<style scoped>

</style>
