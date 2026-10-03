#!/usr/bin/env python3
"""Validate the documentation package. Does not build the not-yet-written GitNova product."""
from pathlib import Path
import csv,hashlib,json,os,re,shutil,subprocess,sys,tempfile,tomllib,xml.etree.ElementTree as ET
from datetime import datetime,timezone
D=Path(__file__).resolve().parents[1]
A=D/'audit';A.mkdir(exist_ok=True)
checks=[]
def check(name,fn):
 try:
  detail=fn();checks.append(dict(name=name,status='PASS',detail=detail))
 except Exception as e:checks.append(dict(name=name,status='FAIL',detail=repr(e)))
def command(argv,cwd=None):
 r=subprocess.run(argv,cwd=cwd,capture_output=True,text=True,timeout=40)
 if r.returncode:raise RuntimeError(f'{argv}\n{r.stdout}\n{r.stderr}')
 return r.stdout.strip() or r.stderr.strip()
plan=json.loads((D/'contracts/file-plan.json').read_text())
def data_files():
 js=list(D.rglob('*.json'));xml=list(D.rglob('*.xml'));toms=list(D.rglob('*.toml'))
 for f in js:
  if '/audit/' not in str(f):json.loads(f.read_text())
 for f in xml:ET.parse(f)
 for f in toms:tomllib.loads(f.read_text())
 return {'json':len(js),'xml':len(xml),'toml':len(toms)}
def plan_test():
 paths=[x['path'] for x in plan];assert len(set(paths))==len(paths)
 manual=(D/'02_逐步手写开发与旧链路退役.md').read_text();mapping=(D/'04_源码迁移与包文件地图.md').read_text()
 for x in plan:
  assert x['path'] in manual,('missing construction card',x['path'])
  assert x['path'] in mapping,('missing map',x['path'])
 assert [int(x) for x in re.findall(r'^## 步骤 (\d+)：',manual,re.M)]==list(range(19))
 assert 'EventSink events' not in manual
 assert 'public interface ToolRuntime' not in manual
 assert 'XML target=' not in manual
 stages={x['name']:x['step'] for x in plan}
 assert stages['SessionSealer'] < stages['OpenSandboxControlAdapter'] < stages['SessionLifecycleService']
 assert stages['AgentWorkerClient'] <= stages['SessionLifecycleService']
 assert stages['RepositoryBootstrapService'] <= stages['SessionLifecycleService']
 assert stages['ToolRuntime'] <= stages['DefaultAgentEngine']
 assert stages['WorkerResources'] <= stages['AgentWorkerServer']
 for f in (D/'reference').glob('*/src/main/java/**/*.java'):
  package=re.search(r'^package ([\w.]+);',f.read_text(),re.M)
  assert package and str(f).endswith(package[1].replace('.','/')+'/'+f.name),str(f)
  relative=str(f.relative_to(D/'reference'))
  target=relative[len('server-control/'):] if relative.startswith('server-control/') else 'agent-runtime/'+relative
  assert target in paths,('reference not planned',target)
 return {'planned_files':len(paths),'steps':19,'java_reference_files':len(list((D/'reference').rglob('*.java')))}
def links():
 local=0
 for f in D.rglob('*.md'):
  text=f.read_text()
  assert text.count('```')%2==0,('unclosed code fence',str(f))
  for raw in re.findall(r'(?<!!)\[[^\]\n]*\]\(([^)]+)\)',text):
   link=raw.strip().strip('<>')
   if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:',link) or link.startswith('#'):continue
   path=link.split('#')[0]
   if not path:continue
   assert (f.parent/path).exists(),(str(f.relative_to(D)),link)
   local+=1
 return {'local_links':local}
def schema_test():
 s=(D/'schema/agent_control.sql').read_text()
 tables=re.findall(r'CREATE TABLE (\w+)',s);assert len(tables)==10 and len(set(tables))==10
 assert not re.search(r'\bDROP\b',re.sub(r'--[^\n]*','',s),re.I)
 # Static balancing of parenthesis outside quotes/comments (not a MySQL parser).
 t=re.sub(r'--[^\n]*','',s);t=re.sub(r"'(?:''|[^'])*'", "''",t)
 depth=0
 for c in t:
  if c=='(':depth+=1
  elif c==')':depth-=1
  assert depth>=0
 assert depth==0
 def enums_java(file,which):
  body=re.search(r'enum '+which+r'\s*\{([^}]+)\}',file.read_text())[1]
  return set(re.findall(r'\b[A-Z][A-Z_]+\b',body))
 taskfile=D/'reference/agent-protocol/src/main/java/com/gitnova/agent/protocol/query/TaskView.java'
 for cname,etype in [('chk_ctask_status','Execution'),('chk_ctask_settlement','Settlement'),('chk_ctask_publication','Publication')]:
  # fallback actual names shown in reference, never invent passed check
  java=taskfile.read_text()
  enames=re.findall(r'enum (\w+)',java)
  which=next((n for n in enames if n.lower().startswith(etype.lower())),None)
  assert which,(etype,enames)
  sqlvalues=set(re.findall(r"'([A-Z_]+)'",re.search(cname+r' CHECK\([^\n]+',s)[0]))
  assert sqlvalues==enums_java(taskfile,which),(cname,sqlvalues,enums_java(taskfile,which))
 names=set(tables)|{'agent_session','repository'}
 assert set(re.findall(r'REFERENCES (\w+)',s))<=names
 return {'tables':len(tables),'scope':'static structure/enum/FK-name check; no database execution'}
