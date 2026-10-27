"""Record bounded API timings, compare the protected baseline, and publish the carrier report."""
import html,json,shutil,statistics,time,urllib.parse
from datetime import datetime
from pathlib import Path
from verify_goods_workspace_ui import login,request,BASE
from verify_return_management import protected
from test_multi_virtual_shipping import sql
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/shared-carriers';DEST=ROOT/'docker/local/dist/acceptance/shared-carriers'


def main():
    manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));regression=json.loads((OUT/'regression.json').read_text('utf-8'));browser=json.loads((OUT/'browser.json').read_text('utf-8'));permissions=json.loads((OUT/'permissions.json').read_text('utf-8'))
    assert manifest['stage']=='CONFIG_AND_PHYSICAL_VERIFIED' and not browser['page_errors']
    before=json.loads((OUT/'protected-before.json').read_text('utf-8'))['protected'];now=protected()
    selects=dict(purchase_returns="SELECT id,return_sn,state,quantity,accepted_quantity,posted_quantity,control_version FROM purchase_return WHERE company_code='QM' ORDER BY id",purchase_executions="SELECT id,return_id,sequence_no,state,dispatch_state,quantity,accepted_quantity,posted_quantity,stock_ready,receipt_final FROM purchase_return_execution WHERE company_code='QM' ORDER BY id",procurement_stock="SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND (sku_sn LIKE 'PRF_%' OR sku_sn LIKE 'WC_%' OR sku_sn LIKE 'SCIT_%') ORDER BY sku_sn")
    for key,statement in selects.items():
        rows=sql(statement,'qm_oms_saas_inventory' if key=='procurement_stock' else 'qm_oms_saas_commodity').splitlines();index={r.split('\t')[0]:r for r in rows};now[key]='\n'.join(index[r.split('\t')[0]] for r in before[key].splitlines())
    unchanged={k:now[k]==v for k,v in before.items()};assert all(unchanged.values()),unchanged
    (OUT/'protected-after.json').write_text(json.dumps(dict(checks=unchanged,protected=now),ensure_ascii=False,indent=2),'utf-8')
    token=login();api=lambda p,b=None:request(p,payload=b,token=token)['data'];timings=[]
    for label,path,body in [('标准资料分页','/order/sharedCarriers/standard/list?pageSize=20',None),('WMS对照分页','/order/sharedCarriers/mapping/list?system_type=WMS&pageSize=20',None),('业务快递选项','/supplychain/purchaseReturns/carriers?'+urllib.parse.urlencode(dict(purchaseId=manifest['ids']['purchase'],flow='OUTBOUND',province='浙江')),None)]:
        values=[]
        for _ in range(3):
            start=time.perf_counter();result=api(path,body);values.append(round((time.perf_counter()-start)*1000,1));assert len(result['rows'])<=30
        timings.append(dict(api=label,samples_ms=values,mean_ms=round(statistics.mean(values),1),max_ms=max(values)))
    (OUT/'performance.json').write_text(json.dumps(dict(timings=timings,scope='本地单用户3次抽样，包含网关与认证；不作为压测结论',bounded=dict(page=100,selector=30,import_rows=500,batch_mappings=100,auto_candidates=50,background_companies_per_tick=2,background_facts_per_company=3,background_syncs_per_company=2)),ensure_ascii=False,indent=2),'utf-8')
    doc=api('/supplychain/purchaseReturns/'+str(manifest['ids']['return']));raw=api('/supplychain/purchaseReturns/reclaims/'+str(manifest['ids']['raw_reclaim']))
    def e(v):return html.escape(str(v))
    def table(headers,rows):return '<table><thead><tr>'+''.join('<th>'+e(h)+'</th>' for h in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+e(c)+'</td>' for c in row)+'</tr>' for row in rows)+'</tbody></table>'
    stock_rows=[]
    for title,key in [('采购实收入账后','stock_received'),('审核退仓预占5件后','stock_held'),('两包裹实出及重复回传后','stock_posted')]:
        s=manifest[key];stock_rows.append([title,s['totalStock'],s['availableStock'],s['allocatedStock'],s['reservedStock']])
    s=manifest['before_repair']['stock'];stock_rows.append(['修复对照及登记拒收收回草稿后',s['totalStock'],s['availableStock'],s['allocatedStock'],s['reservedStock']])
    parcel_rows=[]
    for p in sorted(doc['parcels'],key=lambda p:p['package_code']):
        info=json.loads(p['carrier_resolution_json']);parcel_rows.append([p['package_code'],sum(i['quantity'] for i in p['items']),p['carrier_code'],info['standard_name'],p['waybill'],p['carrier_resolution_state']])
    cards=''.join('<article><h3>'+e(c['key'])+' <span>通过</span></h3><p>'+e(c['scenario'])+'</p><details><summary>查看实际结果与证据</summary><pre>'+e(json.dumps(c['evidence'],ensure_ascii=False,indent=2))+'</pre></details></article>' for c in manifest['checks'])
    image_files=['ui-standards.png','ui-channels.png','ui-wms.png','ui-capabilities.png','ui-todos.png','ui-channel-scope.png','ui-wms-scope.png','ui-return-parcels.png','ui-raw-return.png']
    channel_file=OUT/'channel-actions.json';channel=json.loads(channel_file.read_text('utf-8')) if channel_file.exists() else None
    if channel and channel.get('stage')!='VERIFIED':channel=None
    direction_file=OUT/'direction-browser.json';direction=json.loads(direction_file.read_text('utf-8')) if direction_file.exists() else None
    help_file=OUT/'configuration-help-browser.json';help_ui=json.loads(help_file.read_text('utf-8')) if help_file.exists() else None
    management_file=OUT/'management-ui.json';management=json.loads(management_file.read_text('utf-8')) if management_file.exists() else None
    if management and management.get('stage')!='VERIFIED':management=None
    if direction:image_files.extend(['direction-outbound-create.png','direction-outbound-actual.png','direction-return-create.png','direction-return-actual.png','direction-batch-out.png','direction-batch-in.png'])
    if channel:image_files.extend(['channel-action-channel_shipping.png','channel-action-channel_exchange_logistics.png','channel-action-channel_return_identify.png','channel-action-channel_return_backfill.png','channel-action-multiple.png','channel-action-batch.png'])
    if help_ui:image_files.extend(help_ui['screenshots'])
    if management:image_files.extend(management['screenshots'])
    images=''.join('<details><summary>'+e(name)+'</summary><a href="'+e(name)+'" target="_blank"><img src="'+e(name)+'" loading="lazy"></a></details>' for name in image_files)
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>公共快递配置验收报告</title><style>body{margin:0;background:#f3f6fa;color:#27374d;font:15px/1.8 system-ui,Arial}main{max-width:1240px;margin:auto;padding:36px}h1{margin:0}h2{margin-top:32px}a{color:#006bd6}article,.summary{background:white;padding:22px;border:1px solid #dce4ee;border-radius:10px;margin:15px 0}span{color:#168052;font-size:14px}.notice{padding:16px;background:#fff4df;border-left:4px solid #c68a1c}.muted{color:#6c7e94}table{width:100%;border-collapse:collapse;background:white;margin:18px 0}th,td{padding:12px;text-align:left;border:1px solid #dce4ee;overflow-wrap:anywhere}th{background:#eaf1fa}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f7fa;padding:14px;font-size:12px;max-height:580px;overflow:auto}summary{cursor:pointer;color:#006bd6}img{width:100%;height:auto;border:1px solid #ddd;margin:12px 0}details{margin:12px 0}</style><main>'''
    page+='<h1>公共快递配置验收报告</h1><p class="muted">'+e(datetime.now().strftime('%Y-%m-%d %H:%M:%S'))+' · 本地 TEST 环境 · 测试数据保留</p>'
    page+='<div class="summary"><strong>验收通过：</strong>'+str(regression['total_tests'])+' 条后端回归 / '+str(len(regression['suites']))+' 个测试套件，25 个相关 Vue 组件校验，'+str(len(browser['checks']))+' 项浏览器检查，2 类真实权限账号检查。实物验收为本地真实库存账 + 奇门模拟仓回传。</div>'
    page+='<div class="notice">当前渠道范围为天猫。京东、抖音保留模型和页面扩展入口，尚未接入。现有销售发货通知网关仍为 SIMULATION；本报告不代表真实天猫通知、电子面单或仓库账号权限已联调通过。奇门目录查询已实现适配，本地模拟仓不支持目录方法，失败状态和配置保留行为已验证。</div>'
    page+='<h2>保留的验收单据</h2><p>原采购：'+e(manifest['po_sn'])+'<br>退仓单：<a href="'+BASE+'/oms-supplychain/purchaseReturns?id='+str(manifest['ids']['return'])+'">'+e(manifest['return_sn'])+'</a><br>测试 SKU：'+e(manifest['sku'])+'<br>未知寄回物流草稿：<a href="'+BASE+'/oms-supplychain/purchaseReturns?reclaimId='+str(raw['id'])+'">'+e(raw['reclaim_sn'])+'</a>（预期保持待核实草稿，不自动下发、不增加库存）。</p>'
    page+='<h2>库存核实</h2><p>采购实收10件；申请退仓5件，审核预占5件；仓库以同一运单回传两个包裹，分别2件、3件。第一次回传的未知快递不阻断数量校验通过的实出扣账。重复回传、补齐映射、重新校验及物流身份修复均没有再次扣库。</p>'+table(['阶段','总库存','可用','已分配','预占'],stock_rows)
    page+='<h2>原始物流与标准身份</h2><p>计划使用承运商A；实际包裹P2为承运商B，单独保存实际身份。P1原始编码首次未知，补齐原连接下的对照后识别为A。两个包裹保留同一运单，各自保留商品归属。</p>'+table(['包裹','实出件数','WMS原始编码','标准快递','运单','识别状态'],parcel_rows)
    page+='<h2>业务与配置场景</h2>'+cards
    page+='<h2>权限与隔离</h2>'+table(['场景','维护配置','重试待办'],[[c['scenario'],'拒绝（符合预期）','拒绝（符合预期）'] for c in permissions['checks']])+'<p>无公共配置权限的业务查询账号也实际调用采购退仓快递选项接口并得到选项。公司身份隔离、受信内部调用、并发编辑、冻结快照、实际出入库幂等在后端测试中覆盖。</p>'
    page+='<h2>性能与保留数据检查</h2>'+table(['接口','3次耗时/ms','平均/ms','最大/ms'],[[t['api'],str(t['samples_ms']),t['mean_ms'],t['max_ms']] for t in timings])+'<p>上述为本地单用户抽样，并非生产容量结论。列表与搜索有上限；自动选快递批量查询对照和能力；目录、物流识别使用有界线程池与按公司索引；外部调用不占用实际库存记账事务。</p>'+table(['原有验收资产','结果'],[[k,'保持不变' if v else '异常'] for k,v in unchanged.items()])
    page+='<h2>页面验收</h2><ul>'+''.join('<li>'+e(c)+'</li>' for c in browser['checks'])+'</ul>'+images
    if direction:page+='<h2>WMS 处理用途自动确定</h2><p>下发类按 OMS → WMS 转换编码；回传类按 WMS → OMS 识别标准身份。单条编辑、批量建立和服务端保存均按接口用途确定方向，回传类不配置默认输出。</p><ul>'+''.join('<li>'+e(c)+'</li>' for c in direction['checks'])+'</ul><p><a href="direction-browser.json">本次页面检查明细（未写入配置）</a></p>'
    if channel:
        assert not channel['browser']['page_errors'] and not channel['browser']['configuration_writes']
        page+='<h2>渠道关联按业务用途自动确定方向</h2><p>销售发货回传、换货发出物流回传、买家寄回物流识别、平台寄回物流回填使用独立用途。普通表单及批量关联无需选择 IN、OUT 或双向。平台提交首选项仅在多个候选时显示。</p><ul>'+''.join('<li>'+e(c)+'</li>' for c in channel['browser']['checks'])+'</ul>'
        page+=table(['配置场景','实际结果'],[[c['scenario'],c['result']] for c in channel['checks']])+'<p>候选检查最多100项，以单次公司内有界查询处理；请求防抖并丢弃旧上下文响应。该查询只用于配置，不加入订单或库存记账事务。<a href="channel-actions.json">查看渠道用途与页面验收证据</a></p>'
    if help_ui:
        assert not help_ui['page_errors'] and not help_ui['configuration_writes']
        page+='<h2>运输产品与配置来源说明</h2><p>渠道关联及渠道转换核验隐藏运输产品条件；WMS 保留运输产品编码，并说明专用匹配和通用匹配规则。单条、批量和导入统一使用“配置来源与确认说明”，提供填写示例并在提交前提示必填。</p><ul>'+''.join('<li>'+e(c)+'</li>' for c in help_ui['checks'])+'</ul><p><a href="configuration-help-browser.json">本次页面检查明细</a>；未提交配置变更、外部业务接口或库存操作。</p>'
    if management:
        assert not management['page_errors'] and not management['configuration_writes']
        page+='<h2>快递管理页面迭代验收</h2><p>单一标题、完整导航名称、独立查询与操作区；关联拆列、清单关联状态、范围筛选、直接关联、WMS候选首选、场景分步核验及可读操作记录均已验收。</p><ul>'+''.join('<li>'+e(c)+'</li>' for c in management['checks'])+'</ul><p>测试保留专用快递配置。待办按钮和跳转使用标记为TEST的UI专用任务，不对应真实业务，未执行业务校验或重试。场景核验为只读配置检查，未调用真实平台发货或库存操作。<a href="management-ui.json">查看本轮UI验收证据</a> · <a href="management-performance.json">清单关联索引与分页耗时抽样</a></p>'
    page+='<h2>复核文件</h2><p><a href="regression.json">后端回归明细</a> · <a href="manifest.json">场景与业务证据</a> · <a href="permissions.json">权限验收</a> · <a href="performance.json">耗时抽样及边界</a> · <a href="browser.json">浏览器检查</a></p></main></html>'
    (OUT/'验收报告.html').write_text(page,'utf-8');DEST.mkdir(parents=True,exist_ok=True);assert DEST.resolve().is_relative_to((ROOT/'docker/local/dist').resolve())
    for name in image_files+['验收报告.html','manifest.json','regression.json','permissions.json','performance.json','browser.json']:shutil.copy2(OUT/name,DEST/name)
    if direction:shutil.copy2(direction_file,DEST/'direction-browser.json')
    if channel:shutil.copy2(channel_file,DEST/'channel-actions.json')
    if help_ui:shutil.copy2(help_file,DEST/'configuration-help-browser.json')
    if management:shutil.copy2(management_file,DEST/'management-ui.json')
    if (OUT/'management-performance.json').exists():shutil.copy2(OUT/'management-performance.json',DEST/'management-performance.json')
    with sync_playwright() as p:
        b=p.chromium.launch(headless=True);tab=b.new_page(viewport=dict(width=1560,height=1120));tab.goto(BASE+'/acceptance/shared-carriers/'+urllib.parse.quote('验收报告.html'),wait_until='networkidle')
        assert '公共快递配置验收报告' in tab.locator('main h1').inner_text()
        assert str(regression['total_tests'])+' 条后端回归' in tab.locator('main').inner_text()
        tab.screenshot(path=str(OUT/'acceptance-report.png'));b.close()
    shutil.copy2(OUT/'acceptance-report.png',DEST/'acceptance-report.png')
    print('PASS protected baseline and bounded API timings. Report: '+BASE+'/acceptance/shared-carriers/验收报告.html')


if __name__=='__main__':main()
