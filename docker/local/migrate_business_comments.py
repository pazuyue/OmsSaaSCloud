"""Fill missing comments in the three local business schemas, preserving all definitions.

Run in WSL: python3 docker/local/migrate_business_comments.py [--apply].
Dry runs generate reviewable SQL. Apply saves metadata and rollback SQL, skips existing
comments, and verifies column definitions, indexes and table attributes after each ALTER.
Comment meanings follow the business migrations and service code in this repository.
"""
import argparse
import json
import re
from datetime import datetime
from local_db import HERE, execute, quote

DATABASES = ('qm_oms_saas_order', 'qm_oms_saas_inventory', 'qm_oms_saas_commodity')
COMMENT = re.compile(r"\s+COMMENT\s+(?:=\s*)?'(?:[^'\\]|\\.|'')*'", re.I)
TABLE_COMMENTS = {
    'goods_import_batch': '商品导入批次，保存预览校验统计及确认导入记录',
    'goods_import_row': '商品导入预览明细，保存逐行数据快照与校验说明',
    'goods_master_lock': '公司商品主数据操作锁，协调商品维护与导入的并发操作',
    'wms_outbound_receipt_event': 'WMS出库回传事件，按消息去重并记录向订单服务提交结果',
    'wms_outbound_receipt_package': 'WMS出库回传包裹及商品明细快照，保留包裹和运单去重依据',
    'wms_outbound_receipt_review': 'WMS出库回传冲突人工核对记录，保存原回传、冲突原因及处理决定',
    'wms_outbound_waybill_lock': '公司快递运单操作锁，协调出库回传和共用运单核对',
    'wms_sales_outbound_document': '销售发货单按虚仓同步的出库执行单，关联WMS单据及来源版本',
    'wms_sales_outbound_lock': '销售发货单出库同步锁，按公司与发货单串行处理',
    'wms_sales_outbound_movement': '销售出库实际发生明细，按请求与发货行去重并保留批次追溯',
    'oms_order_warehouse_allocation': '订单分仓实物库存占用回执，保留货主、实仓、虚仓、批次及累计出库进度',
    'rule_stock_order_reservation': '订单行对锁库分货来源的占用余额及消耗、取消数量',
    'rule_stock_reservation': '锁库分货按商品、渠道与批次保存的来源余额及占用、消耗、释放数量',
    'rule_stock_reservation_event': '锁库分货订单占用、消耗和取消操作的幂等事件流水',
    'rule_stock_result': '分货单逐商品执行结果，记录分配、释放进度及来源数量汇总',
    'wms_inventory_change_history': '仓库库存变更流水，记录虚仓商品批次及正次品库存变更前后数量',
    'oms_order_allocation_config': '公司店铺订单分仓策略配置及版本',
    'oms_order_platform_shipping_config': '公司店铺平台发货模式与快递映射配置',
    'oms_order_platform_shipping_log': '平台发货任务操作日志，记录提交、查询及重试结果',
    'oms_order_platform_shipping_sim_receipt': '平台发货模拟回执，按请求保存独立的幂等确认记录',
    'oms_order_platform_shipping_task': '发货包裹平台发货任务，保存固定报文、幂等标识及重试查询进度',
    'oms_order_pull_item': '按平台单号补拉的逐单处理结果，关联补拉任务与原始订单',
    'oms_order_sales_document_sync': '发货单向销售出库单同步的报文版本、变更版本及补偿进度',
    'oms_order_shipment_outbound': '发货单虚拟出库及WMS出库回传记录，保存库存入账指令与处理结果',
    'oms_order_shipment_package': '发货单实际出库包裹，保存快递运单、出库数量及共用运单核对依据',
    'oms_order_shipment_package_line': '实际出库包裹商品明细，关联发货行与平台子订单',
    'oms_order_waybill_lock': '公司快递运单操作锁，协调订单包裹与运单的并发登记',
}

