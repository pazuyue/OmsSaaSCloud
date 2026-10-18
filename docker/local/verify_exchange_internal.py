"""Synthetic internal replacement acceptance, with an actual order-update TID pull.
Leaves the replacement ready for fulfillment; does not send platform or WMS writes.
"""
import json
import subprocess
from datetime import datetime
from pathlib import Path
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
import verify_allocation_rules_v2 as fixture

OUT=Path(__file__).resolve().parents[2]/'outputs/exchanges'
ORDER='qm_oms_saas_order'


def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')


def main():
    OUT.mkdir(parents=True,exist_ok=True);manifest=OUT/'internal-case.json'
    if manifest.exists():c=json.loads(manifest.read_text(encoding='utf-8'))
    else:
        stamp=datetime.now().strftime('%Y%m%d%H%M%S');tid='996'+stamp
        c={'case_id':stamp,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':'EXCH_I_A_'+stamp,'target_sku':'EXCH_I_B_'+stamp,'channel_name':'[换货验收] 未发货协商换品 '+stamp,'seller':'EXCH_I_'+stamp,'platform_tid':tid,'platform_oid':tid+'01','warehouses':[{'code':'EXCH_IV_'+stamp,'quantity':10,'package_count':0}]};save(manifest,c)
    assert c['sku'].startswith('EXCH_I_')
    token=login()
    def api(path,body=None):return request(path,payload=body,token=token,method='POST' if body is not None else 'GET')
    def order(id=None):return api('/order/orders/business/'+str(id or c['order_id']))['data']
    def exchange():return api('/order/exchanges/'+str(c['exchange_id']))['data']
    def act(action,body=None):return api('/order/exchanges/'+str(c['exchange_id'])+'/'+action,{'control_version':exchange()['control_version'],**(body or {})})['data']
    def report():return api('/order/orders/platform/'+str(c['platform_id'])+'/updates')['data']
    try:
        fixture.MANIFEST=manifest
        if not c.get('prepared'):
            fixture.prepare_case(c,token);c['prepared']=True;save(manifest,c)
        if not c.get('old_allocated'):
            o=order();r=api('/order/orders/business/allocate-batch',{'items':[{'id':o['id'],'control_version':o['control_version']}]})['data'];assert r[0]['success'],r
            c['old_shipments']=[s['shipment_sn'] for s in order()['shipments']];c['old_allocated']=True;save(manifest,c)
        if not c.get('target_ready'):
            sample=api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
            if not sql("SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)='QM' AND sku_sn="+quote(c['target_sku']),'qm_oms_saas_commodity'):
                api('/goods/info',{'skuSn':c['target_sku'],'goodsSn':c['target_sku'],'goodsName':'[换货验收] 协商换出商品','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':1,'validity':30})
            payload={'relationSn':'TEST-EXCH-I-'+c['case_id'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['warehouses'][0]['code'],'skuSn':c['target_sku'],'batchCode':'EXCH_I_'+c['case_id'],'zpActualNumber':10,'zpAvailableNumber':10,'transactionPrice':2.5}}
            r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(payload).encode(),capture_output=True,timeout=40);assert r.returncode==0 and json.loads(r.stdout)['code']==200
            scope=json.dumps([c['warehouses'][0]['code']]);sql(f"INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{c['channel_id']},{quote(c['target_sku'])},10,{quote(scope)})",'qm_oms_saas_inventory')
            c['target_ready']=True;save(manifest,c)
        if not c.get('pulled'):
            source=json.loads((OUT.parent/'order-updates/cases.json').read_text(encoding='utf-8'))
            config=next(x for x in api('/order/orders/configs')['data'] if x['channel_id']==c['channel_id']);api('/order/orders/configs',{**config,'source_key':source['source_key'],'reset_cursor':1})
            payload=json.loads(sql("SELECT raw_payload FROM oms_platform_order WHERE company_code='QM' AND id="+str(c['platform_id']),ORDER));trade=payload['trade_fullinfo_get_response']['trade'];modified=datetime.now().strftime('%Y-%m-%d %H:%M:%S');trade['modified']=modified;trade['orders']['order'][0]['outer_sku_id']=c['target_sku']
            body=json.dumps(payload,ensure_ascii=False)
            sql('INSERT INTO jdp_tb_trade(tid,status,type,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(quote(v) for v in [c['platform_tid'],'WAIT_SELLER_SEND_GOODS','fixed',c['seller'],c['created'],modified,modified,body])+') ON DUPLICATE KEY UPDATE jdp_response=VALUES(jdp_response),modified=VALUES(modified),jdp_modified=VALUES(jdp_modified)','oms_order_update_acceptance')
            task=api('/order/orders/backfill',{'mode':'TID','channel_ids':[c['channel_id']],'tids':[c['platform_tid']],'request_key':'EXCHANGE_UPDATE_'+c['case_id']})['data']['task_ids'][0]
            wait_for(lambda:api('/order/orders/tasks/'+str(task))['data'],lambda t:t['status'] in ('SUCCESS','PARTIAL'),'Order update TID pull incomplete')
            assert report()['change_pending']==1;c['pulled']=True;save(manifest,c)
        if not c.get('exchange_id'):
            c['exchange_id']=api('/order/exchanges',{'order_id':c['order_id'],'platform_oid':c['platform_oid'],'quantity':10,'target_sku':c['target_sku'],'reason':'[本地验收] 买家已确认全量换规格，关联平台订单商品变化','request_key':'INTERNAL_EXCHANGE_'+c['case_id']})['data']['id'];save(manifest,c)
        e=exchange()
        if not e.get('approved_version'):
            differences=[d['difference_hash'] for d in report()['differences'] if d['category']=='GOODS' and d['field']=='outer_sku_id'];assert len(differences)==1
            act('review',{'target_sku':c['target_sku'],'reason':'[本地验收] 全量换规格、原未发货 XS 先撤回','difference_hashes':differences})
            assert all(x['state']=='LINKED' for x in exchange()['update_links']) and report()['change_pending']==1
        for _ in range(6):
            if exchange().get('fulfillment_order_id'):break
            act('advance')
        e=wait_for(exchange,lambda e:bool(e.get('fulfillment_order_id')),'Internal replacement was not materialized')
        old=order();new=order(e['fulfillment_order_id']);r=report()
        assert old['lines'][0]['sku_sn']==c['sku'] and old['lines'][0]['quantity']==10 and old['lines'][0]['cancelled_quantity']==10
        assert old['shipped_quantity']==0 and all(s['status']=='VOIDED' for s in old['shipments'])
        assert new['stock_reference']=='EXCHANGE:'+str(c['exchange_id']) and new['lines'][0]['sku_sn']==c['target_sku'] and new['reserved_quantity']==10
        assert r['change_pending']==0 and all(x['state']=='APPLIED' for x in e['update_links'])
        # Check that the independent new demand can enter the normal warehouse allocation flow.
        if new['allocation_status']!='FULL':
            result=api('/order/orders/business/allocate-batch',{'items':[{'id':new['id'],'control_version':new['control_version']}]})['data'];assert result[0]['success'],result
        new=order(new['id']);assert new['allocation_status']=='FULL' and all(s['wms_dispatch_state']=='NONE' for s in new['shipments'])
        c.update(completed=True,replacement_order_id=new['id'],replacement_order_sn=new['order_sn'],exchange_sn=e['exchange_sn'],business_url=BASE+'/oms-order/exchanges?exchangeId='+str(e['id']));save(manifest,c)
        save(OUT/'internal-verification.json',{'original_quantity':10,'original_cancelled_quantity':10,'original_outbound_quantity':0,'replacement_reserved_quantity':10,'replacement_allocated_quantity':10,'exact_goods_difference_applied':True,'raw_trade_preserved':True,'warehouse_dispatch':False,'real_platform_writes':False,'exchange_sn':e['exchange_sn']})
        print(json.dumps({'exchange_id':e['id'],'exchange_sn':e['exchange_sn'],'replacement_order_sn':new['order_sn'],'exact_goods_difference_applied':True,'warehouse_dispatch':False},ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
