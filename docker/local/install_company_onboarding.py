"""Local-only onboarding installation. Backs up platform DB/config/binaries; preserves business rows and jobs."""
import json,subprocess,shutil,sys
from datetime import datetime
from local_db import HERE,execute,docker_db,quote
from configure_order import nacos_get,column
from seed_business import ensure_menu

def main():
    assert sys.argv[1:]==['--apply'],'Usage: install_company_onboarding.py --apply'
    folder=HERE/'backups'/('company-onboarding-'+datetime.now().strftime('%Y%m%d-%H%M%S'));folder.mkdir(parents=True)
    with (folder/'ry-cloud.sql').open('wb') as output:docker_db('mysqldump','--single-transaction','--routines','--triggers','ry-cloud',output=output)
    for app in ['oms-order','oms-inventory','oms-supplychain','oms-modules-goods','oms-channel','ruoyi-system','ruoyi-auth']:
        (folder/(app+'-dev.yml')).write_text(nacos_get(app+'-dev.yml'),encoding='utf8')
    for name in ['order','inventory','supplychain','goods','channel','system','auth']:
        subprocess.run(['docker','cp','oms-local-'+name+':/app/app.jar',str(folder/(name+'.jar'))],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if (HERE/'dist/index.html').exists():shutil.copytree(HERE/'dist',folder/'dist')
    execute((HERE/'migrations/20261014_company_onboarding.sql').read_text(encoding='utf8'))
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sys_company_release' AND index_name='idx_release_state'").strip()=='0':execute('ALTER TABLE sys_company_release ADD INDEX idx_release_state(state,company_code)')
    column('ry-cloud','sys_company','runtime_ready',"TINYINT NOT NULL DEFAULT 0 COMMENT '列表生效摘要'")
    column('ry-cloud','sys_company','last_detect_at',"BIGINT NULL COMMENT '最近检测时间戳毫秒'")
    # Bootstrap the explicitly known current company only; never infer tenants from business tables.
    execute("INSERT IGNORE INTO sys_company(company_code,company_name,status,ever_enabled,release_route_revision,created_by,updated_by) VALUES ('QM','QM（现有公司）','ENABLED',1,1,'local-deploy','local-deploy')")
    for key in ['order','inventory','supplychain','goods','channel']:execute("INSERT IGNORE INTO sys_company_service_route(company_code,service_key,datasource_alias) VALUES ('QM',"+quote(key)+",'master')")
    parent=execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='system' LIMIT 1").strip();assert parent,'System management parent missing'
    menu=ensure_menu('公司管理',int(parent),'company','system/company/index',permission='system:company:list',order=1)
    ids=[menu]
    for index,(action,label) in enumerate([('query','查看公司配置'),('add','新增公司'),('edit','修改公司'),('check','校验公司接入'),('publish','发布公司配置'),('enable','启用公司'),('disable','停用公司'),('recover','恢复公司发布')]):
        ids.append(ensure_menu(label,menu,'',kind='F',permission='system:company:'+action,order=index+1))
    for ident in ids:execute('INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,'+str(ident)+')')
    # No company route changes and no scheduler operations here.
    print(json.dumps({'installed':True,'menu_id':menu,'bootstrap_company':'QM','backup':str(folder),'jobs_untouched':True},ensure_ascii=False))
if __name__=='__main__':main()