# Common meanings; business-specific meanings below take precedence.
COLUMNS = dict(line.split('=', 1) for line in """
id=记录主键ID
company_code=所属公司编码，用于租户隔离
channel_id=店铺渠道ID
channel_name=店铺渠道名称快照
sku_sn=内部商品SKU编码
goods_name=商品名称快照
order_id=OMS业务订单ID，关联oms_order.id
order_sn=OMS业务订单编号
platform_order_id=平台原始订单ID，关联oms_platform_order.id
platform_tid=平台主订单编号，以字符串保存避免精度丢失
platform_oid=平台子订单编号，以字符串保存避免精度丢失
platform_item_id=平台商品ID
platform_sku_id=平台商品SKU ID
outer_sku_id=平台商品外部SKU编码，用于匹配内部SKU
outer_iid=平台商品外部货号，用于匹配内部商品
title=平台商品标题快照
platform=订单来源平台编码
platform_status=平台订单状态
trade_type=平台交易类型
seller_nick=平台卖家账号
platform_created=平台订单创建时间
platform_modified=平台订单修改时间
source_modified=推送源记录修改时间，用于拉单增量分页
payment=实付金额，单位元
receiver_name=收货人姓名快照
receiver_phone=收货人联系电话快照
receiver_address=收货人详细地址快照
receiver_province=收货人所在省份，用于快递服务范围匹配
oaid=平台收货信息关联标识OAID
match_error=商品匹配失败原因
conversion_error=转单失败或等待原因
demand_registered=是否已登记订单库存需求：0否，1是
processing_hash=固定转单报文快照的哈希值
business_order_id=转单生成的OMS业务订单ID
create_time=记录创建时间
update_time=记录最近更新时间
modify_time=记录最近修改时间
create_user=创建人账号
confirm_user=确认人账号
confirm_time=确认时间
created_by=创建操作人或系统执行标识
updated_by=最近修改人账号
operator_id=操作人ID
operator_name=操作人账号或系统执行标识
revision=配置或业务记录版本号
version=库存记录版本号，用于并发更新控制
control_version=业务控制版本号，用于校验并发操作
enabled=是否启用：0否，1是
enabled_at=当前模式启用时间
attempts=累计处理尝试次数
cycle_attempts=当前重试周期的提交尝试次数
query_attempts=回执查询尝试次数
next_attempt=下一次处理或重试时间
next_retry_at=下一次允许重试时间
lease_token=任务执行租约标识，用于防止重复并发执行
lease_until=任务执行租约到期时间
last_error=最近一次处理失败原因
error_message=处理错误说明
message=业务处理说明或结果信息
action=业务操作类型
result=业务操作结果
state=业务处理状态
status=业务状态
reason=业务处理原因或说明
payload_json=业务报文固定快照JSON
payload_hash=业务报文哈希值，用于幂等校验
receipt_json=处理回执快照JSON
receipt_hash=回传报文哈希值，用于重复回传校验
request_id=业务请求幂等标识
request_hash=请求内容哈希值，用于幂等内容校验
request_key=请求幂等键
task_id=所属业务任务ID
quantity=本条业务记录数量
shipment_id=发货单ID，关联订单库oms_order_shipment.id
shipment_sn=发货单编号
shipment_line_id=发货单商品明细ID，关联oms_order_shipment_line.id
order_line_id=OMS业务订单明细ID，关联oms_order_line.id
package_id=出库包裹ID，关联oms_order_shipment_package.id
package_code=出库包裹编号
express_code=快递运单编号
carrier_code=OMS快递公司编码
carrier_name=OMS快递公司名称快照
warehouse_carrier_code=WMS侧快递公司编码
logistics_code=仓库回传的快递公司编码
carrier_mapping=OMS快递与平台快递编码映射JSON
owner_code=库存货主编码
store_code=虚拟仓库编码
real_store_code=实体仓库编码
real_store_name=实体仓库名称快照
batch_id=仓库库存批次ID，关联库存库wms_inventory_batch.id
batch_code=仓库库存批次编号
stock_source_id=订单库存来源ID，关联oms_order_stock_source.id
demand_id=订单库存需求ID，关联oms_order_stock_demand.id
allocation_id=订单分仓占用回执ID，关联库存库oms_order_warehouse_allocation.id
source_type=库存来源类型：LOCK渠道锁库，PUBLIC_QUOTA普通额度，PUBLIC_EXTRA公共补占
source_mode=出库来源方式：VIRTUAL虚拟出库，WMS仓库出库回传
outbound_method=占用消耗的出库方式：VIRTUAL虚拟出库，WMS仓库出库回传
outbound_quantity=累计已出库数量
outbound_shipment_sn=消耗该占用的发货单编号
outbound_time=实际出库发生时间
completed_at=业务处理完成时间
cancelled_at=业务取消时间
cancelled_quantity=累计取消的占用数量
shipped_quantity=发货行累计实际出库数量
final_confirmation=是否仓库最终出库确认：0否，1是
message_id=仓库回传消息标识，用于重复消息去重
remote_event_id=仓库服务出库回传事件ID
remote_task_id=仓库服务出库下发任务ID
external_order=WMS侧业务单号
warehouse_status=WMS侧业务状态
query_message=最近一次仓库状态查询说明
last_query_at=最近一次仓库状态查询时间
last_query_time=最近一次仓库状态查询时间
connection_id=仓库平台连接配置ID，关联wms_connection.id
external_warehouse=WMS侧仓库编码快照
external_owner=WMS侧货主编码快照
dispatch_state=向仓库下发处理状态
post_state=回传事件向订单服务提交状态
post_error=回传事件向订单服务提交失败原因
posted_at=回传事件成功提交时间
event_id=WMS出库回传事件ID，关联wms_outbound_receipt_event.id
lines_json=包裹商品明细快照JSON
waybill_slot=共用运单包裹区分标识，普通独占运单为空
shared_review_id=允许共用运单的人工核对记录ID
conflict_code=出库回传冲突原因编码
conflict_message=出库回传冲突详情
source_log_id=原始仓库交互日志ID，关联wms_interaction_log.id
decision=人工核对处理决定
decided_at=人工核对决定时间
ticket_id=WMS出入库单据ID，关联wms_tickets.id
ticket_line_id=WMS出入库商品明细ID，关联wms_tickets_goods.id
source_line_id=来源发货单明细ID，关联订单库oms_order_shipment_line.id
source_version=来源发货单快照版本号
source_hash=来源发货单快照哈希值
source_status=来源发货单状态
synced_revision=已成功同步的报文版本号
dirty_version=发货单变更标记版本号
synced_dirty_version=已完成同步的发货单变更版本号
import_batch=商品导入批次标识
total_rows=导入数据总行数
error_rows=导入校验失败行数
row_num=导入文件行号
payload=导入商品行数据快照JSON
notes=导入商品行校验说明
expected_date=采购预计到货日期
close_reason=采购单关闭原因
order_transfer_model=旧版订单转单插件关联标识
inventory_status=库存入账状态：0待处理，2成功，3异常
business_type=出入库业务类型，如PURCHASE_IN采购入库、SALE_OUT销售出库
error_info=出入库商品明细库存处理错误说明
rule_id=分货单ID，关联rule_stock_info.id
order_line=订单行业务标识，用于锁库占用来源归属
source_id=锁库分货来源ID，关联rule_stock_reservation.id
original_quantity=来源初始数量
occupied_quantity=当前已被订单占用但尚未消耗的数量
consumed_quantity=累计已出库消耗数量
released_quantity=累计已释放数量
allocated_quantity=分货执行分配数量
release_status=分货释放处理状态
release_error=分货释放失败原因
release_operator=分货释放操作人
detail_json=分货执行明细JSON
journal_request=关联库存变更流水的请求标识
run_action=分货执行动作标识
priority=渠道分货顺序优先级
allow_public_stock=是否允许超出普通渠道额度补占公共库存：0否，1是
demand_quantity=订单行销售需求数量
reserved_quantity=订单行已逻辑预占数量，不代表仓库实物占用
inventory_reason=订单行库存预占处理说明
store_codes=有效虚仓库存范围快照JSON
allocation_status=订单分仓执行状态
allocation_quantity=订单累计分仓实物库存占用数量
allocation_policy=分仓时固定的策略快照JSON
allocation_receipts=分仓占用结果回执JSON
allocation_error=最近一次分仓处理失败原因
allocation_at=最近一次分仓处理时间
policy_json=业务策略配置JSON
services_json=快递货主与实体仓服务范围JSON
generation_no=发货单生成轮次编号
carrier_match_status=发货单快递匹配状态
carrier_match_mode=发货单快递匹配方式
carrier_match_reason=发货单快递匹配结果或失败原因
carrier_attempt_at=最近一次快递匹配尝试时间
carrier_matched_at=快递匹配确认时间
carrier_matched_by=快递匹配确认操作人
carrier_snapshot=发货单快递匹配依据快照JSON
mode=业务处理模式
success_at=业务处理成功时间
platform_receipt=平台发货确认回执标识
receipt_code=模拟平台发货确认回执标识
pulled_count=从推送源拉取的记录数
inserted_count=新增登记的平台订单数
duplicate_count=重复平台订单记录数
pull_mode=补拉方式标识
requested_tids=按单号补拉的平台订单编号列表JSON
pull_source_key=提交补拉任务时固定的推送源连接标识
pull_seller_nick=提交补拉任务时固定的卖家账号
source_key=推送源数据库连接标识
host=推送源数据库主机地址
port=推送源数据库端口
database_name=推送源数据库名称
username=推送源数据库用户名
last_test_status=最近一次推送源连接测试状态
last_test_time=最近一次推送源连接测试时间
last_test_message=最近一次推送源连接测试说明
issue_id=订单业务问题ID，关联oms_order_issue.id
issue_key=问题唯一业务键，同一订单与问题域内唯一
domain=问题所属业务域
category=业务问题分类
reason_code=业务问题原因编码
scope=业务问题影响范围
stage=业务问题发生阶段
severity=业务问题严重程度
blocking=是否阻断履约：0否，1是
evidence_json=业务问题依据快照JSON
evidence_hash=业务问题依据快照哈希值
first_seen=业务问题首次发现时间
last_seen=业务问题最近发现时间
resolved_at=业务问题解除时间
acknowledged_by=业务问题确认操作人
acknowledged_at=业务问题确认时间
acknowledgement=业务问题人工确认说明
actor=事件操作人或系统执行标识
log_id=库存变更日志主键ID
inventory_type=库存品类类型标识，区分正品与次品
operation_type=库存变更操作类型
relation_sn=库存变更关联业务单号
change_quantity=本次库存变更数量
change_reason=本次库存变更原因
operation_time=库存业务操作发生时间
remark=业务备注说明
""".strip().splitlines())

