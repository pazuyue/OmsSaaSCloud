# 平台单据

入口 `/oms-order/platformDocuments`，按平台订单、平台退款、平台换货分为三个页签。仅统一查询与关联展示，各自保留平台来源、转单状态、版本与原有业务操作。OMS 执行继续进入业务订单、业务退款、业务换货。接入配置、补拉表单、同步任务保留在“订单与售后接入”。

## 查询与跳转

公共条件包括店铺、平台主单号、平台子单号、平台修改时间；单号精确匹配且始终为字符串。切换类型沿用公共条件，并缓存每类独立的状态和单号筛选。“清除本类筛选”不清除公共条件；“重置公共条件”不改变各类状态筛选。

URL 参数：`type=ORDER|REFUND|EXCHANGE`、`channelId`、`platformTid`、`platformOid`、`modifiedFrom`、`modifiedTo`。`id` 指当前类型平台来源记录的本地 ID，不是平台单号或 OMS ID。旧 `/oms-order/platform`、`/platformRefunds`、`/platformExchanges` 隐藏导航但保留路由，自动转到对应页签；保留来源 ID、独立单号条件，并将旧 `tid/oid/from/to` 转为公共条件。

“原单关联”按平台主单号及可选店铺查询本地来源及其 SO、TK、HH，不要求原单已经转 SO，也不因退款或换货尚未转单而丢失来源。未匹配主单的申请仍在各自完整列表中查询。每类型最多返回最近 50 条，多于 50 条提示进入相应列表继续查询。

## 权限与性能

`GET /platform-documents/options` 返回当前账号可访问的类型及安全店铺字段；`/list?kind=...` 根据类型再次检查既有 list 权限；`/related` 只查询有权限的类型。公司身份来自登录用户，忽略客户端传入的公司或类型权限。详情、原文、转单等继续走原接口和独立权限。

初次只查询当前页签，其他页签首次切换时加载；共同条件改变后只刷新当前页签，缓存页签在重新激活时应用新条件。快速连续筛选采用请求序号，旧响应不会覆盖新结果。原单关联仅点击时加载，每类型一个有界本地 SQL，无逐行平台调用、无平台能力探测、无原文和收件信息。来源表补充公司与 TID、修改时间索引，订单行补充子单查询索引，已有等效索引复用。

## 部署与验证

先安装订单、退款、换货来源表，最后运行 `docker/local/configure_platform_documents.py` 安装查询索引及统一菜单，再更新订单服务及前端。菜单脚本保留原角色与操作权限，将具有任意一种平台 list 权限的角色关联到统一入口；不授予其他类型权限。脚本不迁移或修正已有业务单据。

后端回归：`docker/local/test_order_intake.py`。前端回归：`node tests/platform-documents.cjs`、`node tests/order-intake.cjs`。真实浏览器只读验收：`docker/local/verify_platform_documents.py`，复用保留的换货和退款测试来源；截图及结果位于 `outputs/platform-documents`。
