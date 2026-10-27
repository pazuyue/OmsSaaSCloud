"""Only add execution/parcel structures and permissions. Preserve existing business and stock facts."""
from pathlib import Path
import sys
from local_db import execute

def install(warehouse,inventory):
    execute((Path(__file__).parent/'migrations/20261026_purchase_return_fulfillment.sql').read_text('utf-8'),warehouse)
    changes={
      'purchase_return':{'fulfillment_model':"TINYINT NOT NULL DEFAULT 0 COMMENT '0原保留记录、2当前CGT多仓库执行结构；不自动回填旧业务'",'stop_key':"VARCHAR(64) NOT NULL DEFAULT '' COMMENT '本轮停止及释放幂等身份，恢复后可再次停止'"},
      'purchase_return_metadata_command':{'payload_json':"JSON NULL COMMENT '本次独立补录或关联原请求，保留每次分量与证据；旧保留记录为空'"},
      'purchase_return_event':{'execution_id':"BIGINT NOT NULL DEFAULT 0 COMMENT '原仓库执行ID，库存消息身份包含该执行'"},
      'purchase_return_reclaim_line':{'supplier_source_id':"BIGINT NULL COMMENT '明确的供应商拒收事实来源ID，同原行可分别来自多个包裹'"},
      'wms_connection':{'purchase_return_quantity_mode':"VARCHAR(16) NOT NULL DEFAULT 'INCREMENTAL' COMMENT '采购退仓回传数量口径：INCREMENTAL本次增量、CUMULATIVE带版本累计'",'purchase_return_fragment_mode':"VARCHAR(16) NOT NULL DEFAULT 'COMPLETE' COMMENT '采购退仓回传：COMPLETE完整消息、SPLIT同消息允许明细分片'"}}
    for table,fields in changes.items():
        for column,definition in fields.items():
            if not execute("SHOW COLUMNS FROM "+table+" LIKE '"+column+"'",warehouse).strip():execute('ALTER TABLE '+table+' ADD COLUMN '+column+' '+definition,warehouse)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='purchase_return_reclaim_line' AND index_name='original_line'",warehouse).strip()!='0':execute("ALTER TABLE purchase_return_reclaim_line DROP INDEX original_line, ADD KEY original_line(company_code,reclaim_id,return_line_id) COMMENT '同原行可按不同拒收来源分行，原来源另设唯一键'",warehouse)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='purchase_return_reclaim_line' AND index_name='supplier_source'",warehouse).strip()=='0':execute("ALTER TABLE purchase_return_reclaim_line ADD UNIQUE KEY supplier_source(company_code,reclaim_id,supplier_source_id) COMMENT '同单同拒收事实仅一条计划'",warehouse)
    if not execute("SHOW COLUMNS FROM oms_stock_care_line LIKE 'planned_quantity'",inventory).strip():execute("ALTER TABLE oms_stock_care_line ADD COLUMN planned_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '首次锁定的业务计划上限；恢复占用不增加原计划，累计占用及释放流水保留'",inventory)

def menus():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='purchaseReturns' AND component='oms/purchase/PurchaseReturns' LIMIT 1").strip();assert parent.isdigit()
    for action,title in [('parcel','退仓包裹核对'),('restoreApprove','退仓恢复审核')]:
        node=ensure_menu(title,int(parent),action,kind='F',permission='warehouse:purchaseReturn:'+action)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{node})')

if __name__=='__main__':
    assert sys.argv[1:] in (['--local'],['--tests']);test=sys.argv[1]=='--tests'
    install('purchase_workspace_test' if test else 'qm_oms_saas_commodity','order_inventory_test' if test else 'qm_oms_saas_inventory')
    if not test:menus()
    print('Purchase-return execution and parcel structures installed; no document or stock backfill')
