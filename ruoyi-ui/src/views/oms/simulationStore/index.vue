<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="是否开启" prop="status">
        <el-select v-model="queryParams.status" placeholder="请选择是否开启" clearable>
          <el-option
            v-for="dict in dict.type.oms_yes_no"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="虚仓编码" prop="wmsSimulationCode">
        <el-input
          v-model="queryParams.wmsSimulationCode"
          placeholder="请输入虚仓编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="虚仓名称" prop="wmsSimulationName">
        <el-input
          v-model="queryParams.wmsSimulationName"
          placeholder="请输入虚仓名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="货主编码" prop="ownerCode">
        <el-input
          v-model="queryParams.ownerCode"
          placeholder="请输入货主编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="货主名称" prop="ownerName">
        <el-input
          v-model="queryParams.ownerName"
          placeholder="请输入货主名称"
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
          v-hasPermi="['warehouse:simulationStoreInfo:add']"
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
          v-hasPermi="['warehouse:simulationStoreInfo:edit']"
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
          v-hasPermi="['warehouse:simulationStoreInfo:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['warehouse:simulationStoreInfo:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="simulationStoreInfoList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="是否开启" align="center" prop="status">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.status"/>
        </template>
      </el-table-column>
      <el-table-column label="虚仓编码" align="center" prop="wmsSimulationCode" />
      <el-table-column label="虚仓名称" align="center" prop="wmsSimulationName" />
      <el-table-column label="货主编码" align="center" prop="ownerCode" />
      <el-table-column label="货主名称" align="center" prop="ownerName" />
      <el-table-column label="公司编码" align="center" prop="companyCode" />
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
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['warehouse:simulationStoreInfo:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['warehouse:simulationStoreInfo:remove']"
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

    <!-- 添加或修改虚仓对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="是否开启" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="parseInt(dict.value)"
            >{{dict.label}}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="虚仓编码" prop="wmsSimulationCode">
          <el-input v-model="form.wmsSimulationCode" placeholder="请输入虚仓编码" />
        </el-form-item>
        <el-form-item label="虚仓名称" prop="wmsSimulationName">
          <el-input v-model="form.wmsSimulationName" placeholder="请输入虚仓名称" />
        </el-form-item>
        <el-form-item label="货主编码" prop="ownerCode">
          <el-input v-model="form.ownerCode" placeholder="请输入货主编码" />
        </el-form-item>
        <el-form-item label="货主名称" prop="ownerName">
          <el-input v-model="form.ownerName" placeholder="请输入货主名称" />
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
import { listSimulationStoreInfo, getSimulationStoreInfo, delSimulationStoreInfo, addSimulationStoreInfo, updateSimulationStoreInfo } from "@/api/simulationStore/simulationStore";

export default {
  name: "SimulationStoreInfo",
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
      // 虚仓表格数据
      simulationStoreInfoList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        status: null,
        wmsSimulationCode: null,
        wmsSimulationName: null,
        ownerCode: null,
        ownerName: null,
        companyCode: null,
        modifyTime: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        status: [
          { required: true, message: "是否开启不能为空", trigger: "change" }
        ],
        wmsSimulationCode: [
          { required: true, message: "虚仓编码不能为空", trigger: "blur" }
        ],
        wmsSimulationName: [
          { required: true, message: "虚仓名称不能为空", trigger: "blur" }
        ],
        ownerCode: [
          { required: true, message: "货主编码不能为空", trigger: "blur" }
        ],
        ownerName: [
          { required: true, message: "货主名称不能为空", trigger: "blur" }
        ],
        companyCode: [
          { required: true, message: "公司编码不能为空", trigger: "blur" }
        ],
        createTime: [
          { required: true, message: "创建时间不能为空", trigger: "blur" }
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
    /** 查询虚仓列表 */
    getList() {
      this.loading = true;
      listSimulationStoreInfo(this.queryParams).then(response => {
        this.simulationStoreInfoList = response.rows;
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
        status: null,
        wmsSimulationCode: null,
        wmsSimulationName: null,
        ownerCode: null,
        ownerName: null,
        companyCode: null,
        createTime: null,
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
      this.title = "添加虚仓";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getSimulationStoreInfo(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改虚仓";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateSimulationStoreInfo(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addSimulationStoreInfo(this.form).then(response => {
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
      this.$modal.confirm('是否确认删除虚仓编号为"' + ids + '"的数据项？').then(function() {
        return delSimulationStoreInfo(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('supplychain/simulationStore/export', {
        ...this.queryParams
      }, `simulationStoreInfo_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
