"""Initialize the owner/real-warehouse association model and its permissions."""
from local_db import HERE, execute
from seed_business import ensure_menu
from backup_database import backup

DB = 'qm_oms_saas_commodity'


def main():
    # Uniqueness and tenant identity are requirements of the current model.
    for table, code in [('owner_info', 'owner_code'), ('wms_real_store_info', 'real_store_code'), ('wms_simulation_store_info', 'wms_simulation_code')]:
        bad = execute(f"SELECT COUNT(*) FROM (SELECT UPPER(company_code),{code} FROM {table} GROUP BY UPPER(company_code),{code} HAVING COUNT(*)>1) d", DB).strip()
        assert bad == '0', f'{table}: duplicate codes must be reconciled first'
        assert execute(f"SELECT COUNT(*) FROM {table} WHERE company_code IS NULL OR TRIM(company_code)=''", DB).strip() == '0', f'{table}: missing company'
    has_relation = execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wms_simulation_store_info' AND column_name='owner_warehouse_id'", DB).strip() == '1'
    backup()
    execute((HERE / 'migrations/20261009_warehouse_workspace.sql').read_text(encoding='utf-8'), DB)
    if not has_relation:
        execute("ALTER TABLE wms_simulation_store_info ADD COLUMN owner_warehouse_id BIGINT NULL COMMENT '固定货主实仓关联', ADD INDEX idx_virtual_relation(owner_warehouse_id)", DB)

    for component, prefix in [('owner', 'warehouse:owner'), ('wmsRealStore', 'warehouse:WmsRealStoreInfo'), ('simulationStore', 'warehouse:simulationStoreInfo')]:
        menu = execute(f"SELECT menu_id FROM sys_menu WHERE component='oms/{component}/index' AND menu_type='C'").strip()
        assert menu.isdigit(), component
        for action in ['list', 'query', 'add', 'edit', 'remove', 'export']:
            target = ensure_menu(component + '-' + action, int(menu), '', kind='F', permission=prefix + ':' + action)

            execute(f"INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{target} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={target})")
    print('Warehouse association schema and current permissions ready')


if __name__ == '__main__':
    main()
