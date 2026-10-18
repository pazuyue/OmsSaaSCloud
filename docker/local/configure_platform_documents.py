"""One platform-document entry; preserve original permissions and deep links."""
from local_db import execute


def install(db):
    for table, indexes in {
        'oms_platform_order': [('document_tid', 'company_code,platform_tid,channel_id,id'), ('document_modified', 'company_code,source_modified,id')],
        'oms_platform_refund': [('document_oid', 'company_code,platform_oid,id'), ('document_tid', 'company_code,platform_tid,channel_id,id'), ('document_modified', 'company_code,source_modified,id')],
        'oms_platform_exchange': [('document_oid', 'company_code,platform_oid,id'), ('document_tid', 'company_code,platform_tid,channel_id,id')],
        'oms_platform_order_line': [('document_oid', 'company_code,platform_oid,platform_order_id')],
    }.items():
        for name, columns in indexes:
            # Reuse any existing index with the required leading columns.
            found = execute(f"SELECT 1 FROM (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' GROUP BY index_name) x WHERE cols='{columns}' OR cols LIKE '{columns},%' LIMIT 1", db).strip()
            if not found:
                execute(f'ALTER TABLE {table} ADD KEY {name}({columns})', db)


def menus():
    from seed_business import ensure_menu
    parent = execute("SELECT menu_id FROM sys_menu WHERE path='oms-order' AND parent_id=0 LIMIT 1").strip()
    assert parent.isdigit()
    menu = ensure_menu('平台单据', int(parent), 'platformDocuments', 'oms/order/PlatformDocuments', order=1)
    execute(f"UPDATE sys_menu SET perms='',visible='0',status='0' WHERE menu_id={menu}")
    execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,{menu} FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms IN ('order:order:list','order:refund:list','order:exchange:list')")
    execute(f"UPDATE sys_menu SET visible='1',component='oms/order/PlatformDocumentsRedirect',query='' WHERE parent_id={parent} AND path IN ('platform','platformRefunds','platformExchanges')")
    for path, order in [('business', 2), ('shipments', 3), ('exceptions', 4), ('businessRefunds', 5), ('exchanges', 6), ('management', 7), ('settings', 8)]:
        execute(f"UPDATE sys_menu SET order_num={order} WHERE parent_id={parent} AND path='{path}'")
    print('Unified platform documents installed; original operation permissions retained')


if __name__ == '__main__':
    install('qm_oms_saas_order')
    menus()
