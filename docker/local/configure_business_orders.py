"""Business order progress metadata. No data backfill or compatibility behavior."""
from configure_order import column
from local_db import execute

def install_order(database):
    for name, definition in {
        'review_mode': "VARCHAR(12) NOT NULL DEFAULT 'AUTO' COMMENT '新业务订单审单方式 AUTO/MANUAL'",
        'manual_match_review': "TINYINT NOT NULL DEFAULT 1 COMMENT '人工匹配商品的新订单需要人工审单'",
    }.items(): column(database, 'oms_order_pull_config', name, definition)
    column(database, 'oms_platform_order', 'fulfillment_block_reason', "VARCHAR(200) NOT NULL DEFAULT '' COMMENT '平台暂缓发货或退款等履约阻断原因'")
    for name, definition in {
        'void_reason': "VARCHAR(200) NOT NULL DEFAULT '' COMMENT '业务订单整单作废原因'",
        'void_by': "VARCHAR(64) NOT NULL DEFAULT '' COMMENT '作废申请人'",
        'void_requested_at': "DATETIME NULL COMMENT '作废申请时间'",
        'void_at': "DATETIME NULL COMMENT '库存释放确认后的作废完成时间'",
        'void_error': "VARCHAR(500) NOT NULL DEFAULT '' COMMENT '作废处理失败原因，保留等待重试'",
        'review_status': "VARCHAR(12) NOT NULL DEFAULT 'PENDING' COMMENT '审单状态 PENDING待审核/APPROVED已审核'",
        'review_by': "VARCHAR(64) NOT NULL DEFAULT '' COMMENT '审核人，自动审单记录系统'",
        'review_at': "DATETIME NULL COMMENT '审核通过时间'",
        'review_reason': "VARCHAR(200) NOT NULL DEFAULT '' COMMENT '审核依据或人工说明'",
        'review_policy': "VARCHAR(24) NOT NULL DEFAULT 'MANUAL' COMMENT '建单时审单依据 AUTO/MANUAL/MANUAL_MATCH'",
        'control_version': "BIGINT NOT NULL DEFAULT 0 COMMENT '审单及挂起并发操作版本'",
        'manual_hold': "TINYINT NOT NULL DEFAULT 0 COMMENT '人工挂起，独立于平台阻断及审单状态'",
        'hold_reason': "VARCHAR(200) NOT NULL DEFAULT '' COMMENT '人工挂起原因'",
        'hold_by': "VARCHAR(64) NOT NULL DEFAULT '' COMMENT '人工挂起操作人'",
        'hold_at': "DATETIME NULL COMMENT '人工挂起时间'",
        'stock_attempt_status': "VARCHAR(16) NOT NULL DEFAULT 'DONE' COMMENT '预占尝试状态 QUEUED/PROCESSING/DONE/FAILED'",
        'stock_attempt_at': "DATETIME NULL COMMENT '最近开始尝试预占时间'",
        'stock_error': "VARCHAR(500) NOT NULL DEFAULT '' COMMENT '最近预占处理失败原因，与缺货原因分开'",
        'reserved_at': "DATETIME NULL COMMENT '整单逻辑预占完成时间'",
    }.items(): column(database, 'oms_order', name, definition)
    column(database, 'oms_order_line', 'inventory_reason_code', "VARCHAR(40) NOT NULL DEFAULT '' COMMENT '预占等待原因代码'")
    for table, name, fields in [('oms_order', 'idx_order_void', 'company_code,channel_id,status,id'), ('oms_order', 'idx_order_review', 'company_code,review_status,id'), ('oms_order', 'idx_business_stock', 'company_code,inventory_status,create_time'), ('oms_order_line', 'idx_business_sku', 'company_code,sku_sn,order_id')]:
        if execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'", database).strip()=='0':
            execute(f'ALTER TABLE {table} ADD INDEX {name}({fields})', database)

def install_inventory(database):
    execute("ALTER TABLE oms_order_stock_source MODIFY reserved TINYINT NOT NULL DEFAULT 0 COMMENT '1当前已预占，0已释放；保留来源记录'", database)
    execute("ALTER TABLE oms_order_stock_demand MODIFY status VARCHAR(24) NOT NULL DEFAULT 'WAIT_RESERVE' COMMENT 'WAIT_RESERVE待预占/RESERVED已预占/CANCELLED已取消需求'", database)
    column(database, 'oms_order_stock_demand', 'reason_code', "VARCHAR(40) NOT NULL DEFAULT '' COMMENT '预占等待原因 PUBLIC_DISABLED/SHORTAGE/BALANCE_MISMATCH'")

def install_permissions():
    from seed_business import ensure_menu
    parents=execute("SELECT menu_id FROM sys_menu WHERE path='business' AND component='oms/order/index' ORDER BY menu_id LIMIT 1").strip()
    if not parents: raise RuntimeError('Business order menu missing')
    for index,(title,permission) in enumerate([('审核业务订单','order:order:review'),('挂起与恢复订单','order:order:hold'),('作废业务订单','order:order:void')]):
        menu=ensure_menu(title,int(parents),'',kind='F',permission=permission,order=30+index)
        execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')

if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_inventory('qm_oms_saas_inventory')
    install_permissions()
    print('Business order metadata installed')
