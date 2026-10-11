# oms-order

独立订单管理微服务，端口 9305，网关前缀 `/order`，本地数据库 `qm_oms_saas_order`。

一期支持天猫普通已付款待发货订单：从只读推送库定时拉单、按店铺和推送更新时间补拉、平台订单/业务订单/转单异常三个列表，以及需求登记和公司渠道逻辑预占。分仓、仓库预占、出库、平台库存同步和已登记需求后的订单更新不在本期。

Quartz `orderPullTask.scanAll()` 调用受内部鉴权保护的扫描入口。订单服务按公司、店铺取得数据库租约，每次读取一个有界页面再处理一批订单；同店铺串行、跨店铺最多两路执行。补拉独立分页，自动任务优先，不修改自动游标。原单落库和游标推进同事务；远程库存操作使用平台订单行稳定身份去重，订单服务失败后重试关联原结果。

`oms_order_stock_demand` 由库存服务维护。拉单解析匹配后登记需求，转单更新同一条需求的逻辑预占。渠道 `available_stock` 为最终可售投影，登记需求先保护可售，待占转预占不重复扣减。普通额度基数单独保留在 `quota_stock`；渠道锁库订单占用从 `allocated_stock` 转入 `reserved_stock`。具体公共来源按虚仓记录逻辑份额，避免重叠库存范围重复占用；此阶段不写 `wms_inventory` 或批次锁定数量。

执行 `docker/local/configure_order.py` 安装本地配置，之后构建并重启 order、inventory、goods、channel、supplychain、job、gateway。前端提供“订单”模块。推送库连接通过 `OMS_ORDER_PUSH_113_URL`、`OMS_ORDER_PUSH_113_USERNAME`、`OMS_ORDER_PUSH_113_PASSWORD` 注入；不在前端、任务日志或代码中保存密码。源表为 `sys_info.jdp_tb_trade`，按 `seller_nick,jdp_modified,tid` 增量读取，源库绝不写入。

支持两个独立推送来源：`sysinfo113` 对应 `172.16.9.113:3306/sys_info`，`sysinfo100` 对应 `172.16.9.100:3306/sys_info`。100 来源使用 `OMS_ORDER_PUSH_100_URL`、`OMS_ORDER_PUSH_100_USERNAME`、`OMS_ORDER_PUSH_100_PASSWORD`。每个店铺选择一个来源，连接池和拉取游标按来源及店铺隔离；新增来源不会切换现有店铺。配置凭据后重建订单服务容器使环境变量生效。

店铺配置默认暂停自动拉单。配置真实卖家、货主、允许虚仓和首次起点后启用。补拉范围为开始时间包含、结束时间不包含的推送更新时间；不是历史订单快照。平台SKU商品编码匹配失败时保留原单，人工修正商品资料后重试。已登记需求后的原报文业务变化会标记待处理，不自动变更业务订单或释放库存。
