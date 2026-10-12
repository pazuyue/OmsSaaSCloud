"""Back up running binaries and live data; compare business tables after dispatch UI QA."""
import hashlib
import json
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from local_db import HERE, execute, docker_db, quote

DATABASES = ['qm_oms_saas_order', 'qm_oms_saas_inventory', 'qm_oms_saas_commodity', 'qm_oms_saas_channel']


def snapshot(baseline=None):
    result = {}
    for database in DATABASES:
        tables = execute('SHOW TABLES', database).splitlines()
        for table in tables:
            include = (table.startswith(('oms_order', 'oms_platform')) if database.endswith('_order') else
                       table.startswith(('oms_', 'wms_inventory', 'rule_stock')) if database.endswith('_inventory') else
                       table == 't_channel' if database.endswith('_channel') else table in ['wms_simulation_store_info', 'wms_real_store_info', 'owner_info', 'owner_warehouse', 'wms_connection', 'wms_inbound_task'])
            if not include or table in ['oms_order_shipment_dispatch', 'oms_order_shipment_virtual_outbound', 'oms_order_virtual_outbound']:
                continue
            key=database + '.' + table
            if baseline and key not in baseline:continue
            columns=baseline[key]['columns'] if baseline and key in baseline and 'columns' in baseline[key] else [line.split('\t')[0] for line in execute('SHOW COLUMNS FROM `' + table + '`', database).splitlines()]
            primary=[row.split('\t')[4] for row in execute('SHOW INDEX FROM `'+table+'`',database).splitlines() if row.split('\t')[2]=='PRIMARY']
            key_column=primary[0] if len(primary)==1 else None
            key_values=baseline[key].get('key_values') if baseline and key in baseline else (execute('SELECT `'+key_column+'` FROM `'+table+'`',database).splitlines() if key_column else None)
            predicate=(' WHERE `'+key_column+'` IN ('+','.join(quote(v) for v in key_values)+')' if key_values else ' WHERE 1=0') if key_column and key_values is not None else ''
            rows = execute('SELECT '+','.join('`'+column+'`' for column in columns)+' FROM `' + table + '`'+predicate, database).splitlines()
            result[key] = {'key_column': key_column, 'key_values': key_values, 'columns': columns, 'count': len(rows), 'sha256': hashlib.sha256('\n'.join(sorted(rows)).encode()).hexdigest()}
    return result


def main():
    if sys.argv[1:2] == ['compare']:
        folder = Path(sys.argv[2])
        previous = json.loads((folder / 'business-snapshot.json').read_text())
        current = snapshot(previous)
        changed = [key for key in set(previous) | set(current) if previous.get(key) != current.get(key)]
        (folder / 'business-after.json').write_text(json.dumps(current, indent=2))
        print(json.dumps({'business_tables_checked': len(previous), 'changed_tables': sorted(changed)}))
        if changed:
            raise SystemExit(1)
        return
    folder = HERE / 'backups' / ('order-dispatch-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    folder.mkdir(parents=True)
    with (folder / 'business-databases.sql').open('wb') as output:
        docker_db('mysqldump', '--single-transaction', '--default-character-set=utf8mb4', '--databases', *DATABASES, output=output)
    for module in ['order', 'supplychain', 'inventory']:
        subprocess.run(['docker', 'cp', 'oms-local-' + module + ':/app/app.jar', str(folder / ('oms-' + module + '.jar'))], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shutil.copy2(HERE / 'dist/index.html', folder / 'frontend-index.html')
    (folder / 'menu-permissions.tsv').write_text(execute("SELECT * FROM sys_menu WHERE perms LIKE 'order:shipment:%'"), encoding='utf-8')
    (folder / 'business-snapshot.json').write_text(json.dumps(snapshot(), indent=2))
    print(folder)


if __name__ == '__main__':
    main()
