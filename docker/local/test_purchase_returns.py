"""Build and test procurement/stock-care changes against disposable databases only."""
import os,shutil,subprocess,json,time,xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,test_jdbc_url
from configure_purchase_returns import install


def main():
    install('purchase_workspace_test','order_inventory_test')
    modules=['oms-common-core','oms-supplychain','oms-inventory','oms-order']
    cache=Path.home()/'.cache/oms-local'
    # NTFS and WSL clocks can differ; copied Windows mtimes must not hide changed Java sources.
    for module in modules:
        target=cache/'source/oms-modules'/module/'src'
        shutil.copytree(ROOT/'oms-modules'/module/'src',target,dirs_exist_ok=True)
        for source in target.rglob('*.java'):os.utime(source,(time.time()+2,time.time()+2))
    suites=['StockCareTest','OrderStockTest','StockProcessTest','PurchaseReturnTest','QimenStockoutTest','PurchaseWorkspaceTest','PurchaseCutoffTest','SupplychainIterationTest','WmsOutboundServiceTest','WmsOutboundReceiptServiceTest','WmsLogQueryTest','ReturnDocumentReadTest','SharedReturnParcelsTest','SharedReturnDispatchTest','TenantRoutingTest']
    env=dict(os.environ,ORDER_STOCK_TEST_URL=test_jdbc_url('order_inventory_test'),ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    out=ROOT/'outputs/purchase-returns';out.mkdir(exist_ok=True)
    with (HERE/'logs/purchase-returns-tests.log').open('w') as log:r=subprocess.run([str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl',','.join('oms-modules/'+m for m in ['oms-supplychain','oms-inventory','oms-order']),'-am','-Dtest='+','.join(suites),'-Dsurefire.failIfNoSpecifiedTests=false','package'],env=env,stdout=log,stderr=subprocess.STDOUT)
    if r.returncode:raise SystemExit('Purchase-return tests/build failed: docker/local/logs/purchase-returns-tests.log')
    reports=[]
    for module in modules:
        for f in (cache/'source/oms-modules'/module/'target/surefire-reports').glob('TEST-*.xml'):
            a=ET.parse(f).getroot().attrib
            if a['name'].split('.')[-1] in suites:
                assert all(int(a.get(k,0))==0 for k in ['failures','errors','skipped']),a
                reports.append({k:a[k] for k in ['name','tests','failures','errors','skipped']})
    assert len(reports)==len(suites),(len(reports),suites)
    total=sum(int(a['tests']) for a in reports);(out/'regression.json').write_text(json.dumps({'total_tests':total,'suites':reports},indent=2),'utf-8');print('PASS',total,'procurement and quality regressions')


if __name__=='__main__':main()
