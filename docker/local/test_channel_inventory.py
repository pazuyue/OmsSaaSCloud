"""Run channel inventory read-model tests in a separate disposable MySQL schema."""
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from local_db import execute, ENV, ROOT

DB = 'channel_inventory_query_test'

def main():
    execute('CREATE DATABASE IF NOT EXISTS ' + DB + ' CHARACTER SET utf8mb4')
    tables = ['oms_inventory', 'wms_inventory', 'wms_inventory_batch', 'wms_inventory_change_history', 'oms_channel_inventory', 'rule_stock_info', 'rule_stock_store_code_info', 'rule_stock_channel_info', 'rule_stock_goods_info', 'rule_stock_result', 'rule_stock_reservation', 'rule_stock_order_reservation', 'rule_stock_reservation_event', 'rule_stock_daily_run', 'rule_stock_daily_item']
    for table in tables:
        execute(f'CREATE TABLE IF NOT EXISTS {DB}.{table} LIKE qm_oms_saas_inventory.{table}')
    from retire_compatibility_schema import apply as retire
    retire(DB,backup=False)
    cache = Path.home() / '.cache/oms-local'
    test = Path('oms-modules/oms-inventory/src/test/java/com/oms/inventory/ChannelInventoryQueryTest.java')
    shutil.copy2(ROOT / test, cache / 'source' / test)
    env = dict(os.environ, CHANNEL_INVENTORY_TEST_URL=f'jdbc:mysql://127.0.0.1:13306/{DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai', INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'], JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    command = [str(ROOT / 'docker/local/tools/apache-maven-3.9.9/bin/mvn'), '-B', '-f', str(cache / 'source/pom.xml'), '-Plocal-docker', '-Dmaven.repo.local=' + str(cache / 'm2'), '-pl', 'oms-modules/oms-inventory', '-Dtest=ChannelInventoryQueryTest', 'test']
    with (ROOT / 'docker/local/logs/channel-inventory-tests.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(result.returncode)
    report = ET.parse(cache / 'source/oms-modules/oms-inventory/target/surefire-reports/TEST-com.oms.inventory.ChannelInventoryQueryTest.xml').getroot().attrib
    assert int(report['tests']) >= 7 and int(report.get('skipped', 0)) == 0 and int(report['errors']) == 0 and int(report['failures']) == 0, report
    execute('DROP DATABASE ' + DB)
    print('PASS channel inventory MySQL integration tests:', report['tests'])

if __name__ == '__main__':
    main()
