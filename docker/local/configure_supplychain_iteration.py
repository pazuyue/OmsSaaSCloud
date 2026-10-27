"""Install supplychain query/difference schema and entries; never change jobs or queue policies."""
from pathlib import Path
import sys
from local_db import execute


def install(database):
    execute((Path(__file__).parent/'migrations/20261025_supplychain_iteration.sql').read_text('utf-8'),database)
    if not execute("SHOW COLUMNS FROM purchase_line LIKE 'stock_unit'",database).strip():
        execute("ALTER TABLE purchase_line ADD COLUMN stock_unit VARCHAR(16) NOT NULL DEFAULT '件' COMMENT '采购时库存单位快照，普通SKU为件，实物套装为套，分单位汇总'",database)
    if not execute("SHOW COLUMNS FROM wms_receipt_event LIKE 'source_log_id'",database).strip():
        execute("ALTER TABLE wms_receipt_event ADD COLUMN source_log_id BIGINT NOT NULL DEFAULT 0 COMMENT '原采购仓库回传日志ID，入账明细据此追溯原报文'",database)
    for table,name,columns in [('po_info','sc_po_due','company_key,po_state,expected_date,id'),('no_tickets','sc_receipt_due','company_key,no_state,expected_callback_time,id'),('wms_tickets_goods','sc_ticket_goods','company_code,sn,id')]:
        if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='"+table+"' AND index_name='"+name+"'",database).strip()=='0':
            execute('ALTER TABLE '+table+' ADD KEY '+name+'('+columns+") COMMENT '供应链按公司定位待办或实际商品明细'",database)


def menus():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-supplychain' LIMIT 1").strip();assert parent.isdigit()
    menu=ensure_menu('供应链待办',int(parent),'operations',component='oms/purchase/SupplychainOperations',permission='warehouse:operations:list',order=0)
    execute('INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,'+str(menu)+')')
    po=execute("SELECT menu_id FROM sys_menu WHERE parent_id="+parent+" AND path='poInfo' LIMIT 1").strip();assert po.isdigit()
    for name,permission in [('收货差异查询','warehouse:poInfo:differenceQuery'),('收货差异处理','warehouse:poInfo:differenceReview')]:
        ident=ensure_menu(name,int(po),'',kind='F',permission=permission);execute('INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,'+str(ident)+')')
    receipt=ensure_menu('到货单',int(parent),'arrivals',component='oms/purchase/Arrivals',permission='warehouse:poInfo:list',order=3)
    execute('INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,'+str(receipt)+')')


if __name__=='__main__':
    assert sys.argv[1:] in (['--tests'],['--local'])
    install('purchase_workspace_test' if sys.argv[1]=='--tests' else 'qm_oms_saas_commodity')
    if sys.argv[1]=='--local':menus()
    print('Installed supplychain iteration; original documents, inventory, jobs and queues unchanged')
