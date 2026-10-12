"""Exercise purchase lifecycle with real MySQL transactions in a disposable database."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import execute, ENV, ROOT, test_jdbc_url

DB = 'purchase_workspace_test'

def main():
    execute('CREATE DATABASE IF NOT EXISTS ' + DB + ' CHARACTER SET utf8mb4')
    tables = ['owner_info','wms_real_store_info','wms_simulation_store_info','owner_warehouse','supplier_info','po_info','purchase_line','purchase_event','no_tickets','no_tickets_goods','wms_tickets','wms_tickets_goods']
    for table in tables: execute(f'CREATE TABLE IF NOT EXISTS {DB}.{table} LIKE qm_oms_saas_commodity.{table}')
    from migrate_supplychain_row_locks import apply
    apply(DB, backup=False)
    from retire_compatibility_schema import apply as retire
    retire(DB, backup=False)
    from migrate_wms_integration import apply as wms_schema
    wms_schema(DB, backup=False)
    cache = Path.home() / '.cache/oms-local'
    test = Path('oms-modules/oms-supplychain/src/test/java/com/oms/supplychain/PurchaseWorkspaceTest.java')
    shutil.copytree(ROOT/'oms-modules/oms-supplychain/src',cache/'source/oms-modules/oms-supplychain/src',dirs_exist_ok=True)
    env = dict(os.environ, PURCHASE_TEST_URL=test_jdbc_url(DB), INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command = [str(ROOT / 'docker/local/tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'), '-Plocal-docker', '-Dmaven.repo.local='+str(cache/'m2'), '-pl', 'oms-modules/oms-supplychain', '-Dtest=PurchaseWorkspaceTest,WmsOutboundServiceTest,WmsOutboundReceiptServiceTest', 'test']
    with (ROOT/'docker/local/logs/purchase-workspace-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode: raise SystemExit(result.returncode)
    report = ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.PurchaseWorkspaceTest.xml').getroot().attrib
    assert int(report['tests']) >= 20 and int(report.get('skipped',0)) == 0 and int(report['failures']) == int(report['errors']) == 0, report
    outbound = ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.WmsOutboundServiceTest.xml').getroot().attrib
    assert int(outbound['tests']) >= 10 and int(outbound.get('skipped',0)) == 0 and int(outbound['failures']) == int(outbound['errors']) == 0, outbound
    receipts=ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.WmsOutboundReceiptServiceTest.xml').getroot().attrib
    assert int(receipts['tests'])>=7 and int(receipts['skipped'])==int(receipts['failures'])==int(receipts['errors'])==0,receipts
    execute('DROP DATABASE '+DB)
    print('PASS purchase and outbound MySQL integration tests:', int(report['tests'])+int(outbound['tests'])+int(receipts['tests']))

if __name__ == '__main__': main()
