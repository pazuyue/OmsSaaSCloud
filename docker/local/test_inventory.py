"""Run real-MySQL transactional tests in an isolated schema; never against business rows."""
import os
import subprocess
import shutil
from pathlib import Path
from local_db import execute, ENV, ROOT

def main():
    database='inventory_workspace_test'
    execute('CREATE DATABASE IF NOT EXISTS '+database+' CHARACTER SET utf8mb4')
    for table in ['oms_inventory','wms_inventory','wms_inventory_batch','wms_inventory_change_history']:
        execute(f'CREATE TABLE IF NOT EXISTS {database}.{table} LIKE qm_oms_saas_inventory.{table}')
    for table,name,columns in [('wms_inventory','idx_inventory_company_page','company_code,id'),('wms_inventory_batch','idx_batch_company_page','company_code,sku_sn,store_code,id')]:
        if execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'",database).strip()=='0':
            execute(f'ALTER TABLE {table} ADD INDEX {name} ({columns})',database)
    cache=Path.home()/'.cache/oms-local'
    source='oms-modules/oms-inventory/src/test/java/com/oms/inventory/InventoryTransactionsTest.java'
    (cache/'source'/source).parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/source,cache/'source'/source)
    env=dict(os.environ,INVENTORY_TEST_URL=f'jdbc:mysql://127.0.0.1:13306/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    mvn=ROOT/'docker/local/tools/apache-maven-3.9.9/bin/mvn'
    command=[str(mvn),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-inventory','-Dtest=InventoryTransactionsTest','-DfailIfNoTests=false','test']
    with (ROOT/'docker/local/logs/inventory-tests.log').open('w') as log:
        result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
    print('Inventory MySQL integration tests exit:',result.returncode)
    if result.returncode == 0:
        import xml.etree.ElementTree as ET
        report=cache/'source/oms-modules/oms-inventory/target/surefire-reports/TEST-com.oms.inventory.InventoryTransactionsTest.xml'
        summary=ET.parse(report).getroot().attrib
        assert int(summary['tests'])>=8 and int(summary.get('skipped',0))==0,summary
        print('Verified tests:',summary['tests'],'failures:',summary['failures'],'errors:',summary['errors'])
    raise SystemExit(result.returncode)

if __name__=='__main__':main()
