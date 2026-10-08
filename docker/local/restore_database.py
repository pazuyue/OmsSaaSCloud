"""Restore a verified local snapshot, with a fresh rollback backup first."""
import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from backup_database import backup
from local_db import ENV, HERE, docker_db

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot')
    parser.add_argument('--replace-local-data', action='store_true', required=True, help='Acknowledge replacement of the snapshot tables in oms-local-mysql.')
    args = parser.parse_args()
    snapshot = Path(args.snapshot).resolve()
    manifest = json.loads(snapshot.with_suffix('.json').read_text(encoding='utf-8'))
    if hashlib.sha256(snapshot.read_bytes()).hexdigest() != manifest['sha256']:
        raise RuntimeError('SQL checksum mismatch')
    saved_env = dict(line.split('=', 1) for line in snapshot.with_suffix('.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
    if saved_env.get('MYSQL_ROOT_PASSWORD') != ENV.get('MYSQL_ROOT_PASSWORD'):
        raise RuntimeError('Snapshot database password differs from local .env. Restore requires the matching deployment environment because Nacos contains database credentials.')
    compose = ['docker', 'compose', '-f', str(HERE / 'compose.yaml')]
    services = json.loads((HERE / 'compose.yaml').read_text())['services']
    subprocess.run(compose + ['stop'] + [name for name in services if name not in ['mysql', 'redis']], check=True)
    rollback = backup(require_complete=False)
    print('Rollback snapshot:', rollback, flush=True)
    docker_db('mysql', '--default-character-set=utf8mb4', sql=snapshot.read_text(encoding='utf-8'))
    subprocess.run(['docker', 'exec', 'oms-local-redis', 'redis-cli', 'FLUSHDB'], check=True)
    env_path = HERE / '.env'
    current = env_path.read_text()
    if 'OMS_BUSINESS_DATABASES=1' not in current:
        current = current.replace('OMS_BUSINESS_DATABASES=0', 'OMS_BUSINESS_DATABASES=1')
        if 'OMS_BUSINESS_DATABASES=1' not in current:
            current = current.rstrip() + '\nOMS_BUSINESS_DATABASES=1\n'
        env_path.write_text(current)
    subprocess.run([sys.executable, str(HERE / 'prepare.py')], check=True)
    # Keep restored Nacos contents rather than publishing newly generated defaults.
    subprocess.run(compose + ['up', '-d', '--wait', '--wait-timeout', '300'], check=True)
    print('Restored:', snapshot)

if __name__ == '__main__':
    main()
