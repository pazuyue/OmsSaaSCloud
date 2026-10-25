"""Unified intake presentation and future task statistics; no business/cursor backfill."""
from local_db import execute
from configure_order import column


def install(database):
    for table in ['oms_order_pull_task', 'oms_refund_sync', 'oms_exchange_sync']:
        for field in ['new_count', 'updated_count', 'unchanged_count', 'failed_count']:
            column(database, table, field, 'BIGINT NULL DEFAULT NULL')
        for key, fields in [('intake_created', 'company_code,create_time,id'), ('intake_shop', 'company_code,channel_id,create_time,id')]:
            if not execute(f"SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{key}' LIMIT 1", database).strip():
                execute(f'ALTER TABLE {table} ADD KEY {key}({fields})', database)
    execute("""CREATE TABLE IF NOT EXISTS oms_exchange_sync_item (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, task_id BIGINT NOT NULL,
      dispute_id VARCHAR(64) NOT NULL, exchange_id BIGINT NULL, result VARCHAR(24) NOT NULL DEFAULT 'PENDING',
      message VARCHAR(500) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY task_scope(company_code,task_id,dispute_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", database)

    from configure_platform_exchanges import install as platform_exchanges
    platform_exchanges(database)
    from configure_platform_documents import install as platform_documents
    platform_documents(database)

def menus():
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='oms-order' AND parent_id=0 LIMIT 1").strip()
    assert parent.isdigit()
    menu=execute(f"SELECT menu_id FROM sys_menu WHERE parent_id={parent} AND path='management' LIMIT 1").strip()
    assert menu.isdigit()
    execute(f"UPDATE sys_menu SET menu_name='订单与售后接入',perms='',order_num=5 WHERE menu_id={menu}")
    execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,{menu} FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms IN ('order:order:list','order:pull:config','order:source:config','order:refund:config','order:refund:sync','order:exchange:config','order:exchange:sync')")
    # Retain permission nodes but remove the duplicate navigation entry.
    execute(f"UPDATE sys_menu SET visible='1' WHERE parent_id={parent} AND path='refundSettings'")
    from configure_order_business_settings import install as unified_settings
    unified_settings()
    print('Unified intake navigation installed; company and operation permissions remain independent')


if __name__=='__main__':
    install('qm_oms_saas_order')
    menus()
