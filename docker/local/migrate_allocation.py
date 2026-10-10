"""Idempotent local allocation migration. Run backup_database.py before this script."""
from pathlib import Path
from local_db import execute

ROOT = Path(__file__).resolve().parent
DB = 'qm_oms_saas_inventory'

def main():
    assert list((ROOT / 'backups').glob('oms-saas-complete-*.sql')), 'Create a backup first'
    # Check each DDL independently so an interrupted migration is safe to resume.
    statements = ((ROOT / 'migrations/20261008_allocation_workspace.sql').read_text() + '\n' +
                  (ROOT / 'migrations/20261008_reservation_sources.sql').read_text()).split(';')
    for statement in statements:
        sql = '\n'.join(line for line in statement.splitlines() if not line.startswith('--')).strip()
        if not sql:
            continue
        if sql.startswith('ALTER TABLE'):
            table = sql.split()[2]
            for change in sql.split(' ', 3)[3].split(',\n'):
                change = change.strip()
                name = change.split()[2].split('(')[0]
                kind = 'columns' if change.startswith('ADD COLUMN') else 'statistics'
                field = 'column_name' if kind == 'columns' else 'index_name'
                exists = execute(f"SELECT COUNT(*) FROM information_schema.{kind} WHERE table_schema=DATABASE() AND table_name='{table}' AND {field}='{name}'", DB).strip()
                if exists == '0':
                    execute(f'ALTER TABLE {table} {change}', DB)
        else:
            execute(sql, DB)
    print('Allocation workspace migration applied; existing stock preserved')

if __name__ == '__main__':
    main()
