"""Export all five local application databases, with restore metadata."""
import hashlib
import json
import shutil
import re
from datetime import datetime
from local_db import HERE, docker_db, execute

DATABASES = ['ry-cloud', 'ry-config', 'qm_oms_saas_commodity', 'qm_oms_saas_inventory', 'qm_oms_saas_channel']

def backup(require_complete=True):
    directory = HERE / 'backups'
    directory.mkdir(exist_ok=True)
    path = directory / ('oms-saas-complete-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '.sql')
    existing = set(execute('SHOW DATABASES').splitlines())
    databases = [name for name in DATABASES if name in existing]
    if require_complete and databases != DATABASES:
        raise RuntimeError('Missing databases: ' + ', '.join(set(DATABASES) - existing))
    with path.open('xb') as output:
        # One INSERT per row lets the restore manifest describe this exact MVCC snapshot.
        # Separate SELECT COUNT queries race with active Quartz logs and business writes.
        docker_db('mysqldump', '--single-transaction', '--skip-extended-insert', '--routines', '--triggers', '--events', '--hex-blob', '--default-character-set=utf8mb4', '--databases', *databases, output=output)
    tables = {name: {} for name in databases}
    database = None
    routine = False
    with path.open(encoding='utf-8') as source:
        for line in source:
            if line.startswith('DELIMITER '):
                routine = line.strip() != 'DELIMITER ;'
                continue
            if routine:
                continue
            use = re.match(r'^USE `([^`]+)`;', line)
            create = re.match(r'^CREATE TABLE `([^`]+)`', line)
            insert = re.match(r'^INSERT INTO `([^`]+)`', line)
            if use:
                database = use.group(1)
                assert database in tables, 'Unexpected database in dump'
            elif create:
                tables[database][create.group(1)] = 0
            elif insert:
                tables[database][insert.group(1)] += 1
    assert all(tables.values()), 'Dump is missing expected tables'
    manifest = {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'databases': databases, 'table_counts': tables, 'restored_and_verified': False}
    path.with_suffix('.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding='utf-8')
    shutil.copy2(HERE / '.env', path.with_suffix('.env'))
    path.with_suffix('.sql.sha256').write_text(manifest['sha256'] + '  ' + path.name + '\n')
    print('SQL backup:', path, flush=True)
    print('Databases:', len(databases), 'Tables:', sum(len(x) for x in tables.values()), 'Bytes:', path.stat().st_size, flush=True)
    return path

if __name__ == '__main__':
    import argparse
    import subprocess
    import sys
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true', help='Restore into a disposable MySQL and check every table count.')
    args = parser.parse_args()
    snapshot = backup()
    if args.verify:
        subprocess.run([sys.executable, str(HERE / 'check_backup.py'), str(snapshot)], check=True)
