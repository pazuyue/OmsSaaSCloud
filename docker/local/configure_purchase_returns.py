"""Incremental purchase-return and quality schema; never rescan business or enable jobs."""
from pathlib import Path
import sys
from local_db import execute


def install(warehouse,inventory):
    from configure_warehouse_control import install as install_control
    install_control(warehouse,inventory)
    here=Path(__file__).parent/'migrations'
    execute((here/'20261025_purchase_returns.sql').read_text('utf-8'),warehouse)
    execute((here/'20261025_stock_care.sql').read_text('utf-8'),inventory)
    execute((here/'20261025_purchase_return_iteration.sql').read_text('utf-8'),warehouse)
    for name,definition in [('supplier_received_quantity',"BIGINT NOT NULL DEFAULT 0 COMMENT '供应商已凭证确认签收数量，不改变本仓库存'"),('supplier_refused_quantity',"BIGINT NOT NULL DEFAULT 0 COMMENT '供应商已凭证确认拒收数量，收回入库以此为上限'")]:
        if not execute("SHOW COLUMNS FROM purchase_return_line LIKE '"+name+"'",warehouse).strip():execute('ALTER TABLE purchase_return_line ADD COLUMN '+name+' '+definition,warehouse)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='purchase_return' AND index_name='company_created'",warehouse).strip()=='0':execute("ALTER TABLE purchase_return ADD KEY company_created(company_code,create_time,id) COMMENT '默认近90天列表与快捷待办筛选'",warehouse)
    if not execute("SHOW COLUMNS FROM oms_stock_care_line LIKE 'recovered_quantity'",inventory).strip():execute("ALTER TABLE oms_stock_care_line ADD COLUMN recovered_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '原实际出库已通过独立收回入库恢复的数量，不回滚原出库事实'",inventory)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_stock_care_line' AND index_name='sku_hold'",inventory).strip()=='0':execute("ALTER TABLE oms_stock_care_line ADD KEY sku_hold(company_code,sku_sn,document_sn) COMMENT '按当前页SKU批量汇总质量冻结'",inventory)
    for name,definition in [('attempts',"INT NOT NULL DEFAULT 0 COMMENT '后台处理次数，用于失败退避'"),('next_attempt_at',"DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次自动补偿时间，等回传的单据不轮询执行'")]:
        if not execute("SHOW COLUMNS FROM purchase_return LIKE '"+name+"'",warehouse).strip():execute('ALTER TABLE purchase_return ADD COLUMN '+name+' '+definition,warehouse)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='purchase_return' AND index_name='due_work'",warehouse).strip()=='0':execute("ALTER TABLE purchase_return ADD KEY due_work(company_code,next_attempt_at,state,id) COMMENT '只扫描到期且需要执行的任务'",warehouse)
    if not execute("SHOW COLUMNS FROM purchase_return LIKE 'receipt_review_pending'",warehouse).strip():execute("ALTER TABLE purchase_return ADD COLUMN receipt_review_pending TINYINT NOT NULL DEFAULT 0 COMMENT '已验签回传与原来源冲突，等待仓库更正；未核对前禁止释放原占用'",warehouse)
    from configure_purchase_return_fulfillment import install as install_fulfillment
    install_fulfillment(warehouse,inventory)


def menus():
    from seed_business import ensure_menu
    for path,component,title,permission,actions in [
        ('purchaseReturns','oms/purchase/PurchaseReturns','采购退仓','warehouse:purchaseReturn:query',['query','create','approve','cancel','retry','queryWarehouse','funds','continue','supplierReceipt','reclaimCreate','reclaimApprove','reclaimRetry']),
        ('quality','oms/inventory/QualityInventory','质量库存','inventory:quality:query',['query','create','review'])]:
        root='oms-inventory' if path=='quality' else 'oms-supplychain'
        parent=execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='"+root+"' LIMIT 1").strip();assert parent.isdigit()
        node=ensure_menu(title,int(parent),path,component=component,permission=permission,order=4)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{node})')
        prefix=permission.rsplit(':',1)[0]
        for action in actions:
            child=ensure_menu(title+'-'+action,node,action,kind='F',permission=prefix+':'+action)
            execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')


if __name__=='__main__':
    assert sys.argv[1:] in (['--tests'],['--local'])
    test=sys.argv[1]=='--tests'
    install('purchase_workspace_test' if test else 'qm_oms_saas_commodity','order_inventory_test' if test else 'qm_oms_saas_inventory')
    if not test:menus()
    print('Installed purchase returns and quality schema; jobs, queues, existing stock and documents unchanged')
