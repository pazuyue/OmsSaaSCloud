"""Add scheduled ordinary allocation without enabling any historical rule or changing stock."""
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
    execute("""INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '日常分货规则扫描','DEFAULT',\"dailyAllocationTask.scan('qm')\",'0/10 * * * * ?','3','1','0','admin',NOW(),'按公司qm的分货单有效期和间隔扫描，分批处理普通配额，禁止并发，错过触发不补跑'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target IN ('dailyAllocationTask.scan()',\"dailyAllocationTask.scan('qm')\"))""", 'ry-cloud')
    execute("""UPDATE sys_job SET invoke_target=\"dailyAllocationTask.scan('qm')\" WHERE invoke_target='dailyAllocationTask.scan()' AND job_name='日常分货规则扫描'""", 'ry-cloud')
    print('Daily allocation schema and existing Quartz job registered; historical rules remain unscheduled')

if __name__ == '__main__':
    main()
