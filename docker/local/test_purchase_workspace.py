"""Exercise purchase lifecycle with real MySQL transactions in a disposable database."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import execute, ENV, ROOT, test_jdbc_url

DB = 'purchase_workspace_test'

def prepare():
    execute('CREATE DATABASE IF NOT EXISTS ' + DB + ' CHARACTER SET utf8mb4')
    tables = ['owner_info','wms_real_store_info','wms_simulation_store_info','owner_warehouse','supplier_info','po_info','purchase_line','purchase_event','no_tickets','no_tickets_goods','wms_tickets','wms_tickets_goods']
    for table in tables: execute(f'CREATE TABLE IF NOT EXISTS {DB}.{table} LIKE qm_oms_saas_commodity.{table}')
    from migrate_supplychain_row_locks import apply
    apply(DB, backup=False)
    from retire_compatibility_schema import apply as retire
    retire(DB, backup=False)
    from migrate_wms_integration import apply as wms_schema
    wms_schema(DB, backup=False)
    from configure_sales_outbound_documents import install_supplychain
    install_supplychain(DB)
    from configure_receipt_review import install_supplychain as review_schema
    review_schema(DB)
    from configure_order_cutoff import install_supply
    install_supply(DB)
    from configure_wms_log_center import install as log_center_schema
    log_center_schema(DB)


def main():
    prepare()
    cache = Path.home() / '.cache/oms-local'
    test = Path('oms-modules/oms-supplychain/src/test/java/com/oms/supplychain/PurchaseWorkspaceTest.java')
    shutil.copytree(ROOT/'oms-modules/oms-supplychain/src',cache/'source/oms-modules/oms-supplychain/src',dirs_exist_ok=True)
    env = dict(os.environ, PURCHASE_TEST_URL=test_jdbc_url(DB), INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command = [str(ROOT / 'docker/local/tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'), '-Plocal-docker', '-Dmaven.repo.local='+str(cache/'m2'), '-pl', 'oms-modules/oms-supplychain', '-Dtest=ExchangeReturnTest,PurchaseWorkspaceTest,WmsOutboundServiceTest,WmsOutboundReceiptServiceTest,SalesOutboundDocumentServiceTest,WmsLogQueryTest,PurchaseCutoffTest', 'test']
    with (ROOT/'docker/local/logs/purchase-workspace-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode: raise SystemExit(result.returncode)
    report = ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.PurchaseWorkspaceTest.xml').getroot().attrib
    assert int(report['tests']) >= 20 and int(report.get('skipped',0)) == 0 and int(report['failures']) == int(report['errors']) == 0, report
    outbound = ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.WmsOutboundServiceTest.xml').getroot().attrib
    assert int(outbound['tests']) >= 10 and int(outbound.get('skipped',0)) == 0 and int(outbound['failures']) == int(outbound['errors']) == 0, outbound
    receipts=ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.WmsOutboundReceiptServiceTest.xml').getroot().attrib
    assert int(receipts['tests'])>=9 and int(receipts['skipped'])==int(receipts['failures'])==int(receipts['errors'])==0,receipts
    sales=ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.SalesOutboundDocumentServiceTest.xml').getroot().attrib
    assert int(sales['tests'])>=6 and int(sales['skipped'])==int(sales['failures'])==int(sales['errors'])==0,sales
    logs=ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.WmsLogQueryTest.xml').getroot().attrib
    assert int(logs['tests'])>=9 and int(logs['skipped'])==int(logs['failures'])==int(logs['errors'])==0,logs
    cutoff=ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.PurchaseCutoffTest.xml').getroot().attrib
    assert int(cutoff['tests'])>=16 and int(cutoff['skipped'])==int(cutoff['failures'])==int(cutoff['errors'])==0,cutoff
    exchange=ET.parse(cache/'source/oms-modules/oms-supplychain/target/surefire-reports/TEST-com.oms.supplychain.service.wms.ExchangeReturnTest.xml').getroot().attrib
    assert int(exchange['tests']) >= 13 and int(exchange['skipped']) == int(exchange['failures']) == int(exchange['errors']) == 0, exchange
    execute('DROP DATABASE '+DB)
    print('PASS purchase and outbound MySQL integration tests:', int(report['tests'])+int(outbound['tests'])+int(receipts['tests'])+int(sales['tests'])+int(logs['tests'])+int(cutoff['tests'])+int(exchange['tests']))

if __name__ == '__main__': main()
