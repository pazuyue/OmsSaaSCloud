"""Add only the company/directory link lookup index; preserve all business and inventory data."""
from local_db import HERE, execute


def main():
    database = 'qm_oms_saas_order'
    exists = execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_carrier_mapping' AND index_name='directory_links'", database).strip()
    if exists == '0':
        execute((HERE / 'migrations/20261027_carrier_management_ui.sql').read_text('utf-8'), database)
    print('Carrier directory lookup index ready; no business records changed')


if __name__ == '__main__':
    main()
