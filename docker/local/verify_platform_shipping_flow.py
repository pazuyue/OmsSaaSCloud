"""Opt-in retained simulation acceptance. Does not contact Taobao or post warehouse quantities."""
import json
import sys
import time
from pathlib import Path
from verify_goods_workspace_ui import login, request

SN = 'XS870a08cfdade45b5be4ad60d3cc802ab'
OUTPUT = Path(__file__).resolve().parents[2] / 'outputs/platform-shipping'


def signature(row):
    return {key: row.get(key) for key in ['status', 'control_version', 'shipped_quantity', 'packaged_quantity', 'packages', 'lines', 'outbound_records', 'wms_dispatch_state', 'wms_warehouse_status']}


def main():
    if sys.argv[1:] != ['--enroll-retained']:
        raise SystemExit('Use --enroll-retained to explicitly enroll the retained TEST- document')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    token = login()
    try:
        before = request('/order/orders/shipments/3', token=token)['data']
        assert before['shipment_sn'] == SN and before['status'] == 'SHIPPED'
        assert before['shipped_quantity'] == before['packaged_quantity'] == 6
        assert len(before['packages']) == 1 and before['packages'][0]['express_code'] == 'TEST-SF-' + SN
        assert before['warehouse_target']['environment'] == 'TEST'
        config = request('/order/orders/platform-shipping-config/1', token=token)['data']
        config['mode'] = 'SIMULATION'
        config['carrier_mapping']['SF'] = 'SF'
        request('/order/orders/platform-shipping-config/1', token=token, payload=config)
        path = '/order/orders/shipments/3/platform-shipping'
        report = request(path, token=token, method='POST')['data']
        assert len(report['tasks']) == 1
        request_id = report['tasks'][0]['request_id']
        replay = request(path, token=token, method='POST')['data']
        assert len(replay['tasks']) == 1 and replay['tasks'][0]['request_id'] == request_id
        for _ in range(30):
            report = request(path, token=token)['data']
            if report['tasks'][0]['state'] == 'SUCCESS':
                break
            time.sleep(2)
        task = report['tasks'][0]
        assert task['state'] == 'SUCCESS' and task['mode'] == 'SIMULATION'
        assert task['quantity'] == 6 and task['attempts'] == 1
        assert task['platform_receipt'].startswith('SIMSHIP_')
        assert task['snapshot']['platform_tid'] == '9001717917894739110'
        assert task['snapshot']['goods'] == {'9006371393940767110': 2, '9007664503219726110': 4}
        assert task['snapshot']['notification_type'] == 'PACKAGE_INCREMENT'
        assert len([log for log in report['logs'] if log['action'] == 'SEND']) == 1
        after = request('/order/orders/shipments/3', token=token)['data']
        assert signature(before) == signature(after), 'Warehouse quantity, package or stock evidence changed'
        assert after['platform_shipping_state'] == 'SUCCESS' and after['platform_shipping_success_quantity'] == 6
        result = {'shipment_sn': SN, 'request_id': request_id, 'simulated_platform_receipt': task['platform_receipt'], 'actual_quantity': 6, 'goods': task['snapshot']['goods'], 'attempts': task['attempts'], 'warehouse_data_unchanged': True, 'real_platform_notification_sent': False}
        (OUTPUT / 'retained-acceptance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
        print(json.dumps(result, ensure_ascii=False))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
