"""Build and run carrier regressions in a disposable schema; no business fixtures are changed."""
import os,shutil,subprocess,time,json,xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,execute,test_jdbc_url


def main():
    from configure_purchase_returns import install as base_install
    from configure_warehouse_control import install as control_install
    from configure_shared_carriers import install as carrier_install
    base_install('purchase_workspace_test','order_inventory_test')
    control_install('purchase_workspace_test','order_inventory_test')
    carrier_install('order_workspace_test','purchase_workspace_test')
    database='carrier_workspace_test'
    execute('CREATE DATABASE IF NOT EXISTS '+database+' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin')
    from configure_shared_carriers import install_model
    install_model(database)
    cache=Path.home()/'.cache/oms-local'
    modules=['oms-common-core','oms-order','oms-supplychain','oms-inventory','oms-channel']
    for module in modules:
        target=cache/'source/oms-modules'/module/'src'
        shutil.copytree(ROOT/'oms-modules'/module/'src',target,dirs_exist_ok=True)
        for source in target.rglob('*.java'):os.utime(source,(time.time()+2,time.time()+2))
    env=dict(os.environ,CARRIER_TEST_URL=test_jdbc_url(database),ORDER_STOCK_TEST_URL=test_jdbc_url('order_inventory_test'),ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    suites=['WmsCarrierBridgeTest','WmsCarrierDirectoryTest','SharedCarrierTest','OrderCarrierPolicyTest','OrderCarrierServiceTest','OrderShipmentDispatchServiceTest','OrderShipmentReceiptServiceTest','OrderPlatformShippingServiceTest','PurchaseReturnFulfillmentTest','StockCareReholdTest','StockMovementTest','WarehouseControlTest','StockCareTest','OrderStockTest','StockProcessTest','PurchaseReturnTest','QimenStockoutTest','PurchaseWorkspaceTest','PurchaseCutoffTest','SupplychainIterationTest','WmsOutboundServiceTest','WmsOutboundReceiptServiceTest','WmsLogQueryTest','ReturnDocumentReadTest','SharedReturnParcelsTest','SharedReturnDispatchTest','TenantRoutingTest']
    out=ROOT/'outputs/shared-carriers';out.mkdir(exist_ok=True)
    with (HERE/'logs/shared-carriers-tests.log').open('w') as log:
        result=subprocess.run([str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl',','.join('oms-modules/'+m for m in ['oms-order','oms-supplychain','oms-inventory','oms-channel']),'-am','-Dtest='+','.join(suites),'-Dsurefire.failIfNoSpecifiedTests=false','package'],env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit('Carrier regressions/build failed: docker/local/logs/shared-carriers-tests.log')
    reports=[]
    for module in modules:
        for file in (cache/'source/oms-modules'/module/'target/surefire-reports').glob('TEST-*.xml'):
            attrs=ET.parse(file).getroot().attrib
            if attrs['name'].split('.')[-1] in suites:
                assert all(int(attrs.get(k,0))==0 for k in ['failures','errors','skipped']),attrs
                reports.append({k:attrs[k] for k in ['name','tests','failures','errors','skipped']})
    assert len(reports)==len(suites)
    (out/'regression.json').write_text(json.dumps({'suites':reports,'total_tests':sum(int(r['tests']) for r in reports)},indent=2),'utf-8')
    print('PASS shared carrier tests and application build')


if __name__=='__main__':main()
