"""Install the virtual-warehouse execution model. Never infer business configuration."""
from local_db import execute, HERE, ROOT, docker_db
from datetime import datetime

TABLES = ['wms_connection', 'wms_inbound_task', 'wms_receipt_event', 'wms_receipt_line', 'wms_interaction_log']

def apply(db='qm_oms_saas_commodity', backup=True):
    assert db in ['qm_oms_saas_commodity', 'purchase_workspace_test', 'warehouse_workspace_test']
    if backup:
        path = ROOT / 'docker/local/backups' / ('wms-execution-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '.sql')
        with path.open('wb') as output:
            docker_db('mysqldump', '--single-transaction', '--no-tablespaces', db, output=output)
        print('Backup:', path.name)
    execute((HERE / 'migrations/20261010_wms_integration.sql').read_text(encoding='utf-8'), db)
    fields = {
        'inbound_mode': "TINYINT NULL COMMENT '虚仓入库方式：1 WMS回传、2自动虚拟；必须明确配置'",
        'outbound_mode': "TINYINT NULL COMMENT '虚仓出库方式：1 WMS回传、2自动虚拟；本期仅维护配置'",
        'connection_id': "BIGINT NULL COMMENT '真实出入库绑定的平台连接配置'",
        'external_warehouse': "VARCHAR(100) NULL COMMENT '对接平台中的仓库编码'",
        'external_owner': "VARCHAR(100) NULL COMMENT '对接平台中的货主编码'"
    }
    columns = {r.split('\t')[0] for r in execute('SHOW COLUMNS FROM wms_simulation_store_info', db).splitlines()}
    for name, ddl in fields.items():
        if name not in columns:
            execute(f'ALTER TABLE wms_simulation_store_info ADD COLUMN {name} {ddl}', db)
    # Remove superseded configuration. Existing virtual warehouses require explicit configuration.
    for table, names in [('wms_real_store_info', ['actual_warehouse']), ('owner_warehouse', ['wms_owner_code', 'is_sync'])]:
        columns = {r.split('\t')[0] for r in execute(f'SHOW COLUMNS FROM {table}', db).splitlines()}
        if table == 'owner_warehouse':
            indexes = {r.split('\t')[2] for r in execute(f'SHOW INDEX FROM {table}', db).splitlines()}
            if 'uk_real_wms_owner' in indexes:
                execute('ALTER TABLE owner_warehouse DROP INDEX uk_real_wms_owner', db)
        for name in names:
            if name in columns:
                execute(f'ALTER TABLE {table} DROP COLUMN {name}', db)
    print('Virtual execution schema ready:', db)

if __name__ == '__main__':
    apply()