for moment, moment_text in [('old', '变更前'), ('new', '变更后')]:
    for grade, grade_text in [('zp', '正品'), ('cp', '次品')]:
        for balance, balance_text in [('actual', '实际'), ('available', '可用'), ('lock', '锁定')]:
            COLUMNS[f'{moment}_{grade}_{balance}_number'] = moment_text + grade_text + balance_text + '库存数量'

OVERRIDES = {
    'goods_import_batch': {'status': '商品导入批次状态：PREVIEW预览，COMPLETED已确认导入'},
    'supplier_info': {'status': '供应商启用状态'},
    'oms_order_stock_source': {'quantity': '该库存来源承担的订单需求数量'},
    'oms_order_stock_demand': {'owner_code': '库存需求货主编码，逻辑登记阶段可为空，分仓时确定',
                               'store_codes': '有效虚仓库存范围快照JSON，逻辑登记阶段可为空'},
    'oms_order_stock_event': {'quantity': '本次订单库存登记或逻辑预占数量'},
    'oms_order_warehouse_allocation': {'quantity': '本条分仓回执占用的实物库存数量',
                                      'status': '仓库占用回执状态'},
    'oms_order_allocation_config': {'policy_json': '店铺订单分仓策略JSON'},
    'oms_order_carrier_config': {'policy_json': '店铺允许快递顺序与实仓省份匹配策略JSON'},
    'oms_order_platform_shipping_config': {'mode': '店铺平台发货模式：OFF关闭，SIMULATION模拟'},
    'oms_order_platform_shipping_task': {'mode': '任务创建时固定的平台发货模式',
                                         'quantity': '本次平台发货包裹商品总数量'},
    'oms_order_pull_item': {'task_id': '补拉任务ID，关联oms_order_pull_task.id'},
    'oms_order_platform_shipping_log': {'task_id': '平台发货任务ID，关联oms_order_platform_shipping_task.id'},
    'wms_outbound_receipt_event': {'task_id': 'WMS出库下发任务ID，关联wms_outbound_task.id',
                                  'quantity': '本次仓库出库回传确认的商品总数量'},
    'wms_outbound_receipt_package': {'task_id': 'WMS出库下发任务ID，关联wms_outbound_task.id'},
    'wms_outbound_receipt_review': {'task_id': 'WMS出库下发任务ID，关联wms_outbound_task.id',
                                   'reason': '人工核对处理理由', 'state': '出库回传人工核对处理状态'},
    'oms_order_shipment': {'quantity': '发货单计划出库商品总数量', 'status': '发货单履约状态'},
    'oms_order_shipment_line': {'quantity': '发货行计划出库数量'},
    'oms_order_shipment_package': {'quantity': '包裹实际出库商品总数量',
                                  'outbound_id': '发货单出库记录ID，关联oms_order_shipment_outbound.id'},
    'oms_order_shipment_package_line': {'quantity': '包裹中该发货行的实际出库数量'},
    'oms_order_shipment_outbound': {'quantity': '本次出库确认的商品总数量'},
    'oms_order_virtual_outbound': {'quantity': '本次库存出库入账的商品总数量'},
    'oms_order_shipment_virtual_outbound': {'quantity': '本次虚拟出库指令的商品总数量'},
    'oms_order_line': {'quantity': '业务订单商品购买数量'},
    'oms_platform_order_line': {'quantity': '平台子订单商品购买数量'},
    'wms_sales_outbound_movement': {'quantity': '本次销售出库实际发生数量'},
    'rule_stock_reservation_event': {'quantity': '本次锁库占用、消耗或取消数量'},
    'oms_order_sales_document_sync': {'revision': '当前待同步报文版本号'},
    'oms_order_issue': {'state': '业务问题处理状态'},
}


