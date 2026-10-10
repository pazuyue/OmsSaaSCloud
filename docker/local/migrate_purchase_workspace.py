"""Initialize the purchase workflow schema and current permissions."""
from local_db import HERE, execute
from backup_database import backup
from seed_business import ensure_menu

DB = 'qm_oms_saas_commodity'

def main():
    backup()
    execute((HERE / 'migrations/20261009_purchase_workspace.sql').read_text(encoding='utf-8'), DB)
    # SKU identity follows the existing case-sensitive goods catalog.
    collation = execute("SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='purchase_line'", DB).strip()
    if collation != 'utf8mb4_bin': execute('ALTER TABLE purchase_line CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin', DB)
    additions = {
        'supplier_info': {'status': 'INT NOT NULL DEFAULT 2'},
        'po_info': {'expected_date': 'DATE NULL', 'close_reason': 'VARCHAR(500) NULL'},
        'wms_tickets': {'inventory_status': 'INT NOT NULL DEFAULT 0'},
        'wms_tickets_goods': {'error_info': 'VARCHAR(500) NULL'},
    }
    for table, fields in additions.items():
        for field, ddl in fields.items():
            exists = execute(f"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='{table}' AND column_name='{field}'", DB).strip()
            if exists == '0': execute(f'ALTER TABLE {table} ADD COLUMN {field} {ddl}', DB)
    for component, prefix in [('supplier','warehouse:supplier'), ('poInfo','warehouse:poInfo'), ('wmsTickets','warehouse:tickets')]:
        parent = execute(f"SELECT menu_id FROM sys_menu WHERE component='oms/{component}/index' AND menu_type='C'").strip()
        assert parent.isdigit(), component
        actions = ['list','query','add','edit','remove','export']
        if component == 'poInfo': actions += ['approve','receive','close']
        if component == 'wmsTickets': actions += ['retry']
        for action in actions:
            target = ensure_menu(component+'-'+action, int(parent), '', kind='F', permission=prefix+':'+action)

            execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{target} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={target})')
    print('Purchase schema and current permissions ready')

if __name__ == '__main__': main()
