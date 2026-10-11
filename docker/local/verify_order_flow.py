"""Local, disposable fixtures exercise Quartz -> oms-order -> inventory -> business order.
Uses the configured 113 source only for an empty bounded backfill; never writes remotely.
"""
import json, time, subprocess, hashlib
from datetime import datetime, timedelta
from local_db import execute, quote
from verify_business import request

ORDER='qm_oms_saas_order'; STOCK='qm_oms_saas_inventory'; GOODS='qm_oms_saas_commodity'; CHANNEL='qm_oms_saas_channel'
def main():
    prefix='ORDERTEST'+str(int(time.time())); sku=prefix+'A'; short=prefix+'B'; bad=prefix+'C'; channel=0; token=None
    def count(sql, database=ORDER):return int(execute(sql,database).strip())
    def wait(predicate,label):
        until=time.monotonic()+85
        next_scan=0
        while time.monotonic()<until:
            if predicate():return
            if token and time.monotonic()>=next_scan and 'still running' not in label:
                jobs=request('/schedule/job/list?invokeTarget=orderPullTask.scanAll()',token=token)['rows']
                job=next(j for j in jobs if j['invokeTarget']=='orderPullTask.scanAll()')
                if str(job['status'])=='1':request('/schedule/job/run',{'jobId':job['jobId'],'jobGroup':job['jobGroup']},token,'PUT')
                next_scan=time.monotonic()+10
            time.sleep(1)
        if channel:
            print('Fixture conversion diagnostics:',execute(f"SELECT platform_tid,conversion_status,exception_code,conversion_error,next_retry_at FROM oms_platform_order WHERE channel_id={channel}",ORDER),flush=True)
            print('Fixture demand diagnostics:',execute(f"SELECT platform_tid,sku_sn,demand_quantity,reserved_quantity,status,reason FROM oms_order_stock_demand WHERE channel_id={channel}",STOCK),flush=True)
            print('Fixture inventory diagnostics:',execute(f"SELECT sku_sn,total_stock,available_stock,reserved_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ({quote(sku)},{quote(short)},{quote(bad)})",STOCK),flush=True)
        raise AssertionError(label)
    try:
        captcha=request('/code');answer=subprocess.check_output(['docker','exec','oms-local-redis','redis-cli','--raw','GET','captcha_codes:'+captcha['uuid']],text=True).strip().strip('"')
        token=request('/auth/login',{'username':'admin','password':'admin123','uuid':captcha['uuid'],'code':answer})['data']['access_token']
        request('/channel/channel',{'channelName':prefix,'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1},token,'POST')
        channel=request('/channel/channel/list?channelName='+prefix,token=token)['rows'][0]['channelId']
        catalog=request('/order/orders/catalog',token=token)['data'];store_code=prefix+'W'
        assert any(c['channel_id']==channel for c in catalog['channels'])
        now=datetime.now();start=(now-timedelta(hours=1)).strftime('%Y-%m-%d %H:%M:%S');end=now.strftime('%Y-%m-%d %H:%M:%S')
        request('/order/orders/configs',{'channel_id':channel,'source_key':'sysinfo113','seller_nick':prefix,'enabled':0,'review_mode':'AUTO','manual_match_review':1,'auto_convert':1,'allowed_trade_types':['fixed','tmall_i18n'],'allow_public_stock':0,'interval_seconds':30,'initial_time':start},token,'POST')
        request('/order/orders/backfill',{'channel_ids':[channel],'start_time':start,'end_time':end},token,'POST')
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order_pull_task WHERE channel_id={channel} AND status='SUCCESS'")==1,'113 empty backfill did not complete')
        assert execute(f'SELECT cursor_time FROM oms_order_pull_config WHERE channel_id={channel}',ORDER).strip()==start
        sample=request('/goods/info/list?pageNum=1&pageSize=1',{},token)['rows'][0]
        def product(code):return request('/goods/info',{'skuSn':code,'goodsSn':prefix,'goodsName':code,'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':0,'validity':30},token)['data']
        product(short);product(bad)
        def seed(code,quantity,quota):
            execute(f"INSERT INTO wms_inventory(company_code,sku_sn,store_code,zp_actual_number,zp_available_number) VALUES ('QM',{quote(code)},{quote(store_code)},{quantity},{quantity}); INSERT INTO wms_inventory_batch(company_code,sku_sn,store_code,batch_code,zp_actual_number,zp_available_number) VALUES ('QM',{quote(code)},{quote(store_code)},{quote(prefix)},{quantity},{quantity}); INSERT INTO oms_inventory(company_code,sku_sn,total_stock,available_stock) VALUES ('QM',{quote(code)},{quantity},{quantity}) ON DUPLICATE KEY UPDATE total_stock=total_stock+{quantity},available_stock=available_stock+{quantity}; INSERT INTO oms_channel_inventory(company_code,sku_sn,channel_id,quota_stock,available_stock,stock_scope_json) VALUES ('QM',{quote(code)},{channel},0,0,{quote(json.dumps([store_code]))}) ON DUPLICATE KEY UPDATE quota_stock=0",STOCK)
        seed(bad,10,10)
        tids=[str(int(time.time())*100+i) for i in [1,2,3]]
        for tid,code,qty,payment in zip(tids,[prefix+'RAW',short,bad],[5,40,5],['25.00','200.00','invalid']):
            trade={'tid':tid,'status':'WAIT_SELLER_SEND_GOODS','type':'tmall_i18n','seller_nick':prefix,'oaid':'fixture-oaid','payment':payment,'orders':{'order':[{'oid':tid,'outer_sku_id':code,'num':qty,'payment':'25.00'}]}}
            payload=json.dumps({'trade_fullinfo_get_response':{'trade':trade}})
            execute(f"INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM',{channel},{quote(tid)},'WAIT_SELLER_SEND_GOODS','tmall_i18n',{quote(prefix)},NOW(),{quote(hashlib.sha256(payload.encode()).hexdigest())},{quote(payload)},'WAITING')",ORDER)
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_platform_order WHERE channel_id={channel} AND conversion_status IN ('SUCCESS','EXCEPTION')")==3,'Quartz conversion did not complete')
        assert count(f"SELECT COUNT(*) FROM oms_platform_order WHERE channel_id={channel} AND conversion_status='EXCEPTION'")==2
        assert count(f"SELECT COUNT(*) FROM oms_order_stock_demand WHERE channel_id={channel}",STOCK)==2
        assert count(f"SELECT COUNT(*) FROM oms_order WHERE channel_id={channel} AND inventory_status='WAIT_RESERVE'")==1
        product(sku);seed(sku,100,20)
        rawid=count(f"SELECT id FROM oms_platform_order WHERE channel_id={channel} AND platform_tid={quote(tids[0])}")
        detail=request(f'/order/orders/platform/{rawid}',token=token)['data']
        request(f'/order/orders/platform/{rawid}/match-product',{'processing_hash':detail['processing_hash'],'reason':'核对规格后选择已有OMS商品','lines':[{'platform_oid':tids[0],'sku_sn':sku}]},token,'POST')
        assert request(f'/order/orders/platform/{rawid}',token=token)['data']['lines'][0]['outer_sku_id']==prefix+'RAW'
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order WHERE channel_id={channel} AND inventory_status='WAIT_RESERVE'")==2,'Public stock was used despite policy disabled')
        assert count(f"SELECT SUM(reserved_quantity) FROM oms_order_stock_demand WHERE channel_id={channel}",STOCK)==0
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order_pull_config WHERE channel_id={channel} AND lease_until>NOW()")==0,'Fixture configuration is busy')
        config=next(c for c in request('/order/orders/configs',token=token)['data'] if c['channel_id']==channel)
        config['allow_public_stock']=1
        request('/order/orders/configs',config,token,'POST')
        execute(f"UPDATE oms_platform_order SET next_retry_at=NOW() WHERE channel_id={channel}",ORDER)
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order WHERE channel_id={channel} AND inventory_status='RESERVED'")==1,'SKU repair retry did not reserve')
        seed(short,40,40);execute(f"UPDATE oms_platform_order SET next_retry_at=NOW() WHERE channel_id={channel}",ORDER)
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order WHERE channel_id={channel} AND inventory_status='RESERVED'")==2,'Shortage receipt did not retry')
        assert count(f"SELECT COUNT(*) FROM oms_order_stock_demand WHERE channel_id={channel}",STOCK)==3
        assert count(f"SELECT SUM(reserved_quantity) FROM oms_order_stock_demand WHERE channel_id={channel}",STOCK)==45
        assert count(f"SELECT available_stock FROM oms_channel_inventory WHERE channel_id={channel} AND sku_sn={quote(sku)}",STOCK)==0
        assert count(f"SELECT SUM(zp_lock_number) FROM wms_inventory WHERE sku_sn IN ({quote(sku)},{quote(short)},{quote(bad)})",STOCK)==0
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order_pull_config WHERE channel_id={channel} AND lease_until>NOW()")==0,'Fixture shop still running')
        business=request('/order/orders/business/list?channelId='+str(channel),token=token)['rows']
        void_request={'orders':[{'id':r['id'],'control_version':r['control_version']} for r in business],'reason':'验收：取消未出库订单并按来源释放库存','confirm_local_void':1}
        submitted=request('/order/orders/business/void-batch',void_request,token,'POST')['data']
        assert all(r['submitted'] for r in submitted),submitted
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order WHERE channel_id={channel} AND status='VOIDED'")==2,'Order cancellation did not complete')
        assert count(f"SELECT SUM(reserved_stock) FROM oms_inventory WHERE sku_sn IN ({quote(sku)},{quote(short)})",STOCK)==0
        assert count(f"SELECT available_stock FROM oms_inventory WHERE sku_sn={quote(sku)}",STOCK)==100
        assert count(f"SELECT available_stock FROM oms_inventory WHERE sku_sn={quote(short)}",STOCK)==40
        assert count(f"SELECT COUNT(*) FROM oms_order_stock_demand WHERE channel_id={channel} AND status='CANCELLED'",STOCK)==2
        assert count(f"SELECT COUNT(*) FROM oms_order_stock_event e JOIN oms_order_stock_demand d ON d.id=e.demand_id WHERE d.channel_id={channel} AND e.action='CANCEL'",STOCK)==2
        wait(lambda:count(f"SELECT COUNT(*) FROM oms_order_pull_config WHERE channel_id={channel} AND lease_until>NOW()")==0,'Fixture shop still running')
        repeated=request('/order/orders/business/void-batch',void_request,token,'POST')['data']
        assert all(r['status']=='VOIDED' for r in repeated),repeated
        assert request('/order/orders/business/list?channelId='+str(channel)+'&documentStatus=VOIDED',token=token)['total']==2
        print('PASS live cancellation: two reserved orders voided, public stock restored, demand cancelled, repeated request idempotent')
        print('PASS live services: read-only backfill, Quartz processing, public-supplement policy disabled/enabled, unmatched SKU repair, shortage retry, one-time reservation')
    finally:
        if channel:
            execute(f"UPDATE oms_order_pull_config SET enabled=0,auto_convert=0 WHERE channel_id={channel};UPDATE oms_platform_order SET next_retry_at=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE channel_id={channel}",ORDER)
            wait(lambda:count(f"SELECT COUNT(*) FROM oms_order_pull_config WHERE channel_id={channel} AND lease_until>NOW()")==0,'Fixture shop still running')
            execute(f"DELETE e FROM oms_order_stock_event e JOIN oms_order_stock_demand d ON d.id=e.demand_id WHERE d.channel_id={channel}; DELETE s FROM oms_order_stock_source s JOIN oms_order_stock_demand d ON d.id=s.demand_id WHERE d.channel_id={channel};DELETE FROM oms_order_stock_demand WHERE channel_id={channel};DELETE FROM oms_channel_inventory WHERE channel_id={channel}",STOCK)
            for table in ['wms_inventory_change_history','wms_inventory_batch','wms_inventory','oms_inventory']:execute(f'DELETE FROM {table} WHERE company_code=\'QM\' AND sku_sn IN ({quote(sku)},{quote(short)},{quote(bad)})',STOCK)
            execute(f"DELETE l FROM oms_order_process_log l JOIN oms_platform_order p ON p.id=l.platform_order_id WHERE p.channel_id={channel};DELETE l FROM oms_platform_order_line l JOIN oms_platform_order p ON p.id=l.platform_order_id WHERE p.channel_id={channel};DELETE l FROM oms_order_line l JOIN oms_order o ON o.id=l.order_id WHERE o.channel_id={channel}",ORDER)
            for table in ['oms_order','oms_platform_order','oms_order_pull_task','oms_order_pull_config']:execute(f'DELETE FROM {table} WHERE channel_id={channel}',ORDER)
            execute(f'DELETE FROM goods_sku_sn_info WHERE sku_sn IN ({quote(sku)},{quote(short)},{quote(bad)})',GOODS)
            execute(f'DELETE FROM t_channel WHERE channel_id={channel} AND channel_name={quote(prefix)}',CHANNEL)
        if token:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
