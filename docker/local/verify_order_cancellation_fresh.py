"""An isolated full automatic cancellation must also end the original order lifecycle."""
from pathlib import Path
import verify_after_ship_refund_auto_cases as fixtures


def main():
    fixtures.OUT = Path(__file__).resolve().parents[2] / 'outputs/order-cancellation/fresh'
    r = fixtures.Run(); r.ready(); r.protect()
    c = r.prepare('04'); ref = r.inject(c,'target','100.00')
    observation = r.observe(c,ref,220)
    assert observation['business_state'] == 'COMPLETED', observation
    b = r.api('/order/refunds/business/'+str(ref['id']))
    assert b['review_by'] == '自动售后规则'
    order = r.api('/order/orders/business/'+str(c['order_id']))
    assert order['status'] == order['business_status'] == 'VOIDED', order['status']
    assert order['inventory_status'] == order['logical_inventory_status'] == 'RELEASED'
    for field in ['review_required','can_review','can_hold','can_resume','can_reserve','can_allocate','can_generate_shipments']:
        assert order[field] is False, field
    stock = r.snapshot(c)
    assert stock['company'][0][1:] == ['100','100','0','0'],stock
    assert stock['channel'][0][1:] == ['100','0','0','100','0'],stock
    assert r.settings_hash() == r.data['protected']['old_settings_hash']
    p=r.data['protected']
    assert fixtures.sql('SELECT business_type,desired_mode,concurrency FROM sys_mq_queue_policy ORDER BY business_type','ry-cloud')==p['queues']
    assert fixtures.sql('SELECT job_id,status,cron_expression FROM sys_job ORDER BY job_id','ry-cloud')==p['jobs']
    from verify_merged_composite_gift_refunds import Acceptance
    earlier=Acceptance()
    assert earlier.snapshot()==p['merged_stock'] and earlier.protected_order()==p['preceding_stock']
    c.update(status='PASS',finished=True);r.save()
    fixtures.dump(fixtures.OUT/'result.json',{'pass':True,'case':c,'refund':b,'order':order,'stock':stock,'observation':observation,'boundary':'SIMULATION平台、TEST仓库，定向接入及唤醒，无手工审核退款、无手工取消商品、无全局扫描'})
    print('PASS fresh automatic refund ends original order; retained '+order['order_sn'])


if __name__ == '__main__': main()
