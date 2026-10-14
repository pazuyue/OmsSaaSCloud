"""Keep OMS production routing mutations inside the shared company resolver."""
import re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
RESOLVER='oms-common-core/src/main/java/com/oms/common/tenant/TenantRouting.java'
NON_CODE=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27')
RULES={
    'direct datasource context mutation':r'\bDynamicDataSourceContextHolder\s*\.\s*(?:push|poll|clear)\s*\(',
    'static datasource mutation import':r'\bimport\s+static\s+[\w.]*DynamicDataSourceContextHolder\s*\.\s*(?:push|poll|clear|\*)\s*;',
    'datasource annotation bypass':r'@(?:com\.baomidou\.dynamic\.datasource\.annotation\.)?DS\s*\(',
    'direct pool selection':r'\.\s*(?:getDataSources|getGroupDataSources)\s*\(',
}

def violations(source):
    code=NON_CODE.sub(lambda m:'\n'*m.group().count('\n'),source)
    return [name for name,pattern in RULES.items() if re.search(pattern,code)]

def main():
    modules=ROOT/'oms-modules';failures=[]
    for file in sorted(modules.glob('*/src/main/java/**/*.java')):
        relative=file.relative_to(modules).as_posix()
        if relative==RESOLVER:continue
        for rule in violations(file.read_text(encoding='utf8')):failures.append(relative+': '+rule)
    if failures:raise SystemExit('Tenant routing boundary violation:\n'+'\n'.join(failures))
    print('PASS OMS tenant routing boundary: datasource selection stays in TenantRouting')

if __name__=='__main__':main()
