"""Install platform log search tables and indexes; never backfill business history."""
from local_db import execute, HERE


def install(database):
    execute((HERE / 'migrations/20261010_channel_platform.sql').read_text('utf-8'), database)
    from configure_order import column
    fields = {
        'record_version': 'INT NOT NULL DEFAULT 0',
        'platform_code': "VARCHAR(24) NOT NULL DEFAULT 'TM'",
        'entry_kind': "VARCHAR(24) NOT NULL DEFAULT ''",
        'category': "VARCHAR(24) NOT NULL DEFAULT ''",
        'environment': "VARCHAR(24) NOT NULL DEFAULT ''",
        'direction': "VARCHAR(16) NOT NULL DEFAULT 'LOCAL'",
        'request_id': 'VARCHAR(64) NULL',
        'root_request_id': "VARCHAR(64) NOT NULL DEFAULT ''",
        'parent_request_id': "VARCHAR(64) NOT NULL DEFAULT ''",
        'operation_id': "VARCHAR(64) NOT NULL DEFAULT ''",
        'interface_name': "VARCHAR(128) NOT NULL DEFAULT ''",
        'source_service': "VARCHAR(32) NOT NULL DEFAULT 'channel'",
        'trigger_source': "VARCHAR(24) NOT NULL DEFAULT 'SYSTEM'",
        'http_status': 'INT NULL',
        'request_sent': 'TINYINT NOT NULL DEFAULT 0',
        'platform_request_id': "VARCHAR(128) NOT NULL DEFAULT ''",
        'finished_at': 'DATETIME NULL',
    }
    for key, definition in fields.items():
        column(database, 'channel_interaction_log', key, definition)
    execute("""CREATE TABLE IF NOT EXISTS channel_interaction_payload (
      company_code VARCHAR(64) NOT NULL, log_id BIGINT NOT NULL,
      request_json MEDIUMTEXT NULL, response_json MEDIUMTEXT NULL,
      request_truncated TINYINT NOT NULL DEFAULT 0, response_truncated TINYINT NOT NULL DEFAULT 0,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY(company_code,log_id), KEY expiry(create_time,log_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", database)
    execute("""CREATE TABLE IF NOT EXISTS channel_interaction_business_link (
      company_code VARCHAR(64) NOT NULL, log_id BIGINT NOT NULL,
      number_type VARCHAR(24) NOT NULL, number_value VARCHAR(128) NOT NULL,
      PRIMARY KEY(company_code,log_id,number_type,number_value),
      KEY number_lookup(company_code,number_type,number_value,log_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", database)
    present_payload = {line.split('\t')[2] for line in execute('SHOW INDEX FROM channel_interaction_payload', database).splitlines()}
    if 'company_expiry' not in present_payload:
        execute('ALTER TABLE channel_interaction_payload ADD INDEX company_expiry(company_code,create_time,log_id)', database)
    definitions = {
        'log_request': ('UNIQUE', 'company_code,request_id'),
        'log_center_time': ('', 'company_code,record_version,create_time,id'),
        'log_center_shop': ('', 'company_code,channel_id,record_version,create_time,id'),
        'log_root': ('', 'company_code,root_request_id,id'),
        'log_operation': ('', 'company_code,operation_id,id'),
    }
    present = {line.split('\t')[2] for line in execute('SHOW INDEX FROM channel_interaction_log', database).splitlines()}
    for name, (kind, fields) in definitions.items():
        if name not in present:
            execute(f'ALTER TABLE channel_interaction_log ADD {kind} INDEX {name}({fields})', database)


def install_order_lookup(database):
    """Exact cross-shop numbers need indexes without channel_id between company and number."""
    definitions = [
        ('oms_order', 'log_platform_number', 'company_code,platform_tid,id'),
        ('oms_exchange', 'log_dispute_number', 'company_code,dispute_id,id'),
        ('oms_exchange', 'log_platform_line', 'company_code,platform_oid,id'),
        ('oms_exchange', 'log_order_cases', 'company_code,order_id,id'),
    ]
    for table, name, fields in definitions:
        if not execute(f"SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}' LIMIT 1", database).strip():
            execute(f'ALTER TABLE {table} ADD INDEX {name}({fields})', database)


def menu():
    from seed_business import ensure_menu
    parent = execute("SELECT parent_id FROM sys_menu WHERE component='oms/channel/index' LIMIT 1").strip()
    assert parent.isdigit(), 'Channel parent menu missing'
    ident = ensure_menu('平台交互日志', int(parent), 'platformLogs', 'oms/channel/PlatformLogCenter', permission='channel:platform:log', order=8)
    payload = ensure_menu('查看平台脱敏报文', ident, '', kind='F', permission='channel:platform:payload', order=1)
    for key in [ident, payload]:
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{key})')
    execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,{ident} FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms='channel:platform:log'")

    if execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='platformLogTask.purgeAll()'").strip() == '0':
        execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark) VALUES ('平台交互日志保留期清理','DEFAULT','platformLogTask.purgeAll()','0 20 1 * * ?','3','1','0','admin',NOW(),'按明确公司路由分批清理到期日志；默认摘要90天、报文14天；不修改业务单据')")


if __name__ == '__main__':
    install('qm_oms_saas_channel')
    install_order_lookup('qm_oms_saas_order')
    menu()
    print('Platform log center installed; no historical backfill or platform calls')
