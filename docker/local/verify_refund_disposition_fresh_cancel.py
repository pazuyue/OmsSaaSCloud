"""Retained, isolated automatic cancellation proving the new immutable stock effects."""
import json
from pathlib import Path
import verify_after_ship_refund_auto_cases as fixtures


def main():
    fixtures.OUT = Path(__file__).resolve().parents[2] / 'outputs/refund-disposition-ui/fresh-cancel'
    r = fixtures.Run()
    r.ready()
    r.protect()
    c = r.prepare('04')
    ref = r.inject(c, 'target', '100.00')
    observed = r.observe(c, ref, 220)
    assert observed['business_state'] == 'COMPLETED', observed
    for action in ['CANCEL_FEEDBACK', 'REVIEW', 'PAY']:
        assert action + '\tSUCCESS\t' in observed['platform_operations'], observed
    report = r.api('/order/refunds/business/' + str(ref['id']) + '/disposition')
    row, = report['cancellations']
    assert row['receipt_confirmed'] and row['canceled_quantity'] == 1, row
    assert row['logical_released_quantity'] == 1, row
    assert row['inventory_receipt']['stock_effects'][0]['logical_reservation_released'] == 1
    assert all(w['stock_receipt_confirmed'] for w in row['warehouses']), row
    stock = r.snapshot(c)
    assert stock['company'][0][1:] == ['100', '100', '0', '0'], stock
    assert stock['channel'][0][1:] == ['100', '0', '0', '100', '0'], stock
    b = r.api('/order/refunds/business/' + str(ref['id']))
    assert b['review_by'] == '自动售后规则', b['review_by']
    from verify_merged_composite_gift_refunds import Acceptance
    a = Acceptance(); p = r.data['protected']
    protection = {
        'earlier_stock_unchanged': a.snapshot() == p['merged_stock'] and a.protected_order() == p['preceding_stock'],
        'existing_settings_unchanged': r.settings_hash() == p['old_settings_hash'],
        'queues_unchanged': fixtures.sql('SELECT business_type,desired_mode,concurrency FROM sys_mq_queue_policy ORDER BY business_type', 'ry-cloud') == p['queues'],
        'jobs_unchanged': fixtures.sql('SELECT job_id,status,cron_expression FROM sys_job ORDER BY job_id', 'ry-cloud') == p['jobs'],
    }
    assert all(protection.values()), protection
    c.update(status='PASS', finished=True, finding='自动取消1件，原回执确认释放逻辑预占1件；公司库存回到100，零预占及分仓占用')
    r.save()
    fixtures.dump(fixtures.OUT / 'result.json', {'pass': True, 'case': c, 'observation': observed, 'disposition': report, 'stock': stock, 'protection': protection, 'boundary': '本地SIMULATION平台、TEST仓库，定向接入与唤醒；未验全局定时接入'})
    print('PASS automatic cancellation and exact stock effects; retained ' + ref['refund_sn'])


if __name__ == '__main__':
    main()
