"""Install read-only WMS log search metadata/indexes and its standalone menu."""
from local_db import execute


def install(database):
    from configure_exchanges import install_supplychain as exchange
    exchange(database)
    from configure_order import column
    column(database, 'wms_outbound_task', 'order_id', 'BIGINT NOT NULL DEFAULT 0')
    column(database, 'wms_outbound_task', 'order_sn', "VARCHAR(64) NOT NULL DEFAULT ''")
    column(database, 'wms_outbound_task', 'platform_tid', "VARCHAR(100) NOT NULL DEFAULT ''")
    indexes = {
        'wms_interaction_log': {'log_task': 'company_code,task_id,action,id', 'log_connection_time': 'company_code,connection_id,create_time,id', 'log_request': 'company_code,request_id'},
        'wms_outbound_task': {'log_order': 'company_code,order_sn,id', 'log_platform': 'company_code,platform_tid,id', 'log_external': 'company_code,external_order,id'},
        'wms_inbound_task': {'log_external': 'company_code,external_order,id'},
        'wms_outbound_receipt_event': {'log_task_post': 'company_code,task_id,post_state'},
        'wms_outbound_receipt_review': {'log_task_review': 'company_code,task_id,state'},
    }
    for table, definitions in indexes.items():
        present = {line.split('\t')[2] for line in execute('SHOW INDEX FROM ' + table, database).splitlines()}
        for name, fields in definitions.items():
            if name not in present:
                execute(f'ALTER TABLE {table} ADD INDEX {name}({fields})', database)


def menu():
    from seed_business import ensure_menu
    parent = execute("SELECT parent_id FROM sys_menu WHERE component='oms/simulationStore/index' LIMIT 1").strip()
    assert parent.isdigit(), 'Warehouse parent menu missing'
    ident = ensure_menu('仓库交互日志', int(parent), 'wmsLogs', 'oms/warehouse/WmsLogCenter', permission='warehouse:wms:log', order=9)
    execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{ident})')
    # Preserve access for roles that could already open the old unified-log dialog.
    execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,{ident} FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms='warehouse:wms:log'")


if __name__ == '__main__':
    install('qm_oms_saas_commodity')
    menu()
    print('WMS log center installed; no dispatch, stock posting or scheduler changes.')
