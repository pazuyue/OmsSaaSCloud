<p align="center">
	<img alt="logo" src="https://oscimg.oschina.net/oscnet/up-b99b286755aef70355a7084753f89cdb7c9.png">
</p>
<h1 align="center" style="margin: 30px 0 30px; font-weight: bold;">RuoYi v3.6.4</h1>
<h4 align="center">基于 Vue/Element UI 和 Spring Boot/Spring Cloud & Alibaba 前后端分离的分布式微服务架构</h4>
<p align="center">
	<a href="https://gitee.com/y_project/RuoYi-Cloud/stargazers"><img src="https://gitee.com/y_project/RuoYi-Cloud/badge/star.svg?theme=dark"></a>
	<a href="https://gitee.com/y_project/RuoYi-Cloud"><img src="https://img.shields.io/badge/RuoYi-v3.6.4-brightgreen.svg"></a>
	<a href="https://gitee.com/y_project/RuoYi-Cloud/blob/master/LICENSE"><img src="https://img.shields.io/github/license/mashape/apistatus.svg"></a>
</p>

## 平台简介
OMS SAAS 平台是一个基于SpringBoot/SpringCloud & Alibaba的分布式微服务架构
* PluginFactory 基于company_model_association_info插件关联表进行 插件化开发

## 系统模块

~~~
oms-modules   
├── oms-goods-administration   商品管理服务
├── oms-supplychain   供应链管理服务
├── oms-inventory   库存管理模块
├── oms-order   订单管理服务
├── oms-common-core   公共模块
~~~

## 采购入库与仓库对接

实仓维护物理信息，货主实仓关联维护归属及启用状态。虚仓独立维护 `inbound_mode`、`outbound_mode`（1：WMS 回传；2：自动虚拟），因此同一货主、实仓下的多个虚仓可以使用不同执行方式。本期实现采购入库，出库方式仅保存配置。

真实入库虚仓需绑定可复用的仓库连接，以及平台仓库、货主编码。连接支持奇门 XML 和京东虎符 JSON；其他协议需实现 `WmsProtocol` 并登记后才能使用。审核到货单时保存执行方式和映射快照，异步下发；仓库受理后由签名回传驱动增量收货、正次品分批入账，再更新采购进度。

- `wms_connection`：平台账号、地址、协议版本及加密密钥。
- `wms_inbound_task`：下发任务、配置快照、执行状态和租约。
- `wms_receipt_event`：按平台消息 ID 去重，并检测相同 ID 内容冲突。
- `wms_receipt_line`：实际批次收货增量，每条具有稳定库存幂等键。
- `wms_interaction_log`：请求、回传、查询、取消的脱敏报文及结果。

本地初始化运行 `docker/local/migrate_wms_integration.py` 和 `docker/local/configure_wms_runtime.py`，重建 supplychain、gateway 并重新发布前端。初始化不继承实仓旧执行方式；虚仓须明确配置。部署必须提供 `OMS_WMS_ENCRYPTION_KEY`（32 字节随机值的 Base64 编码），长期妥善保存；已有密文不能通过重新生成该密钥解密。

配置入口位于供应链仓库页面“仓库对接配置”。回调入口是网关 `POST /supplychain/wmsCallback/{公司编码}/{配置回调标识}`，实际外网地址按反向代理前缀拼接。`OMS_WMS_TENANT_ROUTES` 明确指定公司到数据源的路由，例如本地 `QM=master`；多公司用逗号分隔。回调和后台任务均使用此映射，未登记的公司或数据源直接拒绝，不回退默认库。仅此入口免用户登录，仍必须通过平台签名、时间戳和仓库货主校验。报文查看权限与配置维护权限独立。

网络超时的下发进入“结果待确认”，先查询仓库，不能直接重复创建；明确业务拒绝后可以重试。已受理单据取消须等仓库确认；已有收货不能取消。回传成功表示事件已保存，入账失败可在入库单重试，不重复处理成功明细。

当前按完整明细消息接收，支持同一明细多个实际批次及多次增量收货。`totalOrderLines` 与本次明细不匹配的分片报文会明确拒收并留日志，不会提前完成。正式联调需确认平台开通版本、必填扩展字段、回传消息 ID 和分片约定；本地模拟验收不代替平台验收。

回归入口：`docker/local/test_purchase_workspace.py`、`docker/local/test_warehouse_workspace.py`。先在 WSL 运行 `python3 docker/local/wms_http_fixture.py`，再用安装了 Playwright 的 Windows Python 运行 `docker/local/verify_wms_inbound_ui.py`，验证网关、调度、库存和浏览器。验收使用独立前缀的测试数据并在结束时清理；验收结束后停止模拟接口。
