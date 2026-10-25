from pathlib import Path
import hashlib,json,shutil,xml.etree.ElementTree as ET
r=Path.cwd();o=r/'docs/evidence/iam-stage-3-review-fixes';audit={}
for src,dest,names in [('red-xml','red-selected-xml',['CurrentAuthorityClientTest','ResponseAuthorityTest']),('green-named-xml','green-named-selected-xml',['CurrentAuthorityClientTest','ResponseAuthorityTest','ServiceTicketProviderTest'])]:
 (o/dest).mkdir(exist_ok=True)
 for n in names:shutil.copyfile(o/src/('TEST-cn.agentplatform.common.authority.'+n+'.xml'),o/dest/('TEST-cn.agentplatform.common.authority.'+n+'.xml'))
for d in ['red-selected-xml','green-named-selected-xml','green-renewal-xml','final-common-xml']:
 suites=[]
 for p in sorted((o/d).glob('TEST-*.xml')):
  e=ET.parse(p).getroot();suites.append({'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),**{k:int(e.attrib[k]) for k in ['tests','failures','errors','skipped']},'methods':[t.attrib['name'] for t in e.findall('testcase')]})
 audit[d]={'suites':suites,'total':{k:sum(s[k] for s in suites) for k in ['tests','failures','errors','skipped']}}
assert audit['red-selected-xml']['total']=={'tests':7,'failures':6,'errors':0,'skipped':0}
assert audit['green-named-selected-xml']['total']=={'tests':38,'failures':0,'errors':0,'skipped':0}
assert audit['final-common-xml']['total']=={'tests':121,'failures':0,'errors':0,'skipped':0}
(o/'xml-audit.json').write_text(json.dumps(audit,indent=2),encoding='utf8')
p=r/'docs/evidence/iam-stage-3-review-fixes.md';s=p.read_text(encoding='utf-8-sig').replace('两类 XML `red-xml/`','两类 XML `red-selected-xml/`').replace('`green-named-xml/` 保留当轮 XML','`green-named-selected-xml/` 保留当轮三类 XML');s=s.replace('编辑辅助脚本首次读取','初始 `red-xml/`、`green-named-xml/` 是通配符归档，另含 surefire 中继承的未运行类 XML；保留原归档但不合计。精确当轮 XML 位于上述 selected 目录，`xml-audit.json` 按指定类/方法核对 7、38、1、121 四轮数值，没有把继承 XML 拼入当轮结果。\n\n编辑辅助脚本首次读取');p.write_text(s,encoding='utf8')
print({d:a['total'] for d,a in audit.items()})
