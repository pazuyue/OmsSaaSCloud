"""Compare retained pre-acceptance rows without exposing personal fields or changing them."""
import re,json
from local_db import execute,HERE
folder=HERE/'backups/order-dispatch-20261015-153444'
s=(folder/'business-databases.sql').read_text(encoding='utf-8')
audit='cutoff_acceptance_audit_20261015';execute('CREATE DATABASE IF NOT EXISTS '+audit+' CHARACTER SET utf8mb4')
checks={
 'qm_oms_saas_order':{
  'oms_order':['status','inventory_status','allocation_status','allocation_quantity','manual_hold','review_status','control_version','stock_attempt_status','stock_attempt_at','issue_synced_at'],
  'oms_order_line':['quantity','reserved_quantity'],
  'oms_order_shipment':['status','quantity','control_version','cancelled_at'],
  'oms_order_shipment_line':['quantity','shipped_quantity'],
  'oms_order_shipment_dispatch':['state','attempts','external_order'],
 },
 'qm_oms_saas_inventory':{
  'oms_channel_inventory':['available_stock','allocated_stock','reserved_stock','quota_stock','quota_reserved_stock','version'],
  'oms_order_stock_demand':['demand_quantity','reserved_quantity','outbound_quantity','status','reason_code'],
 }
}
result={}
for database,tables in checks.items():
 part=s.split('USE `'+database+'`;')[1].split('-- Current Database:')[0]
 for table,columns in tables.items():
  target=('o_' if database.endswith('order') else 'i_')+table
  if execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+audit+"' AND table_name='"+target+"'").strip()=='0':
   ddl=re.search(r'CREATE TABLE `'+table+r'`.*?;\n',part,re.S).group(0).replace('CREATE TABLE `'+table+'`','CREATE TABLE `'+target+'`',1)
   execute(ddl,audit)
   for line in re.findall(r'^INSERT INTO `'+table+r'` VALUES .*;$',part,re.M):execute(line.replace('INSERT INTO `'+table+'`','INSERT INTO `'+target+'`',1),audit)
  changes=[]
  for col in columns:
   count=int(execute('SELECT COUNT(*) FROM '+database+'.'+table+' n JOIN '+audit+'.'+target+' b ON b.id=n.id WHERE NOT(n.`'+col+'` <=> b.`'+col+'`)').strip())
   if count:changes.append({'column':col,'existing_rows_changed':count})
  result[database+'.'+table]=changes
print(json.dumps(result,indent=2))
(HERE.parents[1]/'outputs/order-cutoff/existing-row-audit.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
