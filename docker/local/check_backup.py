"""Restore a snapshot into a disposable, isolated MySQL container and compare it."""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path
from local_db import HERE

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot')
    args = parser.parse_args()
    path = Path(args.snapshot).resolve()
    manifest = json.loads(path.with_suffix('.json').read_text(encoding='utf-8'))
    assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest['sha256'], 'Checksum mismatch'
    name = 'oms-local-restore-check-' + str(int(time.time()))
    empty = HERE / 'business/empty-init'
    empty.mkdir(parents=True, exist_ok=True)
    subprocess.run(['docker', 'run', '-d', '--rm', '--name', name, '--network', 'none', '--tmpfs', '/var/lib/mysql:rw,size=1g', '-v', str(empty) + ':/docker-entrypoint-initdb.d:ro', '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', 'mysql:5.7', '--lower-case-table-names=1', '--character-set-server=utf8mb4'], check=True, stdout=subprocess.DEVNULL)
    try:
        for _ in range(60):
            check = subprocess.run(['docker', 'exec', name, 'mysql', '-h127.0.0.1', '-uroot', '-e', 'SELECT 1'], capture_output=True)
            if check.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Temporary MySQL did not become ready')
        with path.open('rb') as source:
            result = subprocess.run(['docker', 'exec', '-i', name, 'mysql', '-uroot', '--default-character-set=utf8mb4'], stdin=source, capture_output=True)
        if result.returncode:
            raise RuntimeError(result.stderr.decode())
        def query(sql):
            return subprocess.check_output(['docker', 'exec', name, 'mysql', '-uroot', '-N', '--batch', '-e', sql], text=True).strip()
        checked = 0
        for database, tables in manifest['table_counts'].items():
            actual_names = set(query('SHOW TABLES FROM `' + database + '`').splitlines())
            assert actual_names == set(tables), (database, actual_names ^ set(tables))
            for table, expected in tables.items():
                actual = int(query('SELECT COUNT(*) FROM `' + database + '`.`' + table + '`'))
                assert actual == expected, (database, table, actual, expected)
                checked += 1
        manifest['restored_and_verified'] = True
        manifest['verified_tables'] = checked
        path.with_suffix('.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding='utf-8')
        print('PASS isolated restore:', len(manifest['databases']), 'databases,', checked, 'tables; all row counts match.')
    finally:
        subprocess.run(['docker', 'stop', '-t', '10', name], check=True, stdout=subprocess.DEVNULL)

if __name__ == '__main__':
    main()
