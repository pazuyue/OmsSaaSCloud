"""Install query-only warehouse status fields and targeted interaction change notifications."""
from pathlib import Path
import sys
from local_db import execute


def install(order, warehouse):
    # Re-runnable without rewriting any business record.
    if not execute("SHOW COLUMNS FROM oms_return_query_row LIKE 'warehouse_state'",order).strip():
        execute((Path(__file__).parent/'migrations/20261025_return_document_detail.sql').read_text('utf-8'),order)
    elif not execute("SHOW COLUMNS FROM oms_return_query_row LIKE 'receipt_difference'",order).strip():
        execute("ALTER TABLE oms_return_query_row ADD COLUMN receipt_difference TINYINT NOT NULL DEFAULT 0 COMMENT '仓库已最终回传但本来源尚未收齐，含待核对归属的差异' AFTER interaction_result",order)
    for event in ['INSERT','UPDATE']:
        name='rq_return_interaction_'+event.lower()
        execute('DROP TRIGGER IF EXISTS '+name,warehouse)
        selects=[]
        for table,kind,key in [('wms_refund_return','REFUND','plan_id'),('wms_exchange_return','EXCHANGE','exchange_id')]:
            selects.append("SELECT t.company_code,'"+kind+"' owner_kind,t."+key+" source_id FROM "+table+" t WHERE t.company_code=NEW.company_code AND t.return_sn=NEW.ticket_sn")
            selects.append("SELECT m.company_code,m.owner_kind,m.source_id FROM wms_shared_return_document d JOIN wms_shared_return_member m ON m.company_code=d.company_code AND m.parcel_id=d.parcel_id WHERE d.company_code=NEW.company_code AND d.return_sn=NEW.ticket_sn AND m.owner_kind='"+kind+"'")
        selects.append("SELECT m.company_code,'REFUND',m.plan_id FROM wms_refund_parcel_document d JOIN wms_refund_parcel_member m ON m.company_code=d.company_code AND m.parcel_id=d.parcel_id AND m.generation=d.generation WHERE d.company_code=NEW.company_code AND d.return_sn=NEW.ticket_sn")
        execute("DELIMITER $$\nCREATE TRIGGER "+name+" AFTER "+event+" ON wms_interaction_log FOR EACH ROW BEGIN IF NEW.action REGEXP '^(AFTERSALE_RETURN_|REFUND_RETURN_|RETURN_)' THEN INSERT INTO wms_return_query_dirty(company_code,owner_kind,source_id) "+' UNION '.join(selects)+" ON DUPLICATE KEY UPDATE work_version=work_version+1; END IF; END$$\nDELIMITER ;",warehouse)
    execute("INSERT INTO oms_return_query_dirty(company_code,owner_kind,owner_id) SELECT DISTINCT company_code,owner_kind,owner_id FROM oms_return_query_row ON DUPLICATE KEY UPDATE work_version=work_version+1",order)


if __name__=='__main__':
    assert sys.argv[1:] in (['--tests'],['--local'])
    install('order_workspace_test' if sys.argv[1]=='--tests' else 'qm_oms_saas_order','purchase_workspace_test' if sys.argv[1]=='--tests' else 'qm_oms_saas_commodity')
    print('Installed return document query metadata; no business commands or queue changes')
