"""Build and test supplychain iteration against disposable schemas only; keep live acceptance untouched."""
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,test_jdbc_url


def main():
    from configure_supplychain_iteration import install
    from test_goods_workspace import prepare
    install('purchase_workspace_test');prepare()
    modules=['oms-modules/oms-supplychain','oms-modules/oms-goods-administration','oms-modules/oms-order'];names=['PurchaseWorkspaceTest','PurchaseCutoffTest','SupplychainIterationTest','WmsLogQueryTest','WmsOutboundServiceTest','WmsOutboundReceiptServiceTest','SalesOutboundDocumentServiceTest','ReturnDocumentReadTest','ReturnQueryWarehouseTest','SharedReturnParcelsTest','SharedReturnDispatchTest','GoodsWorkspaceTest','GoodsBundleTest','TenantRoutingTest']
    cache=Path.home()/'.cache/oms-local'
    for module in ['oms-modules/oms-common-core']+modules:shutil.copytree(ROOT/module/'src',cache/'source'/module/'src',dirs_exist_ok=True)
    env=dict(os.environ,PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),GOODS_TEST_URL=test_jdbc_url('goods_workspace_test'),ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    with (HERE/'logs/supplychain-iteration-tests.log').open('w') as log:result=subprocess.run([str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl',','.join(modules),'-am','-Dtest='+','.join(names),'-Dsurefire.failIfNoSpecifiedTests=false','package'],env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit('Supplychain tests/package failed; see supplychain-iteration-tests.log')
    reports=[]
    for module in modules:
        for file in (cache/'source'/module/'target/surefire-reports').glob('TEST-*.xml'):
            a=ET.parse(file).getroot().attrib
            if a['name'].split('.')[-1] in names:assert all(int(a.get(k,0))==0 for k in ['failures','errors','skipped']),a;reports.append({k:a[k] for k in ['name','tests','failures','errors','skipped']})
    assert len(reports)==len(names),(len(reports),names)
    out=ROOT/'outputs/supplychain-iteration';out.mkdir(exist_ok=True);total=sum(int(r['tests']) for r in reports);(out/'regression.json').write_text(json.dumps({'suites':reports,'total_tests':total},indent=2),'utf-8');print('PASS',total,'supplychain related tests')


if __name__=='__main__':main()
