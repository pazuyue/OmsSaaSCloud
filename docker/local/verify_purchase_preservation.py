"""Compare original business columns against the pre-migration backup in a disposable schema."""
import re
import sys
from pathlib import Path
from local_db import execute

def main():
    backup = Path(sys.argv[1])
    text=backup.read_text(encoding='utf-8')
    sections=re.split(r'USE `([^`]+)`;',text)
    databases=dict(zip(sections[1::2],sections[2::2]))
    scope={'qm_oms_saas_commodity':['supplier_info','po_info','no_tickets','no_tickets_goods','wms_tickets','wms_tickets_goods','goods_sku_sn_info','owner_info','wms_real_store_info','wms_simulation_store_info'], 'qm_oms_saas_inventory':['oms_inventory','wms_inventory','wms_inventory_batch','oms_channel_inventory','wms_inventory_change_history']}
    target='purchase_preservation_test'
    execute('CREATE DATABASE IF NOT EXISTS '+target+' CHARACTER SET utf8mb4')
    try:
        for source,tables in scope.items():
            for table in tables:
                section=databases[source]
                create=re.search(r'CREATE TABLE `'+table+r'` \(.*?\) ENGINE=.*?;',section,re.S)
                assert create,table
                columns=re.findall(r'^  `([^`]+)`',create.group(),re.M)
                insert=re.findall(r'^INSERT INTO `'+table+r'` VALUES .*?;$',section,re.M)
                execute('DROP TABLE IF EXISTS `'+table+'`;'+create.group()+''.join(insert),target)
                select='SELECT '+','.join('`'+c+'`' for c in columns)+' FROM `'+table+'` ORDER BY `'+columns[0]+'`'
                assert execute(select,target)==execute(select,source),table+' original records changed'
                print('PASS original rows preserved:',table)
    finally: execute('DROP DATABASE '+target)

if __name__=='__main__':main()
