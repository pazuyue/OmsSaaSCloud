"""Verify real gateway/Feign/JDBC traces and OAP-outage availability on the local stack."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import urllib.error

from local_db import HERE

BASE='http://localhost:8088/prod-api'
OAP='http://localhost:12800/graphql'


def http(path, payload=None, token=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request(BASE+path,data=None if payload is None else json.dumps(payload).encode(),headers=headers)
    with urllib.request.urlopen(request,timeout=30) as response:
        body=json.load(response); trace=response.headers.get('X-Trace-Id')
    assert body.get('code')==200,(path,body.get('msg'))
    return body,trace


def graphql(query, variables):
    request=urllib.request.Request(OAP,data=json.dumps({'query':query,'variables':variables}).encode(),headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=20) as response:body=json.load(response)
    except urllib.error.HTTPError as error:
        raise AssertionError(error.read().decode()) from error
    assert not body.get('errors'),body.get('errors')
    return body['data']


def wait_trace(trace, minimum_services=1, required_operations=()):
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        result=graphql('query($id:ID!){queryTrace(traceId:$id){spans{serviceCode endpointName type isError tags{key value}}}}',{'id':trace})
        spans=(result.get('queryTrace') or {}).get('spans') or []
        if len({span['serviceCode'] for span in spans})>=minimum_services and all(
                any(operation in span['endpointName'] for span in spans) for operation in required_operations):
            return spans
        time.sleep(2)
    raise AssertionError('Trace was not stored by OAP: '+trace)


def login():
    captcha,_=http('/code')
    code=subprocess.check_output(['docker','exec','oms-local-redis','redis-cli','--raw','GET','captcha_codes:'+captcha['uuid']],text=True).strip().strip('"')
    auth,_=http('/auth/login',{'username':'admin','password':'admin123','uuid':captcha['uuid'],'code':code})
    return auth['data']['access_token']


def verify_agents():
    services=json.loads((HERE/'compose.yaml').read_text())['services']
    enabled=[]
    for name,spec in services.items():
        if spec.get('entrypoint',[None])[0]!='java':continue
        container=json.loads(subprocess.check_output(['docker','inspect',spec['container_name']],text=True))[0]
        environment=dict(value.split('=',1) for value in container['Config']['Env'] if '=' in value)
        assert '-javaagent:/opt/skywalking/agent/skywalking-agent.jar' in environment.get('JAVA_TOOL_OPTIONS',''),name
        assert container['State']['Health']['Status']=='healthy',name
        enabled.append({'service':name,'agent_name':environment['SW_AGENT_NAME']})
    print('PASS healthy Java services with Agent:',len(enabled),flush=True)
    return enabled


def verify_recovery(token=None):
    token=token or login()
    _,trace=http('/channel/messages?pageSize=1',token=token)
    wait_trace(trace,minimum_services=2)
    print('PASS trace reporting after OAP recovery',flush=True)
    return trace


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--outage',action='store_true',help='briefly stop OAP and verify read-only business requests; always restart')
    args=parser.parse_args()
    agents=verify_agents()
    token=login()
    # Existing read-only order/workspace endpoints call inventory/channel services through Feign.
    traces={}
    for path in ('/system/user/getInfo','/channel/messages?pageSize=1','/order/messages?pageSize=1','/order/exchanges/catalog'):
        _,trace=http(path,token=token)
        assert trace and trace!='N/A',(path,'Missing gateway trace header')
        spans=wait_trace(trace,minimum_services=2)
        services=sorted({span['serviceCode'] for span in spans})
        assert any('gateway' in service for service in services),(path,services)
        assert len(services)>=2,(path,services)
        if path.endswith('/exchanges/catalog'):
            assert any('oms-channel' in service for service in services),(path,services)
            assert any('oms-supplychain' in service for service in services),(path,services)
        traces[path]={'trace_id':trace,'services':services,'span_count':len(spans),
                      'has_jdbc':any(any(tag['key']=='db.type' for tag in span['tags']) for span in spans)}
        print('PASS gateway/downstream trace:',path,services,flush=True)
    assert any(trace['has_jdbc'] for trace in traces.values()),'No JDBC spans observed'
    if args.outage:
        command=['docker','compose','-f','compose.yaml','-f','generated/tracing.compose.json']
        try:
            subprocess.run(command+['stop','skywalking-oap'],cwd=HERE,check=True)
            for _ in range(3):http('/channel/messages?pageSize=1',token=token)
            print('PASS business reads while OAP is stopped',flush=True)
        finally:
            subprocess.run(command+['up','-d','--wait','--wait-timeout','180','skywalking-oap'],cwd=HERE,check=True)
        verify_recovery(token)
    output=HERE.parents[1]/'outputs/tracing'
    output.mkdir(parents=True,exist_ok=True)
    (output/'acceptance.json').write_text(json.dumps({'verified_at':datetime.now(timezone.utc).isoformat(),'agents':agents,'http_traces':traces,'oap_outage_verified':args.outage},indent=2)+'\n')
    print('SkyWalking UI: http://localhost:18090')


if __name__=='__main__':main()
