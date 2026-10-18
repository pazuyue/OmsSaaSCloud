"""Order update journal and indexed current projection; no historical document rewrite."""
from configure_order import column
from local_db import execute


def install(database):
    for name, definition in {
        'update_version': 'BIGINT NOT NULL DEFAULT 0',
        'update_state': "VARCHAR(24) NOT NULL DEFAULT ''",
        'update_categories': "VARCHAR(200) NOT NULL DEFAULT ''",
        'update_summary': "VARCHAR(500) NOT NULL DEFAULT ''",
        'refund_final_pending': 'TINYINT NOT NULL DEFAULT 0',
    }.items():
        column(database, 'oms_platform_order', name, definition)
    execute("""CREATE TABLE IF NOT EXISTS oms_order_update (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL,
      platform_order_id BIGINT NOT NULL, version BIGINT NOT NULL,
      source_modified DATETIME NOT NULL, content_hash CHAR(64) NOT NULL,
      payload MEDIUMTEXT NOT NULL, differences JSON NOT NULL,
      categories VARCHAR(200) NOT NULL DEFAULT '', state VARCHAR(24) NOT NULL,
      blocking TINYINT NOT NULL DEFAULT 0, reason VARCHAR(500) NOT NULL DEFAULT '',
      cutoff_id BIGINT NULL, operator_name VARCHAR(64) NOT NULL DEFAULT '拉单任务',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, handled_at DATETIME NULL,
      UNIQUE KEY version_scope(company_code,platform_order_id,version),
      UNIQUE KEY source_content(company_code,platform_order_id,source_modified,content_hash),
      KEY history_scope(company_code,platform_order_id,id),
      KEY pending_scope(company_code,state,platform_order_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", database)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_platform_order' AND index_name='update_filter'", database).strip() == '0':
        execute('ALTER TABLE oms_platform_order ADD INDEX update_filter(company_code,update_state,id)', database)


def permissions():
    from seed_business import ensure_menu
    parent = execute("SELECT menu_id FROM sys_menu WHERE path='business' AND component='oms/order/index' ORDER BY menu_id LIMIT 1").strip()
    menu = ensure_menu('处理订单更新', int(parent), '', kind='F', permission='order:order:update', order=40)
    execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{menu})')


if __name__ == '__main__':
    install('qm_oms_saas_order')
    permissions()
    print('Order update schema and permission installed; no history or scheduler changes')
