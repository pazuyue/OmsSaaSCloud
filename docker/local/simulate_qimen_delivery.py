"""Explicitly simulate full delivery of one local TEST shipment through signed WMS receipts.

The user must authorize the target. Never posts stock directly or notifies a sales
platform. Stable callback, message and parcel identities make reruns idempotent.
"""
import argparse
import hashlib
import json
import subprocess
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime,timedelta,timezone
from pathlib import Path
from verify_goods_workspace_ui import login,request,BASE
from wms_qimen_simulator import sign

HERE=Path(__file__).resolve().parent
OUTPUT=HERE.parents[1]/'outputs/warehouse-shipping'

def send(case,connection,credential):
    body=case['callback_body']
    params=dict(app_key=connection['app_key'],customerId=connection['customer_id'],method='taobao.qimen.deliveryorder.confirm',v='2.0',format='xml',sign_method='md5',timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'))
    params['sign']=sign(params,body,credential['secret'])
    url=BASE+'/prod-api/supplychain/wmsCallback/'+connection['company_code'].lower()+'/'+connection['callback_key']+'?'+urllib.parse.urlencode(params)
    req=urllib.request.Request(url,data=body.encode('utf8'),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
    try:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(req,timeout=30) as response:reply=ET.fromstring(response.read())
    except Exception:raise RuntimeError('模拟回传响应未确认；原报文已保留，可按原事件重试') from None
    assert reply.findtext('flag')=='success',reply.findtext('message')

def mark_simulated_delivery(case,connection):
    payload=dict(customer=connection['customer_id'],owner=case['external_owner'],warehouse=case['external_warehouse'],sn=case['shipment_sn'],external=case['external_order'],quantity=case['quantity'],body_hash=hashlib.sha256(case['callback_body'].encode('utf8')).hexdigest())
    script='''import json,sqlite3,datetime
from contextlib import closing
p=json.loads(REPLACE_PAYLOAD)
key=(p['customer'],'delivery',p['owner'],p['warehouse'],p['sn'])
with closing(sqlite3.connect('/data/documents.sqlite',timeout=10)) as db,db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE')
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row is not None and row['external_id']==p['external'] and row['quantity']==p['quantity'] and row['status'] in ('ACCEPT','DELIVERED'),'Simulation identity or quantity mismatch'
 old=db.execute("SELECT body_hash FROM events WHERE customer=? AND kind='delivery' AND sn=? AND action='DELIVERY_CONFIRM'",(p['customer'],p['sn'])).fetchall()
 assert not old or all(item['body_hash']==p['body_hash'] for item in old),'Simulation completion content changed'
 if not old:
  db.execute("UPDATE documents SET status='DELIVERED' WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?",key)
  db.execute("INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,'delivery',?,'DELIVERY_CONFIRM',?,?)",(p['customer'],p['sn'],p['body_hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
print('Local simulated warehouse delivery confirmed.')
'''.replace('REPLACE_PAYLOAD',repr(json.dumps(payload)))
    result=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode('utf8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if result.returncode:raise RuntimeError('本地模拟仓库单号、数量或完成事件不一致，已停止回传')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('shipment_sn');args=parser.parse_args()
    assert args.shipment_sn.startswith('XS') and len(args.shipment_sn)<=64
    OUTPUT.mkdir(parents=True,exist_ok=True);path=OUTPUT/(args.shipment_sn+'.json');token=login()
    try:
        rows=request('/order/orders/shipments/list?number='+urllib.parse.quote(args.shipment_sn),token=token)['rows']
        selected=[row for row in rows if row['shipment_sn']==args.shipment_sn];assert len(selected)==1
        def detail():return request('/order/orders/shipments/'+str(selected[0]['id']),token=token)['data']
        before=detail();route=before['warehouse_target'];assert route['environment']=='TEST' and route['provider']=='QIMEN'
        connection=next(c for c in request('/supplychain/wmsIntegration/connections',token=token)['data'] if c['id']==route['connection_id'])
        assert connection['environment']=='TEST' and connection['provider']=='QIMEN' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        credential=next(c for c in json.loads((HERE/'wms-simulator-credentials.json').read_text(encoding='utf8')) if c['app_key']==connection['app_key'] and c['customer_id']==connection['customer_id'])
        if path.exists():
            case=json.loads(path.read_text(encoding='utf8'));assert case['shipment_id']==before['id'] and case['external_order']==before['wms_external_order'] and case['quantity']==before['quantity']
        else:
            assert before['wms_dispatch_state']=='ACCEPTED' and before['shipped_quantity']==0 and not before.get('outbound_records') and not before.get('packages')
            plans=before['dispatch_lines'];assert plans and sum(int(line['quantity']) for line in plans)==before['quantity']
            stamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')
            case=dict(shipment_id=before['id'],shipment_sn=args.shipment_sn,order_id=before['order_id'],external_order=before['wms_external_order'],external_owner=route['external_owner'],external_warehouse=route['external_warehouse'],quantity=before['quantity'],message_id='SIM_COMPLETE_'+args.shipment_sn,package_code='SIMPKG_'+args.shipment_sn,express_code='TEST-SF-'+args.shipment_sn,outbound_time=stamp,plans=[{k:line[k] for k in ('id','sku_sn','quantity','platform_oid','store_code','batch_code')} for line in plans])
            assert case['external_order'].startswith('SIMOUT_')
            root=ET.Element('request');head=ET.SubElement(root,'deliveryOrder')
            for key,value in dict(deliveryOrderCode=args.shipment_sn,deliveryOrderId=case['external_order'],warehouseCode=case['external_warehouse'],ownerCode=case['external_owner'],status='DELIVERED',confirmType='0',outBizCode=case['message_id'],totalOrderLines=len(plans),orderConfirmTime=stamp).items():ET.SubElement(head,key).text=str(value)
            lines=ET.SubElement(root,'orderLines');totals={}
            for line in plans:
                item=ET.SubElement(lines,'orderLine')
                for key,value in dict(orderLineNo=line['id'],itemCode=line['sku_sn'],ownerCode=case['external_owner'],batchCode=line['batch_code'],inventoryType='ZP',actualQty=line['quantity']).items():ET.SubElement(item,key).text=str(value)
                totals[line['sku_sn']]=totals.get(line['sku_sn'],0)+int(line['quantity'])
            pack=ET.SubElement(ET.SubElement(root,'packages'),'package')
            for key,value in dict(packageCode=case['package_code'],expressCode=case['express_code'],logisticsCode=before['warehouse_carrier_code'],logisticsName=before['carrier_name']).items():ET.SubElement(pack,key).text=str(value)
            items=ET.SubElement(pack,'items')
            for sku,quantity in totals.items():
                item=ET.SubElement(items,'item');ET.SubElement(item,'itemCode').text=sku;ET.SubElement(item,'quantity').text=str(quantity)
            case['callback_body']=ET.tostring(root,encoding='unicode');path.write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf8')
        mark_simulated_delivery(case,connection);send(case,connection,credential)
        request('/order/orders/shipments/'+str(case['shipment_id'])+'/reconcile-receipts',token=token,method='POST')
        for attempt in range(30):
            after=detail()
            if after['status']=='SHIPPED' and after['shipped_quantity']==case['quantity']:break
            time.sleep(1)
        else:raise RuntimeError('回传已保留，出库记账尚未完成，请查看该发货单出库记录')
        records=[r for r in after['outbound_records'] if r['state']=='COMPLETED'];packages=after['packages']
        assert len(records)==1 and records[0]['quantity']==case['quantity'] and len(packages)==1 and packages[0]['express_code']==case['express_code'] and packages[0]['quantity']==case['quantity']
        # Replay the exact same signed event, then reconcile it. Stock commands must remain one.
        send(case,connection,credential);request('/order/orders/shipments/'+str(case['shipment_id'])+'/reconcile-receipts',token=token,method='POST');repeated=detail()
        assert repeated['shipped_quantity']==case['quantity'] and len(repeated['outbound_records'])==1 and len(repeated['packages'])==1
        summary=dict(shipment_sn=args.shipment_sn,quantity=case['quantity'],status=repeated['status'],shipped_quantity=repeated['shipped_quantity'],package_count=len(repeated['packages']),test_waybill=case['express_code'],completed_outbound_records=len(records),replay_idempotent=True)
        case['result']=summary;path.write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(summary,ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
