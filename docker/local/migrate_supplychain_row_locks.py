"""Add business uniqueness/referential constraints before deploying row locks.

Default is a read-only preflight. Apply additive DDL before deploying the service.
"""
import argparse
import datetime
import json
from pathlib import Path
from local_db import execute, docker_db, ROOT, quote

BUSINESS_DB = 'qm_oms_saas_commodity'
DATABASES = {BUSINESS_DB, 'purchase_workspace_test', 'warehouse_workspace_test'}
KEYS = {
    'owner_info': [('uk_owner_company_code', ['owner_code'])],
    'wms_real_store_info': [('uk_real_company_code', ['real_store_code'])],
    'wms_simulation_store_info': [('uk_virtual_company_code', ['wms_simulation_code'])],
    'owner_warehouse': [],
    'supplier_info': [('uk_supplier_code', ['supplier_sn']), ('uk_supplier_name', ['supplier_name']), ('uk_supplier_company_name', ['company_name'])],
    'po_info': [('uk_purchase_code', ['po_sn'])],
    'no_tickets': [('uk_receipt_code', ['no_sn'])],
    'wms_tickets': [('uk_ticket_code', ['sn']), ('uk_purchase_receipt_execution', ['purchase_receipt_key'])],
}
FOREIGN_KEYS = [
    ('owner_warehouse','fk_relation_owner','owner_id','owner_info'),
    ('owner_warehouse','fk_relation_real','real_store_id','wms_real_store_info'),
    ('wms_simulation_store_info','fk_virtual_relation','owner_warehouse_id','owner_warehouse'),
]
RECEIPT_EXPRESSION="CASE WHEN ticket_type=1 THEN NULLIF(relation_sn,'') ELSE NULL END"

def scalar(sql, db):
    return int(execute(sql, db).strip())

def plan(db):
    if db not in DATABASES:
        raise ValueError('Only the local business/test databases are allowed')
    tables=set(execute('SHOW TABLES',db).split())
    statements=[]
    for table, keys in KEYS.items():
        if table not in tables:
            continue
        # Duplicate business identities require explicit correction, never silent merging.
        for name, fields in keys:
            group=['UPPER(company_code)']+[RECEIPT_EXPRESSION if f=='purchase_receipt_key' else f for f in fields]
            predicate=' AND '.join(f'({f}) IS NOT NULL' for f in group)
            duplicates=scalar('SELECT COUNT(*) FROM (SELECT 1 FROM '+table+' WHERE '+predicate+' GROUP BY '+','.join(group)+' HAVING COUNT(*)>1) conflicts',db)
            if duplicates:
                raise RuntimeError(f'{table}.{name}: {duplicates} duplicate groups; repair explicitly before migration')
        columns={line.split('\t')[0] for line in execute(f'SHOW COLUMNS FROM `{table}`',db).splitlines()}
        clauses=[]
        if 'company_key' not in columns:
            clauses.append("ADD COLUMN company_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (UPPER(company_code)) STORED COMMENT '公司编码大写索引键，用于公司内业务唯一约束'")
        if table=='wms_tickets' and 'purchase_receipt_key' not in columns:
            clauses.append("ADD COLUMN purchase_receipt_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS ("+RECEIPT_EXPRESSION+") STORED COMMENT '新采购入库流程的到货单唯一关联键；其他单据为空'")
        indexes=set(line.split('\t')[2] for line in execute(f'SHOW INDEX FROM `{table}`',db).splitlines())
        for name, fields in keys:
            if name not in indexes:
                clauses.append(f'ADD UNIQUE KEY `{name}` (company_key,'+','.join(fields)+')')
        if clauses:
            statements.append(f'ALTER TABLE `{table}` '+', '.join(clauses)+';')
    for table,name,column,parent in FOREIGN_KEYS:
        if table not in tables or parent not in tables:
            continue
        if scalar(f'SELECT COUNT(*) FROM {table} child LEFT JOIN {parent} parent ON parent.id=child.{column} WHERE child.{column} IS NOT NULL AND (parent.id IS NULL OR UPPER(parent.company_code)<>UPPER(child.company_code))',db):
            raise RuntimeError(f'{table}.{column}: orphan/cross-company references; repair before migration')
        if not scalar("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name="+quote(table)+" AND constraint_name="+quote(name),db):
            statements.append(f'ALTER TABLE `{table}` ADD CONSTRAINT `{name}` FOREIGN KEY (`{column}`) REFERENCES `{parent}` (id) ON DELETE RESTRICT ON UPDATE RESTRICT;')
    return statements

def apply(db, backup=True):
    statements=plan(db)
    if not statements:
        return 0
    if backup:
        folder=ROOT/'docker/local/backups';folder.mkdir(exist_ok=True)
        path=folder/('supplychain-row-locks-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.sql')
        with path.open('wb') as output:
            docker_db('mysqldump','--single-transaction','--no-tablespaces','--no-data',db,output=output)
        print('DDL backup:',path.name)
    for sql in statements:
        execute('SET SESSION lock_wait_timeout=10; '+sql,db)
    if plan(db):
        raise AssertionError('Migration is incomplete')
    return len(statements)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database',choices=sorted(DATABASES),default=BUSINESS_DB)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    statements=plan(args.database)
    if args.database==BUSINESS_DB:
        artifact=ROOT/'docker/local/migrations/20261010_supplychain_row_locks.sql'
        if statements:
            artifact.write_text('-- Additive migration before deploying business-row locking.\n'+'\n\n'.join(statements)+'\n',encoding='utf-8')
    print(json.dumps({'database':args.database,'pendingStatements':len(statements)},ensure_ascii=False))
    if args.apply:
        print('Applied statements:',apply(args.database,backup=args.database==BUSINESS_DB))

if __name__=='__main__':
    main()