def identifier(name):
    return '`' + name.replace('`', '``') + '`'


def rows(sql, database):
    return [json.loads(line) for line in execute(sql, database).splitlines()]


def inventory(database):
    tables = rows("SELECT JSON_OBJECT('table',TABLE_NAME,'comment',TABLE_COMMENT) "
                  "FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() "
                  "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME", database)
    columns = rows("SELECT JSON_OBJECT('table',TABLE_NAME,'column',COLUMN_NAME,'comment',COLUMN_COMMENT) "
                   "FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() "
                   "ORDER BY TABLE_NAME,ORDINAL_POSITION", database)
    return tables, columns


def metadata(database, table):
    ddl = execute('SHOW CREATE TABLE ' + identifier(table), database).split('\t', 1)[1].strip()
    definitions = {}
    for line in ddl.splitlines():
        match = re.match(r'\s*`([^`]+)`\s+(.+)', line)
        if match:
            definitions[match.group(1)] = match.group(2).rstrip(',')
    attributes = rows("SELECT JSON_OBJECT('engine',ENGINE,'format',ROW_FORMAT,'collation',TABLE_COLLATION,"
                      "'options',CREATE_OPTIONS,'comment',TABLE_COMMENT) FROM information_schema.tables "
                      "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=" + quote(table), database)[0]
    indexes = execute('SHOW INDEX FROM ' + identifier(table), database)
    # Exclude only estimated cardinality, which can change with ongoing business activity.
    indexes = ['\t'.join(parts[:6] + parts[7:]) for parts in
               (line.split('\t') for line in indexes.splitlines())]
    columns = rows("SELECT JSON_OBJECT('column',COLUMN_NAME,'comment',COLUMN_COMMENT) "
                   "FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="
                   + quote(table) + " ORDER BY ORDINAL_POSITION", database)
    return {'ddl': ddl, 'definitions': definitions, 'attributes': attributes,
            'indexes': indexes, 'comments': {r['column']: r['comment'] for r in columns}}


