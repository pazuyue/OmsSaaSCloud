"""Audited recovery of the one explicitly authorized, misrouted YApi test shipment.

Run in WSL with the order worker stopped. No stock, quantities, source lines or
historical inbound tasks are changed. The old test connection and logs remain.
"""
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from urllib.parse import urlparse
from local_db import execute,quote,HERE

SN='XS870a08cfdade45b5be4ad60d3cc802ab'
C='QM';ORDER='qm_oms_saas_order';WMS='qm_oms_saas_commodity'

def one(sql,database):
    fields,source=sql.split(' FROM ',1)
    raw=execute('SELECT HEX(JSON_OBJECT('+fields+')) FROM '+source,database).strip()
    assert raw and '\n' not in raw,'Expected exactly one scoped row'
    return json.loads(bytes.fromhex(raw).decode('utf8'))

def main():
    running=subprocess.run(['docker','inspect','--format','{{.State.Running}}','oms-local-order'],capture_output=True,text=True)
    assert running.returncode==0 and running.stdout.strip()=='false','Stop only the local order worker before recovery'
    target=json.loads((HERE/'logs/local-qimen-connection.json').read_text(encoding='utf8'))
    current=one("'id',d.id,'shipment_id',d.shipment_id,'state',d.state,'payload',d.payload_json,'hash',d.payload_hash,'external',d.external_order,'sn',s.shipment_sn,'status',s.status,'order_id',s.order_id FROM oms_order_shipment_dispatch d JOIN oms_order_shipment s ON s.id=d.shipment_id AND s.company_code=d.company_code WHERE d.company_code='QM' AND s.shipment_sn="+quote(SN),ORDER)
    payload=json.loads(current['payload']);old_route=payload['route'];old_id=int(old_route['connection_id']);new_id=int(target['id'])
    if old_id==new_id:
        print('Recovery already applied; original logs retained.');return
    assert current['state']=='UNKNOWN' and current['status']=='DISPATCHING' and not current['external']
    assert hashlib.sha256(current['payload'].encode('utf8')).hexdigest()==current['hash']
    original=one("'id',id,'environment',environment,'provider',provider,'endpoint',endpoint FROM wms_connection WHERE company_code='QM' AND id="+str(old_id),WMS)
    url=urlparse(original['endpoint']);assert original['environment']=='TEST' and original['provider']=='QIMEN' and url.hostname=='yapi.dtmiller.com' and url.path=='/mock/1143/qimen/order/cancel','Only the diagnosed YApi mock route is recoverable'
    new=one("'id',id,'name',name,'provider',provider,'version',api_version,'environment',environment,'endpoint',endpoint,'enabled',enabled FROM wms_connection WHERE company_code='QM' AND id="+str(new_id),WMS)
    assert new['provider']=='QIMEN' and new['version']=='2.0' and new['environment']=='TEST' and new['enabled']==1 and new['endpoint']=='http://wms-simulator:8080/qimen/gateway'
    remote=one("'id',id,'state',dispatch_state,'attempts',attempts,'external',external_order,'hash',payload_hash,'connection',connection_id FROM wms_outbound_task WHERE company_code='QM' AND shipment_sn="+quote(SN),WMS)
    assert remote['state']=='UNKNOWN' and remote['attempts']==1 and not remote['external'] and remote['hash']==current['hash'] and remote['connection']==old_id
    first=execute("SELECT HEX(response_body) FROM wms_interaction_log WHERE company_code='QM' AND ticket_sn="+quote(SN)+" AND action='OUTBOUND_CREATE' ORDER BY id",WMS).strip()
    assert '\n' not in first;reply=ET.fromstring(bytes.fromhex(first).decode('utf8'));assert reply.findtext('flag')=='success' and reply.findtext('deliveryOrderId') is None
    assert execute("SELECT COALESCE(SUM(shipped_quantity),0) FROM oms_order_shipment_line WHERE company_code='QM' AND shipment_id="+str(current['shipment_id']),ORDER).strip()=='0'
    assert execute("SELECT COUNT(*) FROM wms_outbound_receipt_event WHERE company_code='QM' AND task_id="+str(remote['id']),WMS).strip()=='0'
    assert execute("SELECT COUNT(*) FROM oms_order_shipment_outbound WHERE company_code='QM' AND shipment_id="+str(current['shipment_id']),ORDER).strip()=='0'
    assert execute("SELECT COUNT(*) FROM oms_order_shipment_virtual_outbound WHERE company_code='QM' AND shipment_id="+str(current['shipment_id']),ORDER).strip()=='0'
    source_codes=set(payload['store_codes']);assert source_codes=={'VC0001'},'Recovery only covers the diagnosed shipment stores'
    binding=execute("SELECT id,connection_id,external_warehouse,external_owner FROM wms_simulation_store_info WHERE UPPER(company_code)='QM' AND wms_simulation_code='VC0001'",WMS).strip().split('\t')
    assert int(binding[1])==old_id and binding[2]==old_route['external_warehouse'] and binding[3]==old_route['external_owner']
    payload['route']={**old_route,'connection_id':new_id,'connection_name':new['name']}
    encoded=json.dumps(payload,ensure_ascii=False,separators=(',',':'),sort_keys=True);digest=hashlib.sha256(encoded.encode('utf8')).hexdigest()
    audit=HERE/'logs/yapi-test-dispatch-recovery.json'
    assert not audit.exists(),'Review previous recovery before applying again'
    audit.write_text(json.dumps({'shipment_sn':SN,'old_connection_id':old_id,'new_connection_id':new_id,'old_payload_hash':current['hash'],'new_payload_hash':digest,'old_oms_state':current['state'],'old_wms_state':remote['state'],'quantities_unchanged':True},indent=2),encoding='utf8')
    message='已确认原目标为YApi取消接口模拟地址；迁移到独立本地奇门模拟连接并恢复创建任务，商品及库存数量不变；原连接 '+str(old_id)+'，新连接 '+str(new_id)
    # Same local MySQL instance: route, both durable snapshots and audit log commit together.
    sql='START TRANSACTION; SELECT id FROM '+ORDER+'.oms_order_shipment_dispatch WHERE id='+str(current['id'])+' FOR UPDATE; SELECT id FROM '+WMS+'.wms_outbound_task WHERE id='+str(remote['id'])+' FOR UPDATE; '
    sql+="UPDATE "+WMS+".wms_simulation_store_info SET connection_id="+str(new_id)+" WHERE UPPER(company_code)='QM' AND connection_id="+str(old_id)+" AND wms_simulation_code='VC0001'; "
    sql+='UPDATE '+WMS+'.wms_outbound_task SET connection_id='+str(new_id)+',payload_json='+quote(encoded)+',payload_hash='+quote(digest)+",dispatch_state='PENDING',attempts=0,lease_token=NULL,lease_until=NULL,last_error='',query_message='',last_query_time=NULL WHERE company_code='QM' AND id="+str(remote['id'])+'; '
    sql+='UPDATE '+ORDER+'.oms_order_shipment_dispatch SET payload_json='+quote(encoded)+',payload_hash='+quote(digest)+",state='PENDING',remote_task_id=NULL,lease_token=NULL,lease_until=NULL,last_error='',query_message='',next_attempt=NOW() WHERE company_code='QM' AND id="+str(current['id'])+'; '
    sql+='UPDATE '+ORDER+'.oms_order_shipment SET control_version=control_version+1 WHERE company_code=\'QM\' AND id='+str(current['shipment_id'])+'; '
    sql+='UPDATE '+ORDER+'.oms_order SET control_version=control_version+1 WHERE company_code=\'QM\' AND id='+str(current['order_id'])+'; '
    sql+='INSERT INTO '+ORDER+".oms_order_shipment_log(company_code,shipment_id,action,result,message,operator_name) VALUES ('QM',"+str(current['shipment_id'])+",'WMS_TEST_ROUTE_REPAIR','SUCCESS',"+quote(message)+",'授权测试修复'); "
    sql+='INSERT INTO '+WMS+".wms_interaction_log(company_code,connection_id,task_id,ticket_sn,direction,action,request_id,request_body,result,error_info) VALUES ('QM',"+str(new_id)+','+str(remote['id'])+','+quote(SN)+",'OUT','OUTBOUND_REPAIR',UUID(),'','SUCCESS',"+quote(message)+'); COMMIT;'
    execute(sql,ORDER)
    print(json.dumps({'shipment_sn':SN,'old_connection_id':old_id,'new_connection_id':new_id,'state':'PENDING','preserved_original_create_log':True,'stock_commands':0}))

if __name__=='__main__':main()
