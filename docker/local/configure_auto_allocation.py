"""Install automatic-allocation tables; no historical tasks or stock changes."""
from local_db import execute,HERE

def install():
    execute((HERE/'migrations/20261014_auto_allocation.sql').read_text(encoding='utf-8'),'qm_oms_saas_order')
    execute((HERE/'migrations/20261014_allocation_stock_signal.sql').read_text(encoding='utf-8'),'qm_oms_saas_inventory')
    print('Automatic-allocation task and stock-signal tables ready; no data backfill')
if __name__=='__main__':install()
