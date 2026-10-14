"""Add the virtual warehouse to shipment identity. Never backfill or split existing documents."""
from local_db import execute
from configure_order import column


def install(database):
    column(database, 'oms_order_shipment', 'store_code', "VARCHAR(255) NOT NULL DEFAULT '' COMMENT '发货单所属虚拟仓，新单独立下发'")
    fields = execute("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_order_shipment' AND index_name='shipment_wave' ORDER BY seq_in_index", database).splitlines()
    desired = ['company_code', 'order_id', 'generation_no', 'owner_code', 'real_store_code', 'store_code']
    if fields != desired:
        if fields not in ([], desired[:-1]):
            raise RuntimeError('Unexpected shipment_wave index; inspect before changing it')
        drop = 'DROP INDEX shipment_wave, ' if fields else ''
        execute('ALTER TABLE oms_order_shipment ' + drop + 'ADD UNIQUE KEY shipment_wave(' + ','.join(desired) + ')', database)


if __name__ == '__main__':
    install('qm_oms_saas_order')
    print('Shipment virtual warehouse identity installed; existing documents unchanged')
