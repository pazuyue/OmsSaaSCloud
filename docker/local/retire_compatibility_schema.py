"""Remove obsolete workflow markers after deploying the single-workflow services.

No quantities, money, documents, stock balances or journal rows are rewritten.
The default only lists DDL. --apply backs up all affected tables including data.
"""
import argparse
from datetime import datetime
from local_db import execute, docker_db, ROOT

ALLOWED={'qm_oms_saas_commodity','qm_oms_saas_inventory','purchase_workspace_test','warehouse_workspace_test','inventory_workspace_test','inventory_workspace_test_other','channel_inventory_query_test'}

def plan(db):
    if db not in ALLOWED:raise ValueError('Local business/test databases only')
    tables=set(execute('SHOW TABLES',db).split())
    actions=[]
    for table,column in [('po_info','workspace_version'),('no_tickets','workspace_version'),('wms_tickets','workspace_version'),('rule_stock_result','source_tracked')]:
        if table in tables and execute(f"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='{table}' AND column_name='{column}'",db).strip()=='1':
            actions.append((table,f'ALTER TABLE `{table}` DROP COLUMN `{column}`;'))
    for table in ['warehouse_master_lock','warehouse_schema_migration']:
        if table in tables:actions.append((table,f'DROP TABLE `{table}`;'))
    return actions

def apply(db,backup=True):
    actions=plan(db)
    if not actions:return 0
    if backup:
        folder=ROOT/'docker/local/backups';folder.mkdir(exist_ok=True)
        path=folder/('single-workflow-'+db+'-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.sql')
        with path.open('wb') as output:docker_db('mysqldump','--single-transaction','--no-tablespaces',db,*dict.fromkeys(t for t,_ in actions),output=output)
        print('Backup:',path.name,flush=True)
    for _,sql in actions:execute('SET SESSION lock_wait_timeout=10; '+sql,db)
    assert not plan(db)
    return len(actions)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    for db in ['qm_oms_saas_commodity','qm_oms_saas_inventory']:
        statements=plan(db);print(db,':',len(statements),'obsolete definitions',flush=True)
        for _,sql in statements:print(sql,flush=True)
        if args.apply:print('Removed:',apply(db),flush=True)

if __name__=='__main__':main()