def alter(table, changes):
    return ('ALTER TABLE ' + identifier(table) + '\n  '
            + ',\n  '.join(changes + ['ALGORITHM=INPLACE', 'LOCK=NONE']) + ';')


def main(apply=False):
    plan = []
    missing_meanings = []
    for database in DATABASES:
        tables, columns = inventory(database)
        if not tables:
            raise RuntimeError(database + ': expected local business schema has no tables')
        for table in tables:
            name = table['table']
            wanted = {}
            for column in columns:
                if column['table'] != name or column['comment'].strip():
                    continue
                col = column['column']
                text = OVERRIDES.get(name, {}).get(col, COLUMNS.get(col))
                if not text:
                    missing_meanings.append(database + '.' + name + '.' + col)
                else:
                    wanted[col] = text
            table_comment = None
            if not table['comment'].strip():
                table_comment = TABLE_COMMENTS.get(name)
                if not table_comment:
                    missing_meanings.append(database + '.' + name + ' TABLE COMMENT')
            if wanted or table_comment:
                info = metadata(database, name)
                changes = ['MODIFY COLUMN ' + identifier(c) + ' '
                           + COMMENT.sub('', info['definitions'][c]) + ' COMMENT ' + quote(text)
                           for c, text in wanted.items()]
                undo = ['MODIFY COLUMN ' + identifier(c) + ' ' + info['definitions'][c] for c in wanted]
                if table_comment:
                    changes.append('COMMENT=' + quote(table_comment))
                    undo.append('COMMENT=' + quote(table['comment']))
                plan.append({'database': database, 'table': name, 'before': info,
                             'columns': wanted, 'table_comment': table_comment,
                             'sql': alter(name, changes), 'rollback': alter(name, undo)})
    if missing_meanings:
        raise RuntimeError('Review business meanings before any DDL:\n' + '\n'.join(missing_meanings))
    sql = '-- Fill missing comments only; preserve existing comments, data and definitions.\n'
    for database in DATABASES:
        sql += '\nUSE ' + identifier(database) + ';\nSET SESSION lock_wait_timeout=10;\n\n'
        sql += '\n\n'.join(p['sql'] for p in plan if p['database'] == database) + '\n'
        print(database, 'tables needing changes:', sum(p['database'] == database for p in plan),
              'table comments:', sum(bool(p['table_comment']) for p in plan if p['database'] == database),
              'column comments:', sum(len(p['columns']) for p in plan if p['database'] == database))
    destination = HERE / 'migrations/20261014_business_comments.sql'
    # Preserve the original migration after successful idempotent reruns.
    if plan or not destination.exists():
        destination.write_text(sql, encoding='utf-8')
    if not apply:
        print('DRY RUN; review migration:', destination)
        return
    if not plan:
        print('Already complete; no DDL needed')
        return
    folder = HERE / 'backups'
    folder.mkdir(exist_ok=True)
    stem = 'business-comments-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    (folder / (stem + '.json')).write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding='utf-8')
    rollback = folder / (stem + '-rollback.sql')
    rollback.write_text('\n\n'.join('USE ' + identifier(p['database'])
                        + ';\nSET SESSION lock_wait_timeout=10;\n' + p['rollback']
                        for p in reversed(plan)) + '\n', encoding='utf-8')
    print('Rollback SQL:', rollback, flush=True)
    for item in plan:
        database, table, before = item['database'], item['table'], item['before']
        current = metadata(database, table)
        # AUTO_INCREMENT may advance as services insert rows; it is not altered here.
        if any(current[key] != before[key] for key in ('definitions', 'attributes', 'indexes', 'comments')):
            raise RuntimeError(database + '.' + table + ': schema changed since planning; rerun')
        execute('SET SESSION lock_wait_timeout=10;\n' + item['sql'], database)
        after = metadata(database, table)
        assert {c: COMMENT.sub('', d) for c, d in before['definitions'].items()} == {
            c: COMMENT.sub('', d) for c, d in after['definitions'].items()}, table + ': definition changed'
        assert before['indexes'] == after['indexes'], table + ': indexes changed'
        expected_attributes = dict(before['attributes'])
        if item['table_comment']:
            expected_attributes['comment'] = item['table_comment']
        assert after['attributes'] == expected_attributes, table + ': unexpected table attribute change'
        expected_comments = dict(before['comments'], **item['columns'])
        assert after['comments'] == expected_comments, table + ': unexpected column comment change'
        print('PASS', database + '.' + table, 'definitions/indexes/existing comments preserved', flush=True)
    for database in DATABASES:
        tables, columns = inventory(database)
        assert not any(not t['comment'].strip() for t in tables), database + ': missing table comments'
        assert not any(not c['comment'].strip() for c in columns), database + ': missing column comments'
    print('COMPLETE; all three schemas have table and column comments')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    main(parser.parse_args().apply)
