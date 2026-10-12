"""Recover this one historical public reservation; inspect by default.

09:14 and 09:51 snapshots prove company available stock decreased by 5,
reserved stock increased by 5, and neither channel locks nor warehouse locks
were consumed. Restore its missing PUBLIC_EXTRA trace without reserving again.
Requires the exact unrepaired state; SQL guard failures disconnect/roll back.
"""
import argparse
import hashlib
import json
import re
from datetime import datetime

from local_db import HERE, docker_db, execute, quote

INV = 'qm_oms_saas_inventory'
ORD = 'qm_oms_saas_order'
SN = 'SO9665a1bc8382406783a5a52e4d14d0bc'
TID = '9006742279826212208'
OID = '9001989143121569208'
ERROR = '逻辑预占来源缺失或数量不一致，请先核对来源台账'
MESSAGE = '历史预占来源修复：依据09:14/09:51备份补记公共库存逻辑预占5件；不重复扣减库存，未执行分仓'


def dump_rows(text, table):
    schema = re.search(r'CREATE TABLE `' + table + r'` \((.*?)\) ENGINE=', text, re.S)
    assert schema, table
    columns = re.findall(r'^  `([^`]+)`', schema.group(1), re.M)
    result = []
    for match in re.finditer(r'^INSERT INTO `' + table + r'` VALUES (.*);$', text, re.M):
        data = match.group(1)
        quoted = escaped = inside = False
        fields = []
        for i, ch in enumerate(data):
            if escaped:
                escaped = False
                continue
            if ch == '\\' and quoted:
                escaped = True
                continue
            if ch == "'":
                quoted = not quoted
                continue
            if quoted:
                continue
            if ch == '(':
                inside, fields, start = True, [], i + 1
            elif ch == ',' and inside:
                fields.append(data[start:i])
                start = i + 1
            elif ch == ')' and inside:
                fields.append(data[start:i])
                result.append(dict(zip(columns, fields)))
                inside = False
    return result