def fixture_test():
 try:import jsonschema
 except ImportError:return {'status':'SKIPPED','reason':'install jsonschema to validate schema; JSON parsed only'}
 schema=json.loads((D/'contracts/command.schema.json').read_text())
 validator=jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker())
 valid=list((D/'fixtures').glob('*.json'))
 for f in valid:validator.validate(json.loads(f.read_text()))
 v=json.loads((D/'examples/submit-task.json').read_text());validator.validate(v)
 broken=[]
 for mutate in [lambda x:x.update(type='EXEC_SHELL'),lambda x:x.update(taskId=None),lambda x:x['payload'].update(mode='ADMIN'),lambda x:x.update(extra='x'),lambda x:x.update(runnerEpoch=0),lambda x:x['payload'].update(deadlineAt='not-date')]:
  x=json.loads(json.dumps(v));mutate(x);assert list(validator.iter_errors(x));broken.append(1)
 return {'valid_commands':len(valid)+1,'rejected_invalid':len(broken),'note':'cross-state authorization/digest tests remain implementation tests'}
def contracts_compile():
 with tempfile.TemporaryDirectory() as td:
  td=Path(td);out=td/'classes';out.mkdir()
  counts={}
  for mod,release in [('agent-protocol','17'),('agent-core','21'),('agent-worker','21'),('server-control','17')]:
   files=list((D/'reference'/mod).rglob('*.java'));counts[mod]=len(files)
   args=['javac','--release',release,'-encoding','UTF-8','-cp',str(out),'-d',str(out)]+[str(f) for f in files]
   command(args)
  return counts

def http_probe():
 with tempfile.TemporaryDirectory() as td:
  command(['javac','--release','17','-encoding','UTF-8','-d',td,str(D/'probes/TransportProbe.java')])
  return command(['java','-cp',td,'TransportProbe'])
def scripts_test():
 with tempfile.TemporaryDirectory() as td:
  root=Path(td);mig=root/'src/main/resources/db/migration';mig.mkdir(parents=True)
  (mig/'V5__existing.sql').write_text('-- existing\n')
  read=command([sys.executable,str(D/'scripts/install_migration.py'),str(root)])
  assert not (mig/'V6__agent_sandbox_control.sql').exists()
  command([sys.executable,str(D/'scripts/install_migration.py'),str(root),'--write'])
  assert (mig/'V6__agent_sandbox_control.sql').exists()
  repeat=command([sys.executable,str(D/'scripts/install_migration.py'),str(root),'--write'])
  assert len(list(mig.glob('*.sql')))==2 and 'Already installed' in repeat
  rows=list(csv.DictReader((D/'contracts/migration-map.csv').open(encoding='utf-8-sig')))
  path=root/rows[0]['old_path'];path.parent.mkdir(parents=True,exist_ok=True);path.write_text('package x;\nimport java.util.List;\nclass X { /* handleNaturalStop */ }')
  unknown=root/'src/main/java/com/gitnova/service/agent/NewLocal.java';unknown.parent.mkdir(parents=True,exist_ok=True);unknown.write_text('class NewLocal {}')
  inventory=json.loads(command([sys.executable,str(D/'scripts/inventory_current.py'),str(root)]))
  assert any(f['status']=='UNMAPPED_LOCAL_FILE' for f in inventory['files'])
  assert any(f['status']=='MATCHED_PATH' for f in inventory['files'])
  return {'migration':'dry-run, new-file write, repeated write idempotent','inventory':'known/missing/unknown fixtures; no real latest worktree'}
def acceptance():
 text=(D/'03_验收矩阵与资料依据.md').read_text();ids=re.findall(r'^\| ([A-Z]+\d+) \|',text,re.M)
 assert len(ids)==len(set(ids));assert len(ids)>=88
 return {'documented_cases':len(ids),'executed_product_cases':0}
check('JSON_XML_TOML',data_files)
check('FILE_PLAN_STEPS_AND_PACKAGES',plan_test)
check('LOCAL_LINKS_AND_FENCES',links)
check('DDL_STATIC_CHECK',schema_test)
check('COMMAND_SCHEMA_FIXTURES',fixture_test)
check('JAVA_CONTRACT_COMPILATION',contracts_compile)
check('JDK_LOOPBACK_TRANSPORT_PROBE',http_probe)
check('LINUX_LAUNCHER_PROBE',lambda:command([sys.executable,str(D/'scripts/test_process_launcher.py')]))
check('SAFE_MIGRATION_AND_INVENTORY_SCRIPTS',scripts_test)
check('ACCEPTANCE_ID_COVERAGE',acceptance)
missing=[name for name in ['docker','mvn','mysql'] if not shutil.which(name)]
report={'checked_at_utc':datetime.now(timezone.utc).isoformat(),'checks':checks,
 'product_execution':'NOT_RUN','sdk_probe_compile':'NOT_RUN','opensandbox_live':'NOT_RUN','mysql_migrations':'NOT_RUN',
 'missing_environment_commands':missing,
 'overall':'PASS' if all(x['status']=='PASS' for x in checks) else 'FAIL',
 'meaning':'PASS covers this document package and isolated probes only, not the final product or live SDK deployment.'}
(A/'self-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
for c in checks:print(c['status'],c['name'],str(c['detail'])[:240])
print('REPORT',A/'self-check.json')
sys.exit(0 if report['overall']=='PASS' else 1)
