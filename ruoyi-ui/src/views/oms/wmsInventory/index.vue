<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="虚仓编码" prop="storeCode">
        <el-input
          v-model="queryParams.storeCode"
          placeholder="请输入虚仓编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="sku" prop="skuSn">
        <el-input
          v-model="queryParams.skuSn"
          placeholder="请输入sku"
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
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['wmsInventory:inventory:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="inventoryList" @selection-change="handleSelectionChange">
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="虚仓编码" align="center" prop="storeCode" />
      <el-table-column label="sku" align="center" prop="skuSn" />
      <el-table-column label="正品库存" align="center" prop="zpActualNumber" />
      <el-table-column label="次品库存" align="center" prop="cpActualNumber" />
      <el-table-column label="可用正品库存" align="center" prop="zpAvailableNumber" />
      <el-table-column label="可用次品库存" align="center" prop="cpAvailableNumber" />
      <el-table-column label="正品预占库存" align="center" prop="zpLockNumber" />
      <el-table-column label="次品预占库存" align="center" prop="cpLockNumber" />
      <el-table-column label="备注" align="center" prop="remark" />
      <el-table-column label="修改时间" align="center" prop="modifyTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.modifyTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
<!--      <el-table-column label="公司编码" align="center" prop="companyCode" />-->
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['inventory:inventory:edit']"
          >修改</el-button>
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

    <!-- 添加或修改仓库库存对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="650px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="虚仓编码" prop="storeCode">
          <el-input v-model="form.storeCode" :disabled = "true"/>
        </el-form-item>
        <el-form-item label="sku" prop="skuSn">
          <el-input v-model="form.skuSn" :disabled = "true"/>
        </el-form-item>
        <el-form-item label="正品库存" prop="zpActualNumber">
          <el-input v-model="form.zpActualNumber" placeholder="请输入正品库存" />
        </el-form-item>
        <el-form-item label="次品库存" prop="cpActualNumber">
          <el-input v-model="form.cpActualNumber" placeholder="请输入次品库存" />
        </el-form-item>
        <el-form-item label="可用正品库存" prop="zpAvailableNumber">
          <el-input v-model="form.zpAvailableNumber" placeholder="请输入可用正品库存" />
        </el-form-item>
        <el-form-item label="可用次品库存" prop="cpAvailableNumber">
          <el-input v-model="form.cpAvailableNumber" placeholder="请输入可用次品库存" />
        </el-form-item>
        <el-form-item label="正品预占库存" prop="zpLockNumber">
          <el-input v-model="form.zpLockNumber" placeholder="请输入正品预占库存" />
        </el-form-item>
        <el-form-item label="次品预占库存" prop="cpLockNumber">
          <el-input v-model="form.cpLockNumber" placeholder="请输入次品预占库存" />
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
import { listInventory, getInventory, delInventory, addInventory, updateInventory } from "@/api/wmsInventory/wmsInventory";

export default {
  name: "Inventory",
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
      // 仓库库存表格数据
      inventoryList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        storeCode: null,
        skuSn: null,
        zpActualNumber: null,
        cpActualNumber: null,
        zpAvailableNumber: null,
        cpAvailableNumber: null,
        zpLockNumber: null,
        cpLockNumber: null,
        //modifyTime: null,
        companyCode: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        storeCode: [
          { required: true, message: "虚仓编码不能为空", trigger: "blur" }
        ],
        skuSn: [
          { required: true, message: "sku不能为空", trigger: "blur" }
        ],
        zpActualNumber: [
          { required: true, message: "正品库存不能为空", trigger: "blur" }
        ],
        cpActualNumber: [
          { required: true, message: "次品库存不能为空", trigger: "blur" }
        ],
        zpAvailableNumber: [
          { required: true, message: "可用正品库存不能为空", trigger: "blur" }
        ],
        cpAvailableNumber: [
          { required: true, message: "可用次品库存不能为空", trigger: "blur" }
        ],
        zpLockNumber: [
          { required: true, message: "正品预占库存不能为空", trigger: "blur" }
        ],
        cpLockNumber: [
          { required: true, message: "次品预占库存不能为空", trigger: "blur" }
        ],
        remark: [
          { required: true, message: "备注不能为空", trigger: "blur" }
        ],
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询仓库库存列表 */
    getList() {
      this.loading = true;
      listInventory(this.queryParams).then(response => {
        this.inventoryList = response.rows;
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
        storeCode: null,
        skuSn: null,
        zpActualNumber: null,
        cpActualNumber: null,
        zpAvailableNumber: null,
        cpAvailableNumber: null,
        zpLockNumber: null,
        cpLockNumber: null,
        remark: null,
        //modifyTime: null,
        //createTime: null,
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
      this.title = "添加仓库库存";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getInventory(id).then(response => {
       let data = response.data;
        this.form.id = data.id;
        this.form.storeCode = data.storeCode;
        this.form.skuSn = data.skuSn;
        this.form.zpActualNumber = data.zpActualNumber;
        this.form.cpActualNumber = data.cpActualNumber;
        this.form.zpAvailableNumber = data.zpAvailableNumber;
        this.form.cpAvailableNumber = data.cpAvailableNumber;
        this.form.zpLockNumber = data.zpLockNumber;
        this.form.cpLockNumber = data.cpLockNumber;
        this.open = true;
        this.title = "修改仓库库存";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateInventory(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addInventory(this.form).then(response => {
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
      this.$modal.confirm('是否确认删除仓库库存编号为"' + ids + '"的数据项？').then(function() {
        return delInventory(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('inventory/wmsInventory/export', {
        ...this.queryParams
      }, `inventory_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
