"""Refresh an existing local channel/SKU through inventory's transactional writer.

Default is inspection only. --apply saves a snapshot and recomputes projections;
it does not reserve, cancel, reallocate, or modify warehouse stock.
"""
import argparse
import json
import re
import subprocess
from datetime import datetime
from urllib.parse import urlencode

from local_db import HERE, execute, quote

DATABASE = 'qm_oms_saas_inventory'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--company', required=True)
    parser.add_argument('--channel', type=int, required=True)
    parser.add_argument('--sku', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_-]{1,50}', args.company) and args.channel > 0
    company = args.company.upper()
    sku = quote(args.sku)
    where = f"company_code={quote(company)} AND sku_sn={sku}"

    def rows(sql):
        return [json.loads(line) for line in execute(sql, DATABASE).splitlines() if line]

    def snapshot():
        return {
            'channel': rows(f"SELECT JSON_OBJECT('id',id,'channel',channel_id,'quota',quota_stock,'quotaReserved',quota_reserved_stock,'available',available_stock,'pending',pending_stock,'reserved',reserved_stock,'allocated',allocated_stock,'frozen',frozen_stock,'scope',stock_scope_json) FROM oms_channel_inventory WHERE {where} ORDER BY channel_id"),
            'company': execute(f"SELECT total_stock,available_stock,allocated_stock,reserved_stock FROM oms_inventory WHERE {where}", DATABASE),
            'warehouses': execute(f"SELECT * FROM wms_inventory WHERE {where} ORDER BY id", DATABASE),
            'batches': execute(f"SELECT * FROM wms_inventory_batch WHERE {where} ORDER BY id", DATABASE),
            'demands': execute(f"SELECT * FROM oms_order_stock_demand WHERE {where} ORDER BY id", DATABASE),
            'sources': execute(f"SELECT * FROM oms_order_stock_source WHERE {where} ORDER BY id", DATABASE),
        }

    before = snapshot()
    target = next(row for row in before['channel'] if row['channel'] == args.channel)
    assert target['scope'], 'Recover the allocation warehouse scope before refreshing'
    print('Before:', json.dumps(target, ensure_ascii=False))
    if not args.apply:
        return
    demands = rows(f"SELECT JSON_OBJECT('tid',platform_tid,'oid',platform_oid,'quantity',demand_quantity) FROM oms_order_stock_demand WHERE {where} AND channel_id={args.channel} ORDER BY id LIMIT 1")
    assert demands, 'An existing demand is required for this refresh command'
    backups = HERE / 'backups'
    backups.mkdir(exist_ok=True)
    path = backups / ('channel-quota-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.json')
    path.write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding='utf-8')
    demand = demands[0]
    command = {'channelId': args.channel, 'platformTid': demand['tid'], 'lines': [{'platformOid': demand['oid'], 'skuSn': args.sku, 'quantity': demand['quantity']}]}
    url = 'http://127.0.0.1:9303/orderStock/internal/query?' + urlencode({'company_code': company})
    response = subprocess.run(['docker', 'exec', '-i', 'oms-local-inventory', 'curl', '--fail', '--silent', '--show-error', '--max-time', '30', '-H', 'from-source: inner', '-H', 'Content-Type: application/json', '--data-binary', '@-', url], input=json.dumps(command), capture_output=True, text=True)
    assert response.returncode == 0, response.stderr
    body = json.loads(response.stdout)
    assert body['code'] == 200, body
    after = snapshot()
    for key in ['company', 'warehouses', 'batches', 'demands', 'sources']:
        assert after[key] == before[key], f'{key} changed during refresh; review snapshot {path}'
    print('After:', json.dumps(next(row for row in after['channel'] if row['channel'] == args.channel), ensure_ascii=False))
    print('Verified company balances, warehouse stock, batches, demands and sources unchanged.')
    print('Snapshot:', path.name)


if __name__ == '__main__':
    main()
