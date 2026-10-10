"""Prepare one explicitly named local store for simulation; never invent real platform credentials."""
import json
import time
from local_db import execute, docker_db, quote, HERE

def main():
    database='qm_oms_saas_channel'
    name='青木天猫旗舰店'
    rows=execute("SELECT channel_id,company_code FROM t_channel WHERE channel_name="+quote(name)+" AND channel_type='TM'",database).strip().splitlines()
    assert len(rows)==1,'Expected exactly one named Tmall store'
    channel,company=rows[0].split('\t');channel=int(channel)
    busy=execute(f"SELECT COUNT(*) FROM channel_platform_binding WHERE channel_id={channel} AND operation_until>NOW()",database).strip()
    assert busy=='0','Store is currently interacting; retry when idle'
    real=execute(f"SELECT COUNT(*) FROM channel_platform_binding b JOIN channel_platform_app a ON a.id=b.app_id WHERE b.channel_id={channel} AND a.simulation_mode=0 AND b.token_cipher IS NOT NULL",database).strip()
    assert real=='0','A real authorization is present; refusing to overwrite it with simulation'
    backup=HERE/'logs'/f'tmall-simulation-before-{int(time.time())}.sql'
    with backup.open('wb') as output:
        docker_db('mysqldump','--single-transaction','--no-create-info','--skip-add-locks',f'--where=channel_id={channel}',database,'t_channel','channel_platform_binding','channel_oauth_state',output=output)
    app_name='天猫本地模拟（青木天猫旗舰店）'
    found=execute("SELECT id FROM channel_platform_app WHERE company_code="+quote(company)+" AND name="+quote(app_name)+" AND simulation_mode=1",database).strip()
    if not found:
        found=execute("INSERT INTO channel_platform_app(company_code,name,enabled,simulation_mode,article_code,item_code) VALUES("+quote(company)+','+quote(app_name)+",1,1,'SIMULATED_SERVICE','SIMULATED_ITEM'); SELECT LAST_INSERT_ID();",database).strip()
    app=int(found)
    sid=str(900000000+channel)
    existing=execute(f'SELECT COUNT(*) FROM channel_platform_binding WHERE channel_id={channel}',database).strip()=='1'
    sql=f"UPDATE t_channel SET enabled=1,to_channel_enabled=1 WHERE channel_id={channel} AND company_code={quote(company)}; "
    if existing:
        sql+=f"UPDATE channel_platform_binding SET app_id={app},expected_shop_id={quote(sid)} WHERE channel_id={channel} AND company_code={quote(company)}; "
    else:
        sql+=f"INSERT INTO channel_platform_binding(channel_id,company_code,app_id,expected_shop_id) VALUES({channel},{quote(company)},{app},{quote(sid)}); "
    sql+=f"UPDATE channel_platform_app SET enabled=1 WHERE id={app} AND simulation_mode=1; DELETE FROM channel_oauth_state WHERE channel_id={channel} AND company_code={quote(company)};"
    execute('START TRANSACTION; '+sql+' COMMIT;',database)
    (HERE/'logs/tmall-simulation-target.json').write_text(json.dumps({'channelId':channel,'appId':app,'name':name,'companyCode':company,'simulation':True},ensure_ascii=False),encoding='utf-8')
    print('Prepared local simulated store:',name,'channel',channel,'app',app,'backup',backup.name)

if __name__=='__main__':main()
