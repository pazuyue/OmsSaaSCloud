<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="供应商编码" prop="supplierSn">
        <el-input
          v-model="queryParams.supplierSn"
          placeholder="请输入供应商编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="供应商简称" prop="supplierName">
        <el-input
          v-model="queryParams.supplierName"
          placeholder="请输入供应商简称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="公司全称" prop="companyName">
        <el-input
          v-model="queryParams.companyName"
          placeholder="请输入公司全称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="操作用户" prop="perationUser">
        <el-input
          v-model="queryParams.perationUser"
          placeholder="请输入操作用户"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="创建时间" prop="createTime">
        <el-date-picker clearable
                        v-model="queryParams.createTime"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择创建时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="修改时间" prop="modifyTime">
        <el-date-picker clearable
                        v-model="queryParams.modifyTime"
                        type="date"
                        value-format="yyyy-MM-dd"
                        placeholder="请选择修改时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['supplierIinfo:supplierIinfo:add']"
        >新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['supplierIinfo:supplierIinfo:edit']"
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
          v-hasPermi="['supplierIinfo:supplierIinfo:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['supplierIinfo:supplierIinfo:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="supplierIinfoList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="供应商编码" align="center" prop="supplierSn" />
      <el-table-column label="供应商简称" align="center" prop="supplierName" />
      <el-table-column label="公司全称" align="center" prop="companyName" />
      <el-table-column label="联系人" align="center" prop="contactUser" />
      <el-table-column label="联系电话" align="center" prop="contactTel" />
      <el-table-column label="省" align="center" prop="contactProvince" />
      <el-table-column label="市" align="center" prop="contactCity" />
      <el-table-column label="区" align="center" prop="contactArea" />
      <el-table-column label="联系人地址" align="center" prop="contactAddress" />
      <el-table-column label="操作用户" align="center" prop="perationUser" />
      <el-table-column label="创建时间" align="center" prop="createTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="公司编码" align="center" prop="companyCode" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['supplierIinfo:supplierIinfo:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['supplierIinfo:supplierIinfo:remove']"
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

    <!-- 添加或修改供应商主对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="供应商简称" prop="supplierName">
          <el-input v-model="form.supplierName" placeholder="请输入供应商简称" />
        </el-form-item>
        <el-form-item label="公司全称" prop="companyName">
          <el-input v-model="form.companyName" placeholder="请输入公司全称" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactUser">
          <el-input v-model="form.contactUser" placeholder="请输入联系人" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactTel">
          <el-input v-model="form.contactTel" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="省" prop="contactProvince">
          <el-input v-model="form.contactProvince" placeholder="请输入省" />
        </el-form-item>
        <el-form-item label="市" prop="contactCity">
          <el-input v-model="form.contactCity" placeholder="请输入市" />
        </el-form-item>
        <el-form-item label="区" prop="contactArea">
          <el-input v-model="form.contactArea" placeholder="请输入区" />
        </el-form-item>
        <el-form-item label="联系人地址" prop="contactAddress">
          <el-input v-model="form.contactAddress" placeholder="请输入联系人地址" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listSupplierIinfo, getSupplierIinfo, delSupplierIinfo, addSupplierIinfo, updateSupplierIinfo } from "@/api/supplier/supplier";

export default {
  name: "SupplierIinfo",
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
      // 供应商主表格数据
      supplierIinfoList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        supplierSn: null,
        supplierName: null,
        companyName: null,
        contactUser: null,
        contactTel: null,
        contactProvince: null,
        contactCity: null,
        contactArea: null,
        contactAddress: null,
        perationUser: null,
        createTime: null,
        modifyTime: null,
        companyCode: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        supplierSn: [
          { required: true, message: "供应商编码不能为空", trigger: "blur" }
        ],
        supplierName: [
          { required: true, message: "供应商简称不能为空", trigger: "blur" }
        ],
        companyName: [
          { required: true, message: "公司全称不能为空", trigger: "blur" }
        ],
        contactUser: [
          { required: true, message: "联系人不能为空", trigger: "blur" }
        ],
        contactTel: [
          { required: true, message: "联系电话不能为空", trigger: "blur" }
        ],
        contactProvince: [
          { required: true, message: "省不能为空", trigger: "blur" }
        ],
        contactCity: [
          { required: true, message: "市不能为空", trigger: "blur" }
        ],
        contactArea: [
          { required: true, message: "区不能为空", trigger: "blur" }
        ],
        contactAddress: [
          { required: true, message: "联系人地址不能为空", trigger: "blur" }
        ],
        perationUser: [
          { required: true, message: "操作用户不能为空", trigger: "blur" }
        ],
        companyCode: [
          { required: true, message: "公司编码不能为空", trigger: "blur" }
        ]
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询供应商主列表 */
    getList() {
      this.loading = true;
      listSupplierIinfo(this.queryParams).then(response => {
        this.supplierIinfoList = response.rows;
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
        supplierSn: null,
        supplierName: null,
        companyName: null,
        contactUser: null,
        contactTel: null,
        contactProvince: null,
        contactCity: null,
        contactArea: null,
        contactAddress: null,
        perationUser: null,
        companyCode: null
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
      this.title = "添加供应商主";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getSupplierIinfo(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改供应商主";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateSupplierIinfo(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addSupplierIinfo(this.form).then(response => {
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
      this.$modal.confirm('是否确认删除供应商主编号为"' + ids + '"的数据项？').then(function() {
        return delSupplierIinfo(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('supplychain/supplier/export', {
        ...this.queryParams
      }, `supplierIinfo_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
