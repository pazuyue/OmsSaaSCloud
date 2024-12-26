<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="是否启用" prop="enable">
        <el-select v-model="queryParams.enable" placeholder="请选择是否启用" clearable>
          <el-option
            v-for="dict in dict.type.oms_yes_no"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="分货单类型" prop="ruleType">
        <el-select v-model="queryParams.ruleType" placeholder="请选择分货单类型" clearable>
          <el-option
            v-for="dict in dict.type.inventory_allocation_rule_type"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="分货单编号" prop="ruleCode">
        <el-input
          v-model="queryParams.ruleCode"
          placeholder="请输入分货单编号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="分货单名称" prop="ruleName">
        <el-input
          v-model="queryParams.ruleName"
          placeholder="请输入分货单名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-input
          v-model="queryParams.status"
          placeholder="请输入状态"
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
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['ruleStock:info:add']"
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
          v-hasPermi="['ruleStock:info:edit']"
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
          v-hasPermi="['ruleStock:info:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['ruleStock:info:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="infoList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="是否启用" align="center" prop="enable">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.enable"/>
        </template>
      </el-table-column>
      <el-table-column label="分货单类型" align="center" prop="ruleType">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.inventory_allocation_rule_type" :value="scope.row.ruleType"/>
        </template>
      </el-table-column>
      <el-table-column label="分货单编号" align="center" prop="ruleCode" />
      <el-table-column label="分货单名称" align="center" prop="ruleName" />
      <el-table-column label="状态" align="center" prop="status">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.inventory_allocation_status" :value="scope.row.status"/>
        </template>
      </el-table-column>
      <el-table-column label="开始时间" align="center" prop="startTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.startTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结束时间" align="center" prop="endTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.endTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="分货范围" align="center" prop="ruleRange">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.goods_range" :value="scope.row.ruleRange"/>
        </template>
      </el-table-column>
      <el-table-column label="备注" align="center" prop="remark" />
      <el-table-column label="商品类型" align="center" prop="type">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.goods_type" :value="scope.row.type"/>
        </template>
      </el-table-column>
      <el-table-column label="最新分货时间" align="center" prop="lastUpdateTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.lastUpdateTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="首次审核时间" align="center" prop="firstReviewerTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.firstReviewerTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="审核时间" align="center" prop="reviewerTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.reviewerTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结束时间" align="center" prop="overTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.overTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="作废时间" align="center" prop="cancelTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.cancelTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="审核人姓名" align="center" prop="reviewerUserName" />
      <el-table-column label="创建人姓名" align="center" prop="createUserName" />
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
            v-hasPermi="['ruleStock:info:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['ruleStock:info:remove']"
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

    <!-- 添加或修改分货单基础信息对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="是否启用" prop="enable">
          <el-radio-group v-model="form.enable">
            <el-radio
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="parseInt(dict.value)"
            >{{dict.label}}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分货单类型" prop="ruleType">
          <el-select v-model="form.ruleType" placeholder="请选择分货单类型">
            <el-option
              v-for="dict in dict.type.inventory_allocation_rule_type"
              :key="dict.value"
              :label="dict.label"
              :value="parseInt(dict.value)"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="分货单名称" prop="ruleName">
          <el-input v-model="form.ruleName" placeholder="请输入分货单名称" />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker clearable
                          v-model="form.startTime"
                          type="datetime"
                          value-format="yyyy-MM-dd HH:mm:ss"
                          placeholder="请选择分货单有效期, 开始时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker clearable
                          v-model="form.endTime"
                          type="datetime"
                          value-format="yyyy-MM-dd HH:mm:ss"
                          placeholder="请选择分货单有效期, 结束时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" placeholder="请输入备注" />
        </el-form-item>
        <el-form-item label="商品类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择商品类型">
            <el-option
              v-for="dict in dict.type.goods_type"
              :key="dict.value"
              :label="dict.label"
              :value="parseInt(dict.value)"
            ></el-option>
          </el-select>
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
import { listInfo, getInfo, delInfo, addInfo, updateInfo } from "@/api/ruleStock/info";

export default {
  name: "Info",
  dicts: ['oms_yes_no', 'inventory_allocation_rule_type', 'goods_type','inventory_allocation_status','goods_range'],
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
      // 分货单基础信息表格数据
      infoList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        enable: null,
        ruleType: null,
        ruleCode: null,
        ruleName: null,
        status: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        enable: [
          { required: true, message: "是否启用不能为空", trigger: "change" }
        ],
        ruleType: [
          { required: true, message: "分货单类型：1，日常分货；2，一次性分货；3，锁库时分货不能为空", trigger: "change" }
        ],
        ruleName: [
          { required: true, message: "分货单名称不能为空", trigger: "blur" }
        ],
        startTime: [
          { required: true, message: "分货单有效期, 开始时间不能为空", trigger: "blur" }
        ],
        endTime: [
          { required: true, message: "分货单有效期, 结束时间不能为空", trigger: "blur" }
        ],
        ruleRange: [
          { required: true, message: "分货范围 : 1,全部商品; 2部分商品,不能为空", trigger: "change" }
        ],
        type: [
          { required: true, message: "商品类型不能为空", trigger: "change" }
        ],
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询分货单基础信息列表 */
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
        enable: 1,
        ruleType: null,
        ruleCode: null,
        ruleName: null,
        status: null,
        startTime: null,
        endTime: null,
        ruleRange: null,
        remark: null,
        type: null,
        lastUpdateTime: null,
        firstReviewerTime: null,
        reviewerTime: null,
        overTime: null,
        cancelTime: null,
        reviewerUserName: null,
        createUserName: null,
        strChannelId: null,
        createTime: null,
        modifyTime: null,
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
      this.title = "添加分货单基础信息";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getInfo(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改分货单基础信息";
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
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除分货单基础信息编号为"' + ids + '"的数据项？').then(function() {
        return delInfo(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('ruleStock/export', {
        ...this.queryParams
      }, `info_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
