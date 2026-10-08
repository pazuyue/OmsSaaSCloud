"""Back up local databases, then import the supplied three-schema OMS archive.

Existing business tables are never dropped. A completed import with the same
archive hash is skipped so subsequent local changes survive repeat runs.
"""
import argparse
import hashlib
import json
import re
import shutil
import zipfile
from datetime import datetime
from local_db import HERE, docker_db, execute

SCHEMAS = ['qm_oms_saas_commodity', 'qm_oms_saas_inventory', 'qm_oms_saas_channel']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive')
    args = parser.parse_args()
    archive = __import__('pathlib').Path(args.archive).resolve()
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    state_dir = HERE / 'business'
    state_dir.mkdir(exist_ok=True)
    state_path = state_dir / 'import-state.json'
    state = json.loads(state_path.read_text()) if state_path.exists() else {'sha256': digest, 'completed': {}}
    if state['sha256'] != digest:
        raise SystemExit('Archive differs from the previous import; existing data has been left untouched.')
    with zipfile.ZipFile(archive) as zipped:
        dumps = {name: zipped.read(name + '.sql').decode('utf-8-sig') for name in SCHEMAS}
    for schema, sql in dumps.items():
        tables = re.findall(r'CREATE TABLE\s+`([^`]+)`', sql, re.I)
        if schema in state['completed']:
            continue
        if not tables or re.search(r'^\s*(USE\s|CREATE DATABASE|GRANT\s|CREATE USER|DROP DATABASE)', sql, re.I | re.M):
            raise SystemExit('Unexpected database control statements in ' + schema)
        existing = execute("SELECT table_name FROM information_schema.tables WHERE table_schema=" + repr(schema)).splitlines()
        if existing:
            raise SystemExit('Refusing to overwrite existing tables in ' + schema)
    backup_dir = HERE / 'backups' / datetime.now().strftime('%Y%m%d-%H%M%S')
    backup_dir.mkdir(parents=True, exist_ok=False)
    databases = execute("SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('ry-cloud','ry-config','qm_oms_saas_commodity','qm_oms_saas_inventory','qm_oms_saas_channel')").splitlines()
    with (backup_dir / 'before-business-import.sql').open('wb') as output:
        docker_db('mysqldump', '--single-transaction', '--routines', '--triggers', '--events', '--hex-blob', '--databases', *databases, output=output)
    shutil.copy2(HERE / '.env', backup_dir / 'deployment.env')
    print('Backup:', backup_dir)
    shutil.copy2(archive, state_dir / 'oms-saas.zip')
    for schema, sql in dumps.items():
        if schema in state['completed']:
            print('Already imported:', schema)
            continue
        execute('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci')
        sql = re.sub(r'^DROP TABLE IF EXISTS[^\n]+\n', '', sql, flags=re.M | re.I)
        execute(sql, schema)
        counts = {}
        for table in re.findall(r'CREATE TABLE\s+`([^`]+)`', sql, re.I):
            counts[table] = int(execute('SELECT COUNT(*) FROM `' + table + '`', schema).strip())
        state['completed'][schema] = counts
        state['backup'] = str(backup_dir)
        state_path.write_text(json.dumps(state, indent=2), encoding='utf-8')
        print('Imported:', schema, json.dumps(counts))

if __name__ == '__main__':
    main()
