"""A fresh, retained unshipped case proves the corrected platform feedback envelope end to end."""
import json
from pathlib import Path
import verify_after_ship_refund_auto_cases as fixtures


def main():
    fixtures.OUT = fixtures.OUT / 'retest/fresh-cancel'
    r = fixtures.Run()
    r.ready()
    r.protect()
    c = r.prepare('04')
    ref = r.inject(c, 'target', '100.00')
    observed = r.observe(c, ref, 220)
    assert observed['business_state'] == 'COMPLETED', observed
    for action in ['CANCEL_FEEDBACK', 'REVIEW', 'PAY']:
        assert action + '\tSUCCESS\t' in observed['platform_operations'], observed
    assert 'UNKNOWN' not in observed['platform_operations']
    stock = r.snapshot(c)
    assert stock['company'][0][1:] == ['100', '100', '0', '0'], stock
    assert stock['channel'][0][1:] == ['100', '0', '0', '100', '0'], stock
    c.update(status='PASS', finding='新单自动截停、释放占用、明确回告成功、审核付款并完成，平台操作均明确成功', finished=True)
    r.save()
    fixtures.dump(fixtures.OUT / 'result.json', {'pass': True, 'case': c, 'observation': observed, 'stock': stock})
    print('PASS fresh automatic cutoff, feedback, review, payment and stock; retained ' + ref['refund_sn'])


if __name__ == '__main__':
    main()
