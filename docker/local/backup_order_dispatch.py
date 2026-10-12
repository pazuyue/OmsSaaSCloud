"""Back up running binaries and live data; compare business tables after dispatch UI QA."""
import hashlib
import json
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from local_db import HERE, execute, docker_db

DATABASES = ['qm_oms_saas_order', 'qm_oms_saas_inventory', 'qm_oms_saas_commodity']


def snapshot():
    result = {}
    for database in DATABASES:
        tables = execute('SHOW TABLES', database).splitlines()
        for table in tables:
            include = (table.startswith(('oms_order', 'oms_platform')) if database.endswith('_order') else
                       table.startswith(('oms_', 'wms_inventory', 'rule_stock')) if database.endswith('_inventory') else
                       table in ['wms_simulation_store_info', 'wms_real_store_info', 'owner_info', 'owner_warehouse', 'wms_connection', 'wms_inbound_task'])
            if not include or table == 'oms_order_shipment_dispatch':
                continue
            rows = execute('SELECT * FROM `' + table + '`', database).splitlines()
            result[database + '.' + table] = {'count': len(rows), 'sha256': hashlib.sha256('\n'.join(sorted(rows)).encode()).hexdigest()}
    return result


def main():
    if sys.argv[1:2] == ['compare']:
        folder = Path(sys.argv[2])
        previous = json.loads((folder / 'business-snapshot.json').read_text())
        current = snapshot()
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
