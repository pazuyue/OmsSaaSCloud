"""Repair only the named retained test order, using its already confirmed cancellation facts."""
import json
from pathlib import Path
from verify_goods_workspace_ui import login, request
from test_multi_virtual_shipping import sql, quote
import verify_after_ship_refund_auto_cases as fixtures

OUT = Path(__file__).resolve().parents[2] / 'outputs/order-cancellation'
ORDER = 'qm_oms_saas_order'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    def api(path): return request(path, token=token)['data']
    before = api('/order/orders/business/151')
    assert before['order_sn'] == 'SO3c4bbe2730894fceb8bbbb61e6496d5b'
    refund = api('/order/refunds/business/105')
    assert refund['order_id'] == 151 and refund['state'] == 'COMPLETED' and refund['funds_state'] == 'SUCCESS'
    disposition = api('/order/refunds/business/105/disposition')
    row, = disposition['cancellations']
    assert row['receipt_confirmed'] and row['canceled_quantity'] == row['logical_released_quantity'] == 1
    case = json.loads((OUT.parent / 'refund-disposition-ui/fresh-cancel/manifest.json').read_text('utf-8'))['cases']['04']
    run = fixtures.Run.__new__(fixtures.Run)
    stock = run.snapshot(case)
    saved = OUT / 'named-order-before.json'
    if not saved.exists(): fixtures.dump(saved, {'order': before, 'refund': refund, 'stock': stock, 'disposition': disposition})
    if before['status'] != 'VOIDED':
        assert before['status'] == 'WAIT_ALLOCATION' and before['allocation_quantity'] == 0
        assert before['demand_quantity'] == before['cancelled_quantity'] == 1 and before['shipped_quantity'] == before['reserved_quantity'] == 0
        v = int(before['control_version'])
        reason = '退款 '+refund['refund_sn']+' 原取消回执已确认；修正原订单结束履约状态，不重复释放库存'
        result = sql(f"""START TRANSACTION;
SELECT id FROM oms_platform_order WHERE company_code='QM' AND id={int(before['platform_order_id'])} FOR UPDATE;
SELECT id FROM oms_order WHERE company_code='QM' AND id=151 FOR UPDATE;
SET @valid=(SELECT COUNT(*) FROM oms_refund WHERE company_code='QM' AND id=105 AND order_id=151 AND state='COMPLETED' AND funds_state='SUCCESS');
SET @valid=@valid AND (SELECT COUNT(*) FROM oms_order_line WHERE company_code='QM' AND order_id=151 AND business_type='SALE' AND quantity=1 AND cancelled_quantity=1 AND reserved_quantity=0)=1;
SET @valid=@valid AND NOT EXISTS(SELECT 1 FROM oms_order_shipment sh JOIN oms_order_shipment_line sl ON sl.company_code=sh.company_code AND sl.shipment_id=sh.id WHERE sh.company_code='QM' AND sh.order_id=151 AND (sl.shipped_quantity>0 OR sh.status NOT IN ('VOIDED','CLOSED')));
SET @valid=@valid AND (SELECT COUNT(*) FROM oms_refund_goods_task WHERE company_code='QM' AND refund_id=105 AND action='CANCEL' AND quantity=1 AND state='DONE' AND request_id={quote(row['receipt_request_id'])})=1;
UPDATE oms_order SET status='VOIDED',inventory_status='RELEASED',allocation_status='NONE',stock_attempt_status='DONE',cutoff_state='COMPLETED',void_at=NOW(),void_reason={quote(reason)},void_by='退款状态修复',control_version=control_version+1 WHERE company_code='QM' AND id=151 AND order_sn='SO3c4bbe2730894fceb8bbbb61e6496d5b' AND status='WAIT_ALLOCATION' AND control_version={v} AND allocation_quantity=0 AND @valid;
SET @changed=ROW_COUNT();
INSERT INTO oms_order_process_log(company_code,platform_order_id,action,result,message,operator_name) SELECT 'QM',{int(before['platform_order_id'])},'REFUND_ORDER_COMPLETE','SUCCESS',{quote(reason)},'退款状态修复' WHERE @changed=1;
COMMIT;
SELECT @changed;""", ORDER)
        assert result.splitlines()[-1] == '1', 'Repair preconditions changed; no broad correction attempted'
    after = api('/order/orders/business/151')
    assert after['status'] == after['business_status'] == 'VOIDED'
    assert after['inventory_status'] == after['logical_inventory_status'] == 'RELEASED'
    for key in ['can_review','can_hold','can_resume','can_allocate','can_generate_shipments','can_reserve','review_required']:
        assert after[key] is False, key
    for field in ['review_status','review_decision','review_by','review_at','review_snapshot','review_events']:
        assert before.get(field) == after.get(field), field
    assert refund == api('/order/refunds/business/105'), 'Repair changed the completed refund'
    assert stock == run.snapshot(case), 'Repair changed inventory'
    fixtures.dump(OUT / 'named-order-result.json', {'pass':True,'order':after,'refund_and_stock_unchanged':True,'approval_history_unchanged':True})
    print('PASS named order151 is closed to further fulfillment; refund, stock and approval history unchanged')


if __name__ == '__main__': main()
