"""Resume retained acceptance data through scoped automatic workers and signed TEST receipts.

Never manually reviews, pays, creates a return plan, or writes a stock quantity.
The original manifest/results remain immutable evidence of the first execution.
"""
import json
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path
from verify_after_ship_refund_auto_cases import Run, ROOT, OUT, ORDER, CHANNEL, SOURCE, dump, BASE
from test_multi_virtual_shipping import sql, quote
from wms_qimen_simulator import sign

DEST = OUT / 'retest'


class Retest(Run):
    def __init__(self):
        DEST.mkdir(parents=True, exist_ok=True)
        self.path = DEST / 'manifest.json'
        self.data = json.loads((self.path if self.path.exists() else OUT / 'manifest.json').read_text('utf-8'))
        if self.data.get('not_implemented'):
            self.data['initial_gaps'] = self.data.pop('not_implemented')
        self.data['not_implemented'] = []
        self.data['production_code_modified'] = True
        self.token = None

    def source(self, c):
        """Mirror the latest simulated official fact into the owned JDP fixture only."""
        ref = c['refunds']['target']
        raw = sql(f"SELECT payload FROM channel_refund_sim WHERE channel_id={c['channel_id']} AND refund_id={quote(ref['refund_id'])}", CHANNEL)
        payload = json.loads(raw)
        p = payload.get('refund_get_response', {}).get('refund', payload.get('refund', payload))
        if not c.get('retest_source_ready'):
            p['good_status'] = 'BUYER_NOT_RECEIVED' if c['key'] in ['05', '06', '08'] else 'BUYER_RECEIVED'
            p['refund_version'] = str(int(p.get('refund_version', '1')) + 1)
            p['modified'] = max(datetime.now(), datetime.strptime(p['modified'], '%Y-%m-%d %H:%M:%S') + timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S')
            wire = json.dumps({'refund_get_response': {'refund': p}}, ensure_ascii=False)
            sql(f"UPDATE channel_refund_sim SET payload={quote(wire)},modified={quote(p['modified'])} WHERE channel_id={c['channel_id']} AND refund_id={quote(ref['refund_id'])}", CHANNEL)
            c['retest_source_ready'] = True
            self.save()
        wire = json.dumps({'refund_get_response': {'refund': p}}, ensure_ascii=False)
        sql(f"UPDATE jdp_tb_refund SET status={quote(p['status'])},modified={quote(p['modified'])},jdp_modified={quote(p['modified'])},jdp_response={quote(wire)} WHERE refund_id={quote(ref['refund_id'])} AND seller_nick={quote(c['seller'])}", SOURCE)

    def prepare_retest(self, keys):
        for key in keys:
            c = self.data['cases'][key]
            assert c['channel_id'] > self.data['protected']['maximum_existing_channel']
            self.source(c)
            self.tick(c, intake=True)
        # Only the new acceptance shops get these explicit merchant permissions.
        for key in keys:
            c = self.data['cases'][key]
            if not c.get('retest_rules_ready'):
                flow = self.api(f"/order/refunds/flow-configs/{c['channel_id']}")
                self.api(f"/order/refunds/flow-configs/{c['channel_id']}", {**flow, 'enabled': 1, 'small_refund_enabled': 1, 'small_refund_limit': '20.00', 'auto_shipped_cancel': 1, 'auto_finish_return': 1})
                c['retest_rules_ready'] = True
                self.save()
            self.tick(c)

    def tick(self, c, intake=False):
        ref = c['refunds']['target']
        if intake:
            self.source(c)
            sequence = int(c.get('retest_sequence', 0)) + 1
            c['retest_sequence'] = sequence
            self.save()
            self.api('/order/refunds/sync', {'channel_id': c['channel_id'], 'mode': 'IDS', 'refund_ids': [ref['refund_id']], 'request_key': 'AF_RETEST_' + ref['refund_id'] + '_' + str(sequence)})
        self.api(f"/order/refunds/business/{ref['id']}/flow/retry", {})

    def status(self, c):
        ref = c['refunds']['target']
        b = self.api(f"/order/refunds/business/{ref['id']}")
        flow = sql(f"SELECT state,last_message FROM oms_refund_flow_task WHERE company_code='QM' AND refund_id={ref['id']}", ORDER).split('\t', 1)
        goods = self.api(f"/order/refunds/business/{ref['id']}/goods")
        parcels = self.api(f"/order/refunds/business/{ref['id']}/parcels")
        operations = sql(f"SELECT action,state,result_message FROM oms_refund_operation WHERE company_code='QM' AND refund_id={ref['id']} ORDER BY id", ORDER)
        result = {'business': b, 'flow_state': flow[0] if flow else '', 'flow_message': flow[1] if len(flow) > 1 else '', 'goods': goods, 'parcels': parcels, 'operations': operations, 'stock': self.snapshot(c)}
        dump(DEST / (c['key'] + '-observation.json'), result)
        return result

    def receipt(self, c, parcel, partial=False):
        doc = parcel.get('warehouse_document')
        if not doc or doc.get('dispatch_state') not in ['ACCEPTED', 'SUCCESS']:
            return False
        marker = 'PART' if partial else 'FINAL'
        receipt_key = str(parcel['id']) + '_' + marker
        if receipt_key in c.get('retest_receipts', {}):
            return False
        state = self.status(c)
        plans = [p for p in state['goods']['plans'] if any(m['plan_id'] == p['id'] for m in parcel['members'])]
        if not plans or not all(p.get('actual') for p in plans):
            return False
        actual = plans[0]['actual']
        connection = next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id'] == actual['connection_id'])
        assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
        credential = next(x for x in json.loads((ROOT / 'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key'] == connection['app_key'] and x['customer_id'] == connection['customer_id'])
        quantities = {}
        for plan in plans:
            qty = plan['quantity'] - plan['accepted_quantity']
            if partial:
                qty = min(1, qty)
            if qty:
                quantities[plan['sku_sn']] = quantities.get(plan['sku_sn'], 0) + qty
        if not quantities:
            return False
        before = self.snapshot(c)
        root = ET.Element('request')
        head = ET.SubElement(root, 'returnOrder')
        for key, value in {'returnOrderCode': doc['return_sn'], 'returnOrderId': doc['external_order'], 'warehouseCode': actual['external_warehouse'], 'ownerCode': actual['external_owner'], 'outBizCode': 'AF_RETEST_' + c['case_id'] + '_' + receipt_key, 'orderType': 'THRK', 'confirmType': '1' if partial else '0'}.items():
            ET.SubElement(head, key).text = str(value)
        lines = ET.SubElement(root, 'orderLines')
        for index, (sku, qty) in enumerate(sorted(quantities.items())):
            line = ET.SubElement(lines, 'orderLine')
            for key, value in {'itemCode': sku, 'ownerCode': actual['external_owner'], 'actualQty': qty, 'inventoryType': 'ZP', 'batchCode': 'AFRET_' + c['case_id'] + '_' + str(index)}.items():
                ET.SubElement(line, key).text = str(value)
        wire = ET.tostring(root, encoding='unicode')
        (DEST / (c['key'] + '-' + receipt_key + '.xml')).write_text(wire, 'utf-8')
        params = {'app_key': connection['app_key'], 'customerId': connection['customer_id'], 'method': 'taobao.qimen.returnorder.confirm', 'v': '2.0', 'format': 'xml', 'sign_method': 'md5', 'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
        params['sign'] = sign(params, wire, credential['secret'])
        req = urllib.request.Request(BASE + '/prod-api/supplychain/wmsCallback/qm/' + connection['callback_key'] + '?' + urllib.parse.urlencode(params), data=wire.encode(), headers={'Content-Type': 'application/xml;charset=UTF-8'}, method='POST')
        def send():
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=60) as response:
                reply = ET.fromstring(response.read())
            assert reply.findtext('flag') == 'success', reply.findtext('message')
        send()
        send()  # Same original signed callback must be idempotent.
        c.setdefault('retest_receipts', {})[receipt_key] = {'quantity': quantities, 'partial': partial, 'stock_before': before, 'duplicate_callback_sent': True}
        self.save()
        self.tick(c)
        return True

    def run_cases(self, keys, seconds=650):
        self.prepare_retest(keys)
        until = time.monotonic() + seconds
        tick_at = 0
        last = {}
        while True:
            done = 0
            for key in keys:
                c = self.data['cases'][key]
                s = self.status(c)
                b = s['business']
                summary = (b['state'], b['goods_state'], b['funds_state'], s['flow_state'], s['flow_message'], len(s['goods']['plans']))
                if last.get(key) != summary:
                    print(key, summary, flush=True)
                    last[key] = summary
                if key in ['02B', '03', '10']:
                    if s['flow_state'] == 'MANUAL':
                        done += 1
                    continue
                if b['state'] == 'COMPLETED':
                    done += 1
                    continue
                # Send actual TEST warehouse receipts only after automatic plan creation and dispatch.
                for parcel in s['parcels']:
                    partial = key == '07' and not any(x['partial'] for x in c.get('retest_receipts', {}).values())
                    if key == '07' and not partial and not c.get('retest_partial_verified'):
                        if any(p['accepted_quantity'] == 1 and p['posted_quantity'] == 1 for p in s['goods']['plans']):
                            assert b['funds_state'] != 'SUCCESS' and 'PAY\t' not in s['operations'], 'Partial return paid before full receipt'
                            c['retest_partial_verified'] = True
                            c['partial_stock'] = s['stock']
                            self.save()
                        else:
                            continue
                    self.receipt(c, parcel, partial)
            if done == len(keys) or time.monotonic() > until:
                break
            if time.monotonic() > tick_at:
                for key in keys:
                    self.tick(self.data['cases'][key], intake=True)
                tick_at = time.monotonic() + 30
            time.sleep(3)
        self.report_retest(keys)

    def review_audit(self, keys):
        """Read the actual review and platform-operation actors, including all setup refunds."""
        roles = {}
        for key in keys:
            for role, ref in self.data['cases'][key]['refunds'].items():
                roles[ref['id']] = {'case': key, 'role': role, 'url': ref['url']}
        ids = ','.join(str(i) for i in roles)
        raw = sql("SELECT JSON_OBJECT('id',b.id,'refund_sn',b.refund_sn,'amount',b.refund_fee,'purpose',b.purpose,'review_state',b.review_state,'review_by',b.review_by,'reviewed_at',b.reviewed_at,'funds_state',b.funds_state,'review_events',(SELECT COUNT(*) FROM oms_refund_event e WHERE e.company_code=b.company_code AND e.refund_id=b.id AND e.action='REVIEW'),'manual_review_events',(SELECT COUNT(*) FROM oms_refund_event e WHERE e.company_code=b.company_code AND e.refund_id=b.id AND e.action='REVIEW' AND e.actor NOT IN ('自动售后规则','自动售后流程'))) FROM oms_refund b WHERE b.company_code='QM' AND b.id IN (" + ids + ') ORDER BY b.id', ORDER)
        refunds = []
        for line in raw.splitlines():
            row = json.loads(line)
            row.update(roles[row['id']])
            row['manual_local_review'] = row['manual_review_events'] > 0
            row['local_review_automatic'] = row['review_state'] == 'APPROVED' and row['review_by'] in ['自动售后规则', '自动售后流程'] and not row['manual_local_review'] and row['review_events'] > 0
            row['source_success_prepared_as_fixture'] = row['role'] == 'prior'
            refunds.append(row)
        assert len(refunds) == len(roles), 'Missing refund review evidence'
        raw = sql("SELECT JSON_OBJECT('refund_id',refund_id,'action',action,'actor',actor,'state',state,'policy_revision',policy_revision) FROM oms_refund_operation WHERE company_code='QM' AND refund_id IN (" + ids + ") AND action IN ('REVIEW','PAY','CANCEL_FEEDBACK','INTERCEPT') ORDER BY id", ORDER)
        operations = [json.loads(line) for line in raw.splitlines()]
        target_ids = {i for i, role in roles.items() if role['role'] == 'target'}
        manual_target = any(row['manual_local_review'] or row['review_state'] == 'APPROVED' and not row['local_review_automatic'] for row in refunds if row['id'] in target_ids)
        manual_target |= any(op['actor'] != '自动售后流程' or op['policy_revision'] is None for op in operations if op['refund_id'] in target_ids)
        return {'scope': '目标单自动处理步骤；含人工前置的完整申请序列尚未全自动验收', 'refunds': refunds, 'platform_operations': operations, 'manual_target_review_or_pay': manual_target,
                'automatic_target_reviews': sum(row['local_review_automatic'] for row in refunds if row['id'] in target_ids),
                'target_reviews_not_adopted': sum(row['review_state'] == 'PENDING' for row in refunds if row['id'] in target_ids),
                'manual_prior_reviews': sum(row['manual_local_review'] for row in refunds if row['role'] == 'prior')}

    def report_retest(self, keys):
        audit = self.review_audit(keys)
        rows = []
        for key in keys:
            c = self.data['cases'][key]
            s = self.status(c)
            b = s['business']
            stock = {r[0]: list(map(int, r[1:])) for r in s['stock']['company']}
            protection = key in ['02B', '03', '10']
            expected_total = 99 if key in ['01', '02A', '02B', '03', '10'] else 100
            money_ok = s['flow_state'] == 'MANUAL' and b['review_state'] == 'PENDING' and 'PAY\t' not in s['operations'] if protection else b['state'] == 'COMPLETED' and b['funds_state'] == 'SUCCESS' and b['goods_state'] == 'DONE'
            stock_ok = stock[c['sku']] == [expected_total, expected_total, 0, 0] and (key != '09' or stock[c['gift']] == [100, 100, 0, 0])
            specific = ('剩余可退 80.00' in s['flow_message'] if key == '10' else bool(c.get('retest_partial_verified')) if key == '07' else True)
            passed = money_ok and stock_ok and specific
            c.setdefault('before_fix_status', c.get('status'))
            c.setdefault('before_fix_finding', c.get('finding'))
            setup = [row for row in audit['refunds'] if row['case'] == key and row['role'] == 'prior']
            target = next(row for row in audit['refunds'] if row['case'] == key and row['role'] == 'target')
            review_ok = target['local_review_automatic'] if b['review_state'] == 'APPROVED' else target['review_events'] == 0
            operation_ok = all(op['actor'] == '自动售后流程' and op['policy_revision'] is not None for op in audit['platform_operations'] if op['refund_id'] == target['id'])
            passed = passed and review_ok and operation_ok
            c.update(status='PASS' if passed else 'FAIL', finding=s['flow_message'], target_retest_complete=passed, automation_acceptance='PARTIAL_WITH_MANUAL_SETUP' if setup else 'TARGET_STEPS_VERIFIED')
            c.pop('retest_complete', None)
            rows.append({'case': key, 'scenario': c['title'], 'pass': passed, 'pass_scope': 'TARGET_STEPS_ONLY', 'has_manual_preconditions': any(row['manual_local_review'] for row in setup), 'preconditions': setup, 'target_review': target,
                         'business_state': b['state'], 'flow_state': s['flow_state'], 'message': s['flow_message'], 'stock': s['stock'], 'order_url': c['order_url'], 'refund_url': c['refunds']['target']['url'], 'refund_sn': b['refund_sn'], 'operations': s['operations']})
        self.save()
        from verify_merged_composite_gift_refunds import Acceptance
        prior = Acceptance(); p = self.data['protected']
        checks = {'older_merged_stock_unchanged': prior.snapshot() == p['merged_stock'], 'older_preceding_stock_unchanged': prior.protected_order() == p['preceding_stock'], 'old_settings_unchanged': self.settings_hash() == p['old_settings_hash'], 'queue_controls_unchanged': sql('SELECT business_type,desired_mode,concurrency FROM sys_mq_queue_policy ORDER BY business_type', 'ry-cloud') == p['queues'], 'job_controls_unchanged': sql('SELECT job_id,status,cron_expression FROM sys_job ORDER BY job_id', 'ry-cloud') == p['jobs']}
        dump(DEST / 'review-origin-audit.json', audit)
        dump(DEST / 'results.json', {'rows': rows, 'pass_scope': 'TARGET_STEPS_ONLY', 'whole_sequence_automation_acceptance': 'INCOMPLETE', 'review_audit': audit, 'protection': checks, 'retained': True, 'manual_target_review_or_pay': audit['manual_target_review_or_pay'], 'direct_stock_sql': False})
        print('TARGET STEPS', sum(r['pass'] for r in rows), '/', len(rows), '; automatic target reviews', audit['automatic_target_reviews'], '; manual prior reviews', audit['manual_prior_reviews'], '; full sequence automation INCOMPLETE', flush=True)
        assert all(checks.values()), checks


if __name__ == '__main__':
    run = Retest()
    run.ready()
    run.run_cases(sys.argv[1:] or list(run.data['cases']))
