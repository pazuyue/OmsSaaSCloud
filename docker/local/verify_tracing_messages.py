"""Verify trace correlation using only a retained local simulated product channel."""
from datetime import datetime
import json
import time
from local_db import HERE,execute,quote
from verify_tracing import http,login,wait_trace


def main():
    output=HERE.parents[1]/'outputs/tracing'
    source=json.loads((output.parent/'channel-automation/case.json').read_text())
    channel=int(source['channel_id']);database='qm_oms_saas_channel'
    simulated=execute("SELECT a.simulation_mode FROM channel_platform_binding b JOIN channel_platform_app a ON a.company_code=b.company_code AND a.id=b.app_id WHERE b.company_code='QM' AND b.channel_id="+str(channel),database).strip()
    assert simulated=='1','Tracing acceptance requires a trusted simulated channel'
    token=login();stamp=datetime.now().strftime('%Y%m%d%H%M%S')
    item='96'+stamp
    payload={'num_iid':item,'title':'[链路验收] '+stamp,'approve_status':'onsale','modified':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'num':2,'skus':{'sku':[{'sku_id':'95'+stamp,'outer_id':source['sku'],'properties':'1:1','properties_name':'链路验收规格','quantity':2}]}}
    execute("INSERT INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(channel)+","+quote(item)+","+quote(json.dumps(payload,ensure_ascii=False))+")",database)
    response,source_trace=http('/channel/platformProducts/pull/'+str(channel),{'mode':'IDS','item_ids':[item]},token)
    task=int(response['data']['id']);deadline=time.monotonic()+150
    detail=None
    while time.monotonic()<deadline:
        body,_=http('/channel/messages?keyword=PRODUCTPULL'+str(task),token=token)
        rows=body['data']['rows']
        if rows:
            detail,_=http('/channel/messages/'+rows[0]['event_id'],token=token)
            detail=detail['data']
            if detail['process_state']=='SUCCESS' and any(a['stage']=='PUBLISH' for a in detail['attempts']):break
            assert detail['process_state']!='FAILED',detail.get('last_error')
        time.sleep(2)
    assert detail and detail['process_state']=='SUCCESS','Simulated message did not complete'
    assert detail['execution_mode']=='MQ',detail['execution_mode']
    assert detail['source_trace_id']==source_trace,(detail['source_trace_id'],source_trace)
    publish=next(a['sw_trace_id'] for a in detail['attempts'] if a['stage']=='PUBLISH')
    process=next(a['sw_trace_id'] for a in detail['attempts'] if a['stage']=='PROCESS')
    assert publish and process,'Missing execution traces'
    assert publish==process,'RabbitMQ publisher and consumer contexts did not connect'
    assert source_trace!=process,'Outbox should use a separate execution trace'
    spans=wait_trace(process,required_operations=('oms.message.publish/','oms.message.process/'))
    operations=[s['endpointName'] for s in spans]
    assert any('oms.message.publish/' in name for name in operations),operations
    assert any('oms.message.process/' in name for name in operations),operations
    for trace in (source_trace,process):
        body,_=http('/channel/messages?keyword='+trace,token=token)
        assert any(row['event_id']==detail['event_id'] for row in body['data']['rows'])
    output.mkdir(parents=True,exist_ok=True)
    result={'task_id':task,'event_id':detail['event_id'],'channel_id':channel,'source_trace_id':source_trace,'execution_trace_id':process,'span_count':len(spans),'checks':['trusted simulation only','source HTTP association','MQ publish/consume same trace','Outbox execution separated','technical trace search']}
    (output/'message-acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('PASS real Outbox/RabbitMQ tracing:',json.dumps(result,ensure_ascii=False))


if __name__=='__main__':main()
