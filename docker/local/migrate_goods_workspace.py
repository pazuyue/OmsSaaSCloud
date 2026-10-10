"""Add master-data coordination and import state without changing existing goods or stock."""
from local_db import HERE, execute
from seed_business import ensure_menu

DB = 'qm_oms_saas_commodity'

def main():
    assert list((HERE / 'backups').glob('oms-saas-complete-*.sql')), 'Create a backup first'
    sql = (HERE / 'migrations/20261009_goods_workspace.sql').read_text(encoding='utf-8')
    execute(sql.split('-- Apply once')[0], DB)
    if execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='goods_size' AND column_name='sort_order'", DB).strip() == '0':
        execute("ALTER TABLE goods_size ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '尺码业务排序，越小越靠前'", DB)
    for kind in ['info', 'category', 'color', 'size']:
        menu = execute(f"SELECT menu_id FROM sys_menu WHERE component='oms/goods/{kind}/index' AND menu_type='C'").strip()
        assert menu.isdigit(), kind
        for action in ['list', 'query', 'add', 'edit', 'remove', 'export'] + (['import'] if kind == 'info' else []):
            permission = f'goods:{kind}:{action}'
            target = ensure_menu('商品' + kind + '-' + action, int(menu), '', kind='F', permission=permission)

            execute(f"INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{target} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={target})")
    print('Goods workspace schema and equivalent permissions applied; existing goods and stock preserved')

if __name__ == '__main__':
    main()
