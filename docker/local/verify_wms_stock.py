"""Read-only stock assertions scoped to the exact generated WMS acceptance SKU."""
import re,sys
from local_db import execute,quote

def main(prefix):
    assert re.fullmatch(r'PURUI[0-9]{10}',prefix)
    sku=quote(prefix+'-SKU')
    inventory='qm_oms_saas_inventory'
    assert execute(f'SELECT zp_actual_number,cp_actual_number FROM wms_inventory WHERE sku_sn={sku}',inventory).strip()=='3\t2'
    assert execute(f'SELECT SUM(zp_actual_number),SUM(cp_actual_number),COUNT(*) FROM wms_inventory_batch WHERE sku_sn={sku}',inventory).strip()=='3\t2\t2'
    assert execute(f'SELECT total_stock FROM oms_inventory WHERE sku_sn={sku}',inventory).strip()=='5'
    assert execute(f'SELECT COUNT(*),SUM(change_quantity) FROM wms_inventory_change_history WHERE sku_sn={sku} AND operation_type=\'RECEIVE\'',inventory).strip()=='2\t5'
    print('PASS actual stock: good=3, bad=2, batches=2; duplicate callbacks did not repost')

if __name__=='__main__':main(sys.argv[1])
