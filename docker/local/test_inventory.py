"""Run real-MySQL transactional tests in an isolated schema; never against business rows."""
import os
import subprocess
import shutil
import sys
from pathlib import Path
from local_db import execute, ENV, ROOT

def main():
    database='inventory_workspace_test'
    if sys.argv[1:]==['--cleanup']:
        execute('DROP DATABASE IF EXISTS inventory_workspace_test')
        print('Disposable inventory test schema removed')
        return
    execute('CREATE DATABASE IF NOT EXISTS '+database+' CHARACTER SET utf8mb4')
    for table in ['oms_inventory','wms_inventory','wms_inventory_batch','wms_inventory_change_history','oms_channel_inventory','rule_stock_info','rule_stock_store_code_info','rule_stock_channel_info','rule_stock_goods_info','rule_stock_result','rule_stock_reservation','rule_stock_order_reservation','rule_stock_reservation_event','rule_stock_daily_run','rule_stock_daily_item']:
        execute(f'CREATE TABLE IF NOT EXISTS {database}.{table} LIKE qm_oms_saas_inventory.{table}')
    for table,name,columns in [('wms_inventory','idx_inventory_company_page','company_code,id'),('wms_inventory_batch','idx_batch_company_page','company_code,sku_sn,store_code,id')]:
        if execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'",database).strip()=='0':
            execute(f'ALTER TABLE {table} ADD INDEX {name} ({columns})',database)
    cache=Path.home()/'.cache/oms-local'
    source='oms-modules/oms-inventory/src/test/java/com/oms/inventory/InventoryTransactionsTest.java'
    (cache/'source'/source).parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/source,cache/'source'/source)
    allocation='oms-modules/oms-inventory/src/test/java/com/oms/inventory/AllocationWorkspaceTest.java'
    shutil.copy2(ROOT/allocation,cache/'source'/allocation)
    product='oms-modules/oms-inventory/src/test/java/com/oms/inventory/ProductInventoryQueryTest.java'
    shutil.copy2(ROOT/product,cache/'source'/product)
    daily='oms-modules/oms-inventory/src/test/java/com/oms/inventory/DailyAllocationTest.java'
    shutil.copy2(ROOT/daily,cache/'source'/daily)
    env=dict(os.environ,INVENTORY_TEST_URL=f'jdbc:mysql://127.0.0.1:13306/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    mvn=ROOT/'docker/local/tools/apache-maven-3.9.9/bin/mvn'
    command=[str(mvn),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-inventory','-Dtest=InventoryTransactionsTest,AllocationWorkspaceTest,ProductInventoryQueryTest,DailyAllocationTest','-DfailIfNoTests=false','test']
    with (ROOT/'docker/local/logs/inventory-tests.log').open('w') as log:
        result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
    print('Inventory MySQL integration tests exit:',result.returncode)
    if result.returncode == 0:
        import xml.etree.ElementTree as ET
        report=cache/'source/oms-modules/oms-inventory/target/surefire-reports/TEST-com.oms.inventory.InventoryTransactionsTest.xml'
        summary=ET.parse(report).getroot().attrib
        assert int(summary['tests'])>=8 and int(summary.get('skipped',0))==0,summary
        print('Verified tests:',summary['tests'],'failures:',summary['failures'],'errors:',summary['errors'])
        allocation_summary=ET.parse(report.with_name('TEST-com.oms.inventory.AllocationWorkspaceTest.xml')).getroot().attrib
        assert int(allocation_summary['tests'])>=20 and int(allocation_summary.get('skipped',0))==0,allocation_summary
        print('Allocation tests:',allocation_summary['tests'],'failures:',allocation_summary['failures'],'errors:',allocation_summary['errors'])
        product_summary=ET.parse(report.with_name('TEST-com.oms.inventory.ProductInventoryQueryTest.xml')).getroot().attrib
        assert int(product_summary['tests'])>=12 and int(product_summary.get('skipped',0))==0,product_summary
        print('Product and batch inventory tests:',product_summary['tests'],'failures:',product_summary['failures'],'errors:',product_summary['errors'])
        daily_summary=ET.parse(report.with_name('TEST-com.oms.inventory.DailyAllocationTest.xml')).getroot().attrib
        assert int(daily_summary['tests'])>=11 and int(daily_summary.get('skipped',0))==0,daily_summary
        print('Daily allocation tests:',daily_summary['tests'],'failures:',daily_summary['failures'],'errors:',daily_summary['errors'])
        execute('DROP DATABASE inventory_workspace_test')
    raise SystemExit(result.returncode)

if __name__=='__main__':main()
