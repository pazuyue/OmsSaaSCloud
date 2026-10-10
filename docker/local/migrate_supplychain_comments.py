"""Fill current supply-chain table/column comments without changing data or column definitions.

The CREATE scripts are the source of comment text. Existing definitions come from SHOW CREATE
TABLE, preserving types, nullability, defaults, charset, indexes and automatic timestamps.
Run without arguments to inspect, then --apply to update comments and verify preservation.
"""
import argparse
import hashlib
import json
import re
from datetime import datetime
from local_db import HERE, execute, quote

DB='qm_oms_saas_commodity'
TABLES={'purchase_line','purchase_event','owner_warehouse'}
COMMENT=re.compile(r"\s+COMMENT\s+(?:=\s*)?'(?:[^'\\]|\\.|'')*'",re.I)

def desired_comments():
    result={}
    for name in ['20261009_purchase_workspace.sql','20261009_warehouse_workspace.sql']:
        source=(HERE/'migrations'/name).read_text(encoding='utf-8')
        for table,body,comment in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\) ENGINE=.*?COMMENT='([^']*)';",source,re.S):
            columns=dict(re.findall(r"^\s*(\w+)\s+[^\n]*?COMMENT '([^']*)'",body,re.M))
            result[table]={'table':comment,'columns':columns}
    assert set(result)==TABLES
    return result

def metadata(table):
    definitions={}
    ddl=execute('SHOW CREATE TABLE `'+table+'`',DB).split('\t',1)[1].strip()
    for line in ddl.splitlines():
        match=re.match(r'\s*`([^`]+)`\s+(.+)',line)
        if match:definitions[match.group(1)]=match.group(2).rstrip(',')
    comment=json.loads(execute("SELECT JSON_OBJECT('comment',TABLE_COMMENT) FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+quote(table),DB))['comment']
    attributes=execute("SELECT ENGINE,ROW_FORMAT,TABLE_COLLATION,CREATE_OPTIONS FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+quote(table),DB)
    indexes=execute('SHOW INDEX FROM `'+table+'`',DB)
    # Index cardinality is an estimate that can change independently of this migration.
    indexes=['\t'.join(parts[:6]+parts[7:]) for parts in (line.split('\t') for line in indexes.splitlines())]
    raw=execute('SELECT * FROM `'+table+'` ORDER BY `'+next(iter(definitions))+'`',DB)
    return {'ddl':ddl,'definitions':definitions,'comment':comment,'attributes':attributes,'indexes':indexes,'data_sha256':hashlib.sha256(raw.encode('utf-8')).hexdigest()}

def main(apply=False):
    desired=desired_comments();before={};statements=[];rollback=[];pending=[]
    for table,wanted in desired.items():
        info=metadata(table);before[table]=info
        assert set(wanted['columns']).issubset(info['definitions']),table+': missing columns; inspect before altering'
        changes=['MODIFY COLUMN `'+col+'` '+COMMENT.sub('',definition)+' COMMENT '+quote(wanted['columns'][col]) for col,definition in info['definitions'].items() if col in wanted['columns']]
        statements.append('ALTER TABLE `'+table+'`\n  '+',\n  '.join(changes+['COMMENT='+quote(wanted['table']),'ALGORITHM=INPLACE','LOCK=NONE'])+';')
        rollback.append('ALTER TABLE `'+table+'`\n  '+',\n  '.join(['MODIFY COLUMN `'+col+'` '+definition for col,definition in info['definitions'].items()]+['COMMENT='+quote(info['comment']),'ALGORITHM=INPLACE','LOCK=NONE'])+';')
        current=dict((c,COMMENT.search(d).group(0) if COMMENT.search(d) else '') for c,d in info['definitions'].items() if c in wanted['columns'])
        if info['comment']!=wanted['table'] or any(v.strip()!='COMMENT '+quote(wanted['columns'][c]) for c,v in current.items()):pending.append(table)
        print(table+': '+wanted['table']+'; columns='+str(len(wanted['columns'])))
    sql='-- Comments only; existing types, defaults, charset and indexes are preserved.\nUSE `'+DB+'`;\nSET SESSION lock_wait_timeout=10;\n\n'+'\n\n'.join(statements)+'\n'
    (HERE/'migrations/20261010_supplychain_comments.sql').write_text(sql,encoding='utf-8')
    if not apply:
        print('DRY RUN; tables needing comments:',len(pending));return
    if not pending:
        print('Already complete; no DDL needed');return
    stamp=datetime.now().strftime('%Y%m%d-%H%M%S')
    folder=HERE/'backups';folder.mkdir(exist_ok=True)
    (folder/('supplychain-comments-'+stamp+'.json')).write_text(json.dumps(before,ensure_ascii=False,indent=2),encoding='utf-8')
    undo=folder/('supplychain-comments-'+stamp+'-rollback.sql')
    undo.write_text('USE `'+DB+'`;\n'+'\n\n'.join(rollback)+'\n',encoding='utf-8')
    for table,statement in zip(desired,statements):
        if table in pending:execute('SET SESSION lock_wait_timeout=10;\n'+statement,DB)
        after=metadata(table);old=before[table]
        assert {k:COMMENT.sub('',v) for k,v in old['definitions'].items()}=={k:COMMENT.sub('',v) for k,v in after['definitions'].items()},table+': column definition changed'
        assert old['attributes']==after['attributes'] and old['indexes']==after['indexes'],table+': table attributes or indexes changed'
        assert old['data_sha256']==after['data_sha256'],table+': rows changed during verification; inspect concurrent activity'
        assert after['comment']==desired[table]['table']
        for col,text in desired[table]['columns'].items():assert COMMENT.search(after['definitions'][col]).group(0).strip()=='COMMENT '+quote(text)
        print('PASS comments complete, definitions/indexes/rows unchanged:',table)
    print('Updated '+str(len(desired))+' table comments and '+str(sum(len(v['columns']) for v in desired.values()))+' column comments')
    print('Rollback SQL:',undo)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true')
    main(parser.parse_args().apply)
