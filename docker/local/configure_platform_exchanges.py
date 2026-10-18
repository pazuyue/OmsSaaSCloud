"""Platform exchange source layer. No business document or historical source backfill."""
from local_db import execute
from configure_order import column


def install(db):
    execute("""CREATE TABLE IF NOT EXISTS oms_platform_exchange (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
      dispute_id VARCHAR(64) NOT NULL, platform_tid VARCHAR(64) NOT NULL DEFAULT '', platform_oid VARCHAR(64) NOT NULL DEFAULT '',
      platform_status VARCHAR(64) NOT NULL DEFAULT '', source_phase VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN', speed_exchange TINYINT NULL,
      requested_quantity BIGINT NOT NULL DEFAULT 0, original_description VARCHAR(1000) NOT NULL DEFAULT '', target_description VARCHAR(1000) NOT NULL DEFAULT '',
      source_modified DATETIME NULL, platform_deadline DATETIME NULL, payload JSON NOT NULL, payload_hash CHAR(64) NOT NULL,
      source_state VARCHAR(24) NOT NULL DEFAULT 'VALID', source_error VARCHAR(500) NOT NULL DEFAULT '', version BIGINT NOT NULL DEFAULT 1,
      conversion_state VARCHAR(24) NOT NULL DEFAULT 'WAITING', conversion_error VARCHAR(500) NOT NULL DEFAULT '',
      order_id BIGINT NULL, business_exchange_id BIGINT NULL, next_convert DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      last_sync DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY platform_scope(company_code,channel_id,dispute_id), UNIQUE KEY business_scope(company_code,business_exchange_id),
      KEY listing(company_code,id), KEY conversion(company_code,channel_id,conversion_state,next_convert,id),
      KEY oid_scope(company_code,channel_id,platform_oid,id), KEY modified_scope(company_code,source_modified,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_platform_exchange_snapshot (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, platform_exchange_id BIGINT NOT NULL,
      source_modified DATETIME NULL, payload_hash CHAR(64) NOT NULL, payload JSON NOT NULL,
      outcome VARCHAR(24) NOT NULL, difference_json JSON NOT NULL, actor VARCHAR(64) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY received_version(company_code,platform_exchange_id,payload_hash), KEY history(company_code,platform_exchange_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)
    column(db, 'oms_platform_exchange_snapshot', 'actor', "VARCHAR(64) NOT NULL DEFAULT ''")
    column(db, 'oms_exchange_config', 'auto_convert', 'TINYINT NOT NULL DEFAULT 0')
    column(db, 'oms_exchange', 'platform_exchange_id', 'BIGINT NULL')
    column(db, 'oms_exchange_sync_item', 'platform_exchange_id', 'BIGINT NULL')
    for table in ['oms_order', 'oms_platform_order']:
        column(db, table, 'exchange_block_reason', "VARCHAR(500) NOT NULL DEFAULT ''")


def menus():
    from seed_business import ensure_menu
    parent = execute("SELECT menu_id FROM sys_menu WHERE path='oms-order' AND parent_id=0 LIMIT 1").strip()
    assert parent.isdigit()
    menu = ensure_menu('平台换货', int(parent), 'platformExchanges', 'oms/order/PlatformExchanges', permission='order:exchange:list', order=7)
    execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,{menu} FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms='order:exchange:list'")
    execute(f"UPDATE sys_menu SET menu_name='业务换货' WHERE parent_id={parent} AND path='exchanges'")
    for path, order in [('platformRefunds',5),('businessRefunds',6),('platformExchanges',7),('exchanges',8),('management',9),('settings',10)]:
        execute(f"UPDATE sys_menu SET order_num={order} WHERE parent_id={parent} AND path='{path}'")
    raw = ensure_menu('查看平台换货原文', menu, '', kind='F', permission='order:exchange:raw', order=1)
    execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{raw})')
    print('Platform exchange navigation installed; execution permissions unchanged')


if __name__ == '__main__':
    install('qm_oms_saas_order')
    menus()