def historical_evidence():
    evidence = []
    for stamp, available, reserved in [('091454', 325, 0), ('095106', 320, 5)]:
        path = HERE / 'backups' / ('oms-saas-complete-20261011-' + stamp + '.sql')
        text = path.read_text(encoding='utf-8')
        def sku_rows(table):
            return [r for r in dump_rows(text, table)
                    if r.get('company_code') == "'QM'" and r.get('sku_sn') == "'test01'"]
        company, = sku_rows('oms_inventory')
        assert [int(company[k]) for k in ['available_stock', 'reserved_stock', 'allocated_stock']] == [available, reserved, 0]
        locks = sku_rows('rule_stock_reservation')
        assert locks and all(int(r['occupied_quantity']) == 0 and int(r['released_quantity']) == int(r['original_quantity']) for r in locks)
        warehouse = sku_rows('wms_inventory')
        assert warehouse and all(int(r['zp_lock_number']) == 0 for r in warehouse)
        assert not sku_rows('oms_order_stock_source')
        demands = sku_rows('oms_order_stock_demand')
        if reserved:
            demand, = [r for r in demands if r['id'] == '7']
            assert demand['platform_tid'] == quote(TID) and demand['platform_oid'] == quote(OID)
            assert int(demand['reserved_quantity']) == 5
            assert sum(int(r['reserved_quantity']) for r in demands) == 5
        else:
            assert sum(int(r['reserved_quantity']) for r in demands) == 0
        evidence.append({'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                         'available': available, 'reserved': reserved, 'lock_occupied': 0, 'warehouse_occupied': 0})
    return evidence


def rows(database, table, where):
    columns = [line.split('\t')[0] for line in execute('SHOW COLUMNS FROM `' + table + '`', database).splitlines()]
    fields = ','.join(quote(c) + ',`' + c + '`' for c in columns)
    return [json.loads(line) for line in execute('SELECT JSON_OBJECT(' + fields + ') FROM `' + table + '` WHERE ' + where + ' ORDER BY id', database).splitlines()]


def snapshot():
    scope = "company_code='QM' AND sku_sn='test01'"
    result = {table: rows(INV, table, scope) for table in [
        'oms_inventory', 'oms_channel_inventory', 'oms_order_stock_demand',
        'oms_order_stock_source', 'rule_stock_reservation', 'rule_stock_result',
        'wms_inventory', 'wms_inventory_batch', 'oms_order_warehouse_allocation']}
    result['order'] = rows(ORD, 'oms_order', "company_code='QM' AND id=5")
    result['lines'] = rows(ORD, 'oms_order_line', "company_code='QM' AND order_id=5")
    result['shipments'] = rows(ORD, 'oms_order_shipment', "company_code='QM' AND order_id=5")
    result['events'] = rows(INV, 'oms_order_stock_event', "company_code='QM' AND demand_id=7")
    result['logs'] = rows(ORD, 'oms_order_process_log', "company_code='QM' AND platform_order_id=46")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    evidence = historical_evidence()
    before = snapshot()
    order, = before['order']
    demand, = [r for r in before['oms_order_stock_demand'] if r['id'] == 7]
    source = [r for r in before['oms_order_stock_source'] if r['demand_id'] == 7]
    if source:
        assert len(source) == 1 and source[0]['source_type'] == 'PUBLIC_EXTRA' and source[0]['source_id'] == 0 and source[0]['quantity'] == 5 and source[0]['reserved'] == 1
        print('Already repaired: one PUBLIC_EXTRA source, quantity 5; no writes.')
        return
    assert order['order_sn'] == SN and order['status'] == 'WAIT_ALLOCATION'
    assert order['review_status'] == 'APPROVED' and order['inventory_status'] == 'RESERVED'
    assert order['allocation_status'] == 'FAILED' and order['allocation_error'] == ERROR
    assert order['allocation_quantity'] == 0 and not before['shipments']
    assert demand['platform_tid'] == TID and demand['platform_oid'] == OID and demand['reserved_quantity'] == demand['demand_quantity'] == 5 and demand['status'] == 'RESERVED'
    assert not [r for r in before['oms_order_warehouse_allocation'] if r['demand_id'] == 7]
    print('Historical evidence verified: PUBLIC_EXTRA 5; no warehouse selected.')
    print('Current: reserved 5, source 0, allocated 0; repair will preserve all inventory balances.')
    if not args.apply:
        return
    path = HERE / 'backups' / ('order-source-so9665-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    path.mkdir(parents=True)
    (path / 'before.json').write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding='utf-8')
    (path / 'evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    with (path / 'before.sql').open('wb') as out:
        docker_db('mysqldump', '--single-transaction', '--skip-lock-tables', '--databases', INV, ORD, output=out)
    def guard(condition):
        # A failed guard attempts a duplicate key. mysql stops; connection close
        # rolls back the uncommitted transaction (no --force is passed).
        return 'INSERT INTO repair_guard SELECT 1 WHERE COALESCE((' + condition + '),0)=0;\n'
    sql = "SET SESSION innodb_lock_wait_timeout=15; SET TRANSACTION ISOLATION LEVEL READ COMMITTED; START TRANSACTION;\nCREATE TEMPORARY TABLE repair_guard (id INT PRIMARY KEY); INSERT INTO repair_guard VALUES(1);\n"
    sql += f"SELECT id FROM {ORD}.oms_order_pull_config WHERE company_code='QM' AND channel_id=1 FOR UPDATE;\n"
    sql += guard(f"SELECT COUNT(*)=1 FROM {ORD}.oms_order_pull_config WHERE company_code='QM' AND channel_id=1 AND (lease_until IS NULL OR lease_until<NOW())")
    for database, table, where in [
        (ORD, 'oms_platform_order', "company_code='QM' AND id=46"),
        (ORD, 'oms_order', "company_code='QM' AND id=5"),
        (ORD, 'oms_order_line', "company_code='QM' AND order_id=5"),
        *[(INV, table, "company_code='QM' AND sku_sn='test01'") for table in [
            'oms_inventory', 'oms_channel_inventory', 'oms_order_stock_demand',
            'oms_order_stock_source', 'rule_stock_reservation', 'wms_inventory',
            'wms_inventory_batch', 'oms_order_warehouse_allocation']],
        (ORD, 'oms_order_shipment', "company_code='QM' AND order_id=5")]:
        sql += f'SELECT id FROM {database}.{table} WHERE {where} ORDER BY id FOR UPDATE;\n'
    sql += guard(f"SELECT COUNT(*)=1 FROM {ORD}.oms_order WHERE company_code='QM' AND id=5 AND order_sn={quote(SN)} AND platform_order_id=46 AND channel_id=1 AND platform_tid={quote(TID)} AND status='WAIT_ALLOCATION' AND review_status='APPROVED' AND inventory_status='RESERVED' AND manual_hold=0 AND allocation_status='FAILED' AND allocation_quantity=0 AND allocation_at IS NULL AND allocation_error={quote(ERROR)} AND control_version={int(order['control_version'])}")
    sql += guard(f"SELECT COUNT(*)=1 FROM {ORD}.oms_order_line WHERE company_code='QM' AND order_id=5 AND id=5 AND platform_oid={quote(OID)} AND sku_sn='test01' AND quantity=5 AND reserved_quantity=5")
    sql += guard(f"SELECT COUNT(*)=1 FROM {INV}.oms_order_stock_demand WHERE company_code='QM' AND id=7 AND channel_id=1 AND platform_tid={quote(TID)} AND platform_oid={quote(OID)} AND sku_sn='test01' AND demand_quantity=5 AND reserved_quantity=5 AND status='RESERVED'")
    sql += guard(f"SELECT COUNT(*)=0 FROM {INV}.oms_order_stock_source WHERE demand_id=7")
    sql += guard(f"SELECT SUM(reserved_quantity)=5 FROM {INV}.oms_order_stock_demand WHERE company_code='QM' AND sku_sn='test01'")
    sql += guard(f"SELECT COUNT(*)=1 FROM {INV}.oms_inventory WHERE company_code='QM' AND sku_sn='test01' AND available_stock=320 AND reserved_stock=5 AND allocated_stock=0")
    sql += guard(f"SELECT COUNT(*)=1 FROM {INV}.oms_channel_inventory WHERE company_code='QM' AND channel_id=1 AND sku_sn='test01' AND reserved_stock=5 AND allocated_stock=0")
    sql += guard(f"SELECT COUNT(*)=0 FROM {INV}.rule_stock_reservation WHERE company_code='QM' AND sku_sn='test01' AND (occupied_quantity<>0 OR original_quantity<>released_quantity+consumed_quantity)")
    sql += guard(f"SELECT COUNT(*)=0 FROM {INV}.wms_inventory WHERE company_code='QM' AND sku_sn='test01' AND zp_lock_number<>0")
    sql += guard(f"SELECT COUNT(*)=0 FROM {INV}.oms_order_warehouse_allocation WHERE company_code='QM' AND (demand_id=7 OR platform_tid={quote(TID)})")
    sql += guard(f"SELECT COUNT(*)=0 FROM {ORD}.oms_order_shipment WHERE company_code='QM' AND order_id=5")
    sql += f"INSERT INTO {INV}.oms_order_stock_source(demand_id,company_code,sku_sn,store_code,source_type,source_id,quantity,reserved) VALUES(7,'QM','test01','','PUBLIC_EXTRA',0,5,1);\n"
    sql += f"INSERT INTO {INV}.oms_order_stock_event(company_code,demand_id,action,quantity,message) VALUES('QM',7,'SOURCE_REPAIR',5,{quote(MESSAGE)});\n"
    sql += f"UPDATE {ORD}.oms_order SET allocation_status='NONE',allocation_error='',control_version=control_version+1 WHERE company_code='QM' AND id=5;\n"
    sql += f"INSERT INTO {ORD}.oms_order_process_log(company_code,platform_order_id,action,result,message,operator_name) VALUES('QM',46,'STOCK_SOURCE_REPAIR','SUCCESS',{quote(MESSAGE)},'数据修复');\n"
    sql += guard(f"SELECT SUM(quantity)=5 FROM {INV}.oms_order_stock_source WHERE company_code='QM' AND demand_id=7 AND reserved=1")
    sql += 'COMMIT;\n'
    (path / 'applied.sql').write_text(sql, encoding='utf-8')
    execute(sql, INV)
    after = snapshot()
    (path / 'after.json').write_text(json.dumps(after, ensure_ascii=False, indent=2), encoding='utf-8')
    for key in ['oms_inventory', 'oms_channel_inventory', 'oms_order_stock_demand',
                'rule_stock_reservation', 'rule_stock_result', 'wms_inventory',
                'wms_inventory_batch', 'oms_order_warehouse_allocation', 'lines', 'shipments']:
        assert after[key] == before[key], key + ' changed; investigate backup ' + str(path)
    restored, = [r for r in after['oms_order_stock_source'] if r['demand_id'] == 7]
    assert restored['quantity'] == demand['reserved_quantity'] == 5 and restored['source_type'] == 'PUBLIC_EXTRA' and restored['reserved'] == 1
    assert after['order'][0]['allocation_status'] == 'NONE' and after['order'][0]['allocation_error'] == ''
    assert after['order'][0]['control_version'] == order['control_version'] + 1
    print('Repaired: PUBLIC_EXTRA source 5; allocation status NONE; repair audit recorded.')
    print('Verified company/channel/warehouse/batch balances, demand, order lines and shipments unchanged.')
    print('Backup:', path.name)


if __name__ == '__main__':
    main()
