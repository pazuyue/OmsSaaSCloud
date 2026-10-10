"""Initialize scheduled ordinary allocation."""
from local_db import HERE, execute
DB = 'qm_oms_saas_inventory'

def main():
    assert list((HERE/'backups').glob('oms-saas-complete-*.sql')), 'Create a backup first'
    for statement in (HERE/'migrations/20261009_daily_allocation.sql').read_text().split(';'):
        sql = '\n'.join(line for line in statement.splitlines() if not line.startswith('--')).strip()
        if not sql:
            continue
        if sql.startswith('ALTER TABLE'):
            table = sql.split()[2]
            for change in sql.split(' ', 3)[3].split(',\n'):
                change = change.strip()
                name = change.split()[2].split('(')[0]
                kind, field = ('columns', 'column_name') if change.startswith('ADD COLUMN') else ('statistics', 'index_name')
                if execute(f"SELECT COUNT(*) FROM information_schema.{kind} WHERE table_schema=DATABASE() AND table_name='{table}' AND {field}='{name}'", DB).strip() == '0':
                    execute(f'ALTER TABLE {table} {change}', DB)
                else:
                    if change.startswith('ADD COLUMN'):
                        execute(f'ALTER TABLE {table} MODIFY COLUMN {change[len("ADD COLUMN "):]}', DB)
                    elif change.startswith('ADD INDEX'):
                        columns = change.split('(', 1)[1].rstrip(')')
                        existing = execute(f"SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}'", DB).strip()
                        if existing != columns:
                            execute(f'ALTER TABLE {table} DROP INDEX {name}, {change}', DB)
        else:
            execute(sql, DB)
            # Also add comments when the table was already created by an earlier local migration.
            if sql.startswith('CREATE TABLE IF NOT EXISTS'):
                table = sql.split()[5]
                for line in sql.splitlines()[1:]:
                    definition = line.strip().rstrip(',')
                    if " COMMENT '" in definition and not definition.startswith(')'):
                        execute(f'ALTER TABLE {table} MODIFY COLUMN {definition}', DB)
                comment = sql.rsplit("COMMENT=", 1)[1]
                execute(f'ALTER TABLE {table} COMMENT={comment}', DB)
    from migrate_daily_scan import ensure_unified_job
    ensure_unified_job()
    print('Daily allocation schema and unified Quartz task ready')

if __name__ == '__main__':
    main()
