<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="渠道名称" prop="channelName">
        <el-input
          v-model="queryParams.channelName"
          placeholder="请输入渠道名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="外部关联编码" prop="outCorrelationCode">
        <el-input
          v-model="queryParams.outCorrelationCode"
          placeholder="请输入外部关联编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="渠道编码" prop="channelType">
        <el-select v-model="queryParams.channelType" placeholder="请选择渠道编码" clearable>
          <el-option
            v-for="dict in dict.type.channel_type"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="是否启用" prop="enabled">
        <el-select v-model="queryParams.enabled" placeholder="请选择是否启用 1-开启,0-禁用" clearable>
          <el-option
            v-for="dict in dict.type.oms_yes_no"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="对接平台" prop="toChannelEnabled">
        <el-select v-model="queryParams.toChannelEnabled" placeholder="请选择对接平台, 1：需对接，0：无需对接" clearable>
          <el-option
            v-for="dict in dict.type.oms_yes_no"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="经营模式" prop="mmodelType">
        <el-select v-model="queryParams.mmodelType" placeholder="请选择经营模式：1直营, 2加盟" clearable>
          <el-option
            v-for="dict in dict.type.m_model_type"
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
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['system:channel:add']"
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
          v-hasPermi="['system:channel:edit']"
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
          v-hasPermi="['system:channel:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['system:channel:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="channelList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="渠道ID" align="center" prop="channelId" />
      <el-table-column label="渠道名称" align="center" prop="channelName" />
      <el-table-column label="外部关联编码" align="center" prop="outCorrelationCode" />
      <el-table-column label="渠道编码" align="center" prop="channelType">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.channel_type" :value="scope.row.channelType"/>
        </template>
      </el-table-column>
      <el-table-column label="是否启用" align="center" prop="enabled">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.enabled"/>
        </template>
      </el-table-column>
      <el-table-column label="是否对接平台" align="center" prop="toChannelEnabled">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.toChannelEnabled"/>
        </template>
      </el-table-column>
      <el-table-column label="是否同步库存" align="center" prop="syncEnabled">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.oms_yes_no" :value="scope.row.syncEnabled"/>
        </template>
      </el-table-column>
      <el-table-column label="经营模式" align="center" prop="mmodelType">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.m_model_type" :value="scope.row.mmodelType"/>
        </template>
      </el-table-column>
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
            v-hasPermi="['system:channel:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:channel:remove']"
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

    <!-- 添加或修改店铺信息对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="渠道名称" prop="channelName">
          <el-input v-model="form.channelName" placeholder="请输入渠道名称" />
        </el-form-item>
        <el-form-item label="外部关联编码" prop="outCorrelationCode">
          <el-input v-model="form.outCorrelationCode" placeholder="请输入外部关联编码" />
        </el-form-item>
        <el-form-item label="渠道编码" prop="channelType">
          <el-select v-model="form.channelType" placeholder="请选择渠道编码">
            <el-option
              v-for="dict in dict.type.channel_type"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="是否启用" prop="enabled">
          <el-select v-model="form.enabled" placeholder="请选择是否启用 1-开启,0-禁用">
            <el-option
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="dict.label"
              :value="parseInt(dict.value)"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="是否对接平台" prop="toChannelEnabled">
          <el-select v-model="form.toChannelEnabled" placeholder="请选择对接平台, 1：需对接，0：无需对接">
            <el-option
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="dict.label"
              :value="parseInt(dict.value)"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="是否同步库存" prop="syncEnabled">
          <el-select v-model="form.syncEnabled" placeholder="是否同步库存, 1：需对接，0：无需对接">
            <el-option
              v-for="dict in dict.type.oms_yes_no"
              :key="dict.value"
              :label="dict.label"
              :value="parseInt(dict.value)"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="经营模式" prop="mmodelType">
          <el-select v-model="form.mmodelType" placeholder="请选择经营模式：1直营, 2加盟">
            <el-option
              v-for="dict in dict.type.m_model_type"
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
import { listChannel, getChannel, delChannel, addChannel, updateChannel } from "@/api/channel/channel";

export default {
  name: "Channel",
  dicts: ['oms_yes_no', 'channel_type', 'm_model_type'],
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
      // 店铺信息表格数据
      channelList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        channelName: null,
        outCorrelationCode: null,
        channelType: null,
        enabled: null,
        toChannelEnabled: null,
        syncEnabled: null,
        mmodelType: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        channelName: [
          { required: true, message: "渠道名称不能为空", trigger: "blur" }
        ],
       /* outCorrelationCode: [
          { required: true, message: "外部关联编码不能为空", trigger: "blur" }
        ],*/
        channelType: [
          { required: true, message: "渠道编码不能为空", trigger: "change" }
        ],
        enabled: [
          { required: true, message: "是否启用 1-开启,0-禁用不能为空", trigger: "change" }
        ],
        toChannelEnabled: [
          { required: true, message: "对接平台, 1：需对接，0：无需对接不能为空", trigger: "change" }
        ],
        syncEnabled: [
          { required: true, message: "是否同步库存 1-开启, 0-关闭不能为空", trigger: "blur" }
        ],
        mmodelType: [
          { required: true, message: "经营模式：1直营, 0加盟不能为空", trigger: "change" }
        ],
        companyCode: [
          { required: true, message: "公司编码不能为空", trigger: "blur" }
        ],
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询店铺信息列表 */
    getList() {
      this.loading = true;
      listChannel(this.queryParams).then(response => {
        this.channelList = response.rows;
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
        channelId: null,
        channelName: null,
        outCorrelationCode: null,
        channelType: null,
        enabled: null,
        toChannelEnabled: null,
        syncEnabled: null,
        mmodelType: null,
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
      this.ids = selection.map(item => item.channelId)
      this.single = selection.length!==1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加店铺信息";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const channelId = row.channelId || this.ids
      getChannel(channelId).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改店铺信息";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.channelId != null) {
            updateChannel(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addChannel(this.form).then(response => {
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
      const channelIds = row.channelId || this.ids;
      this.$modal.confirm('是否确认删除店铺信息编号为"' + channelIds + '"的数据项？').then(function() {
        return delChannel(channelIds);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('channel/channel/export', {
        ...this.queryParams
      }, `channel_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
