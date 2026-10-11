"""Local-only fixtures. Matching uses one disposable SKU with no warehouse stock."""
import sys,json,hashlib,time
from local_db import execute,quote
mode,channel,prefix=sys.argv[1:]
assert mode in ('create','delete') and channel.isdigit() and prefix.startswith('EXUI') and prefix[4:].isdigit()
db='qm_oms_saas_order'
assert execute(f'SELECT COUNT(*) FROM t_channel WHERE channel_id={channel} AND channel_name={quote(prefix)}','qm_oms_saas_channel').strip()=='1'
if mode=='create':
    ids=[]
    for i in range(4):
        tid=prefix[4:]+str(i)
        trade={'tid':tid,'status':'WAIT_BUYER_PAY' if i==3 else 'WAIT_SELLER_SEND_GOODS','type':'disabled_type' if i==1 else 'fixed','seller_nick':prefix,'payment':'10.00','oaid':'fixture','orders':{'order':[{'oid':tid+'1','outer_sku_id':prefix+'A','num':'invalid' if i==2 else 1,'payment':'5.00'},{'oid':tid+'2','outer_sku_id':prefix+'B','num':1,'payment':'5.00'}]}}
        payload=json.dumps({'trade_fullinfo_get_response':{'trade':trade}})
        result=execute(f"INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,source_modified,payload_hash,raw_payload) VALUES ('QM',{channel},{quote(tid)},{quote(trade['status'])},{quote(trade['type'])},{quote(prefix)},NOW(),{quote(hashlib.sha256(payload.encode()).hexdigest())},{quote(payload)});SELECT LAST_INSERT_ID()",db)
        ids.append(int(result.strip()))
    print(json.dumps(ids))
else:
    execute(f"UPDATE oms_order_pull_config SET enabled=0,auto_convert=0 WHERE company_code='QM' AND channel_id={channel}; UPDATE oms_platform_order SET next_retry_at=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE company_code='QM' AND channel_id={channel}",db)
    for attempt in range(30):
        if execute(f"SELECT COUNT(*) FROM oms_order_pull_config WHERE company_code='QM' AND channel_id={channel} AND lease_until>NOW()",db).strip()=='0':break
        time.sleep(1)
    else:raise RuntimeError('Fixture lease remains active')
    stock='qm_oms_saas_inventory';sku=prefix+'TARGET'
    assert execute(f"SELECT COUNT(*) FROM oms_order_stock_demand WHERE channel_id={channel} AND (sku_sn<>{quote(sku)} OR reserved_quantity<>0)",stock).strip()=='0'
    execute(f"DELETE e FROM oms_order_stock_event e JOIN oms_order_stock_demand d ON d.id=e.demand_id WHERE d.channel_id={channel};DELETE FROM oms_order_stock_demand WHERE channel_id={channel};DELETE FROM oms_channel_inventory WHERE company_code='QM' AND sku_sn={quote(sku)};DELETE FROM oms_inventory WHERE company_code='QM' AND sku_sn={quote(sku)}",stock)
    execute(f"DELETE l FROM oms_order_line l JOIN oms_order o ON o.id=l.order_id WHERE o.company_code='QM' AND o.channel_id={channel};DELETE FROM oms_order WHERE company_code='QM' AND channel_id={channel}",db)
    for table in ['oms_order_process_log','oms_platform_order_line']:
        execute(f"DELETE l FROM {table} l JOIN oms_platform_order p ON p.id=l.platform_order_id WHERE p.company_code='QM' AND p.channel_id={channel}",db)
    for table in ['oms_platform_order','oms_order_pull_task','oms_order_pull_config']:
        execute(f"DELETE FROM {table} WHERE company_code='QM' AND channel_id={channel}",db)
    execute(f'DELETE FROM t_channel WHERE channel_id={channel} AND channel_name={quote(prefix)}','qm_oms_saas_channel')
    execute(f'DELETE FROM goods_sku_sn_info WHERE sku_sn={quote(sku)} AND goods_sn={quote(prefix)}','qm_oms_saas_commodity')
    print('Temporary orders, demand, product and shop cleaned; no warehouse stock changed')
