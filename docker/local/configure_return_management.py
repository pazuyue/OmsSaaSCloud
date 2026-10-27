"""Install return query read models only. Does not activate business jobs or queues."""
from pathlib import Path
from local_db import execute


def install_order(database, seed=False):
    execute((Path(__file__).parent/'migrations/20261025_return_query_order.sql').read_text('utf-8'),database)
    for table,kind,key in [('oms_refund','REFUND','id'),('oms_refund_return_plan','REFUND','refund_id'),('oms_refund_goods_task','REFUND','refund_id'),('oms_exchange','EXCHANGE','id')]:
        for event in ['INSERT','UPDATE','DELETE']:
            alias='OLD' if event=='DELETE' else 'NEW';name='rq_'+table.replace('oms_','')+'_'+event.lower()
            execute('DROP TRIGGER IF EXISTS '+name,database)
            execute("CREATE TRIGGER "+name+' AFTER '+event+' ON '+table+" FOR EACH ROW INSERT INTO oms_return_query_dirty(company_code,owner_kind,owner_id) VALUES (UPPER("+alias+".company_code),'"+kind+"',"+alias+'.'+key+") ON DUPLICATE KEY UPDATE work_version=work_version+1",database)
    if seed:
        for table,kind in [('oms_refund','REFUND'),('oms_exchange','EXCHANGE')]:
            execute("INSERT IGNORE INTO oms_return_query_dirty(company_code,owner_kind,owner_id) SELECT UPPER(company_code),'"+kind+"',id FROM "+table+" WHERE create_time>=DATE_SUB(NOW(),INTERVAL 90 DAY)",database)


def install_supplychain(database, seed=False):
    execute((Path(__file__).parent/'migrations/20261025_return_query_supplychain.sql').read_text('utf-8'),database)
    for table,kind,key in [('wms_refund_return','REFUND','plan_id'),('wms_exchange_return','EXCHANGE','exchange_id')]:
        for event in ['INSERT','UPDATE','DELETE']:
            alias='OLD' if event=='DELETE' else 'NEW';name='rq_'+table.replace('wms_','')+'_'+event.lower()
            execute('DROP TRIGGER IF EXISTS '+name,database)
            execute("CREATE TRIGGER "+name+' AFTER '+event+' ON '+table+" FOR EACH ROW INSERT INTO wms_return_query_dirty(company_code,owner_kind,source_id) VALUES (UPPER("+alias+".company_code),'"+kind+"',"+alias+'.'+key+") ON DUPLICATE KEY UPDATE work_version=work_version+1",database)
    if seed:
        for table,kind,key in [('wms_refund_return','REFUND','plan_id'),('wms_exchange_return','EXCHANGE','exchange_id')]:
            execute("INSERT IGNORE INTO wms_return_query_dirty(company_code,owner_kind,source_id) SELECT UPPER(company_code),'"+kind+"',"+key+' FROM '+table+" WHERE create_time>=DATE_SUB(NOW(),INTERVAL 90 DAY)",database)


if __name__=='__main__':
    import sys
    assert sys.argv[1:] in (['--tests'],['--local'],['--refresh-index'])
    if sys.argv[1]=='--refresh-index':
        execute("INSERT INTO oms_return_query_dirty(company_code,owner_kind,owner_id) SELECT DISTINCT company_code,owner_kind,owner_id FROM oms_return_query_row WHERE company_code<>'' ON DUPLICATE KEY UPDATE work_version=work_version+1",'qm_oms_saas_order')
    elif sys.argv[1]=='--tests':
        install_order('order_workspace_test');install_supplychain('purchase_workspace_test')
    else:
        install_order('qm_oms_saas_order',True);install_supplychain('qm_oms_saas_commodity',True)
    if sys.argv[1]!='--refresh-index':
        from configure_return_document_detail import install
        install('order_workspace_test' if sys.argv[1]=='--tests' else 'qm_oms_saas_order','purchase_workspace_test' if sys.argv[1]=='--tests' else 'qm_oms_saas_commodity')
    print('Installed return query projections and versioned change markers; business jobs/queues unchanged')
