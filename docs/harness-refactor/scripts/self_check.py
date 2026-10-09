#!/usr/bin/env python3
"""Offline handbook/contract audit; NEVER marks product acceptance as PASS."""
from pathlib import Path
import argparse,copy,datetime,hashlib,json,os,re,shutil,subprocess,sys,tempfile,time,traceback
import xml.etree.ElementTree as ET
import tomllib
from urllib.parse import unquote
try:
 from jsonschema import Draft202012Validator,FormatChecker
except ImportError:
 raise SystemExit('Requires jsonschema in your Python environment; do not install into production runtime. See audit/运行自检.md')
ROOT=Path(__file__).resolve().parents[1]
REPORT=[];SCHEMAS={};TMP=None;DETAIL={}
def text(p):return (ROOT/p).read_text(encoding='utf-8')
def js(p):return json.loads(text(p))
def require(ok,msg):
 if not ok:raise AssertionError(msg)
def run(cmd,timeout=40,cwd=None):
 r=subprocess.run([str(x) for x in cmd],capture_output=True,text=True,timeout=timeout,cwd=cwd or ROOT)
 require(r.returncode==0,f'{cmd}\n{r.stdout[-3500:]}\n{r.stderr[-6000:]}')
 return r.stdout+r.stderr
def check(name,fn,kind='STATIC'):
 start=time.monotonic()
 try:
  detail=fn();REPORT.append(dict(name=name,kind=kind,status='PASS',seconds=round(time.monotonic()-start,3),detail=detail))
 except Exception as e:REPORT.append(dict(name=name,kind=kind,status='FAIL',seconds=round(time.monotonic()-start,3),detail=str(e)));print('FAIL',name,str(e)[:1000])
def structured():
 counts={}
 for suf in ('.json','.xml','.toml'):
  n=0
  for p in ROOT.rglob('*'+suf):
   if 'audit' in p.relative_to(ROOT).parts:continue
   if suf=='.json':json.loads(p.read_text())
   elif suf=='.xml':ET.fromstring(p.read_text())
   else:tomllib.loads(p.read_text())
   n+=1
  counts[suf]=n
 return counts
def single_canonical():
 for i in range(21):require(len(list(ROOT.glob(f'{i:02d}_*.md')))==1,f'chapter {i:02d} duplicates or missing')
 require(not (ROOT/'agent_integration').exists(),'second protocol root')
 require(not (ROOT/'schema/agent_control.sql').exists(),'old DDL live')
 require(len(list((ROOT/'schema').glob('*sql*')))==1,'more than one target DDL')
 s=text('02_逐步手写开发与旧链路退役.md')
 nums=re.findall(r'^## 步骤 (\d+)：',s,re.M)
 require(nums==[str(i) for i in range(19)],'Agent steps shifted/missing/duplicated')
 return '21 chapters, Agent steps 0–18, one reference/DDL'
def schemas():
 for p in (ROOT/'contracts').glob('*.schema.json'):
  d=json.loads(p.read_text());Draft202012Validator.check_schema(d);SCHEMAS[p.name]=Draft202012Validator(d,format_checker=FormatChecker())
 return sorted(SCHEMAS)
def fixtures():
 cases=[]
 for p in (ROOT/'fixtures').glob('*.json'):cases.append((p,'command.schema.json'))
 for p in (ROOT/'fixtures-platform').glob('*.json'):cases.append((p,'platform-operation.schema.json'))
 for n,s in [('submit-task.json','command.schema.json'),('public-submit.json','public-task.schema.json'),('sync-carry.json','sync-request.schema.json'),('sync-discard.json','sync-request.schema.json'),('merge-request.json','merge-request.schema.json'),('bootstrap-manifest.json','manifest.schema.json'),('accepted.json','receipt.schema.json'),('worker-health.json','worker-health.schema.json'),('task-view.json','task-view.schema.json')]:cases.append((ROOT/'examples'/n,s))
 for p,s in cases:
  errors=list(SCHEMAS[s].iter_errors(json.loads(p.read_text())));require(not errors,f'{p.name}: '+str(errors[0]) if errors else '')
 ev=0
 for line in text('examples/events.sse').splitlines():
  if line.startswith('data:'):
   SCHEMAS['event.schema.json'].validate(json.loads(line[5:].strip()));ev+=1
 return {'json_fixtures':len(cases),'sse_events':ev}
def negatives():
 base=js('examples/submit-task.json');bad=[]
 def mutate(name,fn,source=base,schema='command.schema.json'):
  b=copy.deepcopy(source);fn(b);bad.append((name,b,schema))
 mutate('missing_workline',lambda x:x.pop('worklineId'))
 mutate('v1_wire',lambda x:x.update(schemaVersion=1))
 mutate('mode',lambda x:x['payload'].update(mode='QA'))
 mutate('permissions',lambda x:x['payload'].update(allowedTools=['shell']))
 mutate('type_payload_mismatch',lambda x:x.update(type='CANCEL_TASK'))
 mutate('null_submit_task',lambda x:x.update(taskId=None))
 mutate('zero_epoch',lambda x:x.update(runnerEpoch=0))
 mutate('unknown_command',lambda x:x.update(type='SYNC_WORKSPACE'))
 mutate('bad_time',lambda x:x['payload'].update(deadlineAt='next week'))
 mutate('bad_hash',lambda x:x['payload'].update(runtimeConfigDigest='abcd'))
 mutate('preview_task',lambda x:x.update(taskId=base['taskId']),js('fixtures/preview_changes.json'))
 mutate('extra_outer',lambda x:x.update(isAdmin=True))
 mutate('missing_discard_digest',lambda x:x.pop('expectedTreeDigest',None),js('examples/sync-discard.json'),'sync-request.schema.json')
 mutate('bad_policy',lambda x:x.update(changePolicy='FORCE'),js('examples/sync-carry.json'),'sync-request.schema.json')
 mutate('public_hidden_scope',lambda x:x.update(runnerEpoch=3),js('examples/public-submit.json'),'public-task.schema.json')
 mutate('public_permissions',lambda x:x.update(allowedTools=['shell']),js('examples/public-submit.json'),'public-task.schema.json')
 mutate('bad_merge_strategy',lambda x:x.update(strategy='AUTO'),js('examples/merge-request.json'),'merge-request.schema.json')
 mutate('unknown_workline_status',lambda x:x['payload'].update(worklineStatus='CODING'))
 mutate('missing_workline_status',lambda x:x['payload'].pop('worklineStatus'))
 mutate('old_receipt_shape',lambda x:x.update(accepted=True),js('examples/accepted.json'),'receipt.schema.json')
 mutate('missing_op_line',lambda x:x.pop('worklineId'),js('fixtures-platform/report_progress.json'),'platform-operation.schema.json')
 mutate('progress_has_state',lambda x:x.update(purpose='PROGRESS'),js('examples/bootstrap-manifest.json'),'manifest.schema.json')
 for name,b,s in bad:require(list(SCHEMAS[s].iter_errors(b)),f'invalid fixture accepted: {name}')
 return {'rejected_cases':len(bad)}
def record_fields(code,name):
 m=re.search(r'\brecord\s+'+re.escape(name)+r'\s*\((.*?)\)\s*(?:implements[^\{]*)?\{',code,re.S)
 require(m is not None,'record not found '+name)
 # commas in generic types are not record component delimiters
 chunks=re.split(r',(?![^<>]*>)',m[1]);return [re.search(r'(\w+)\s*$',x).group(1) for x in chunks]
def parity():
 rp=ROOT/'reference/agent-protocol/src/main/java/com/gitnova/agent/protocol'
 mapping={'command.schema.json':('command/AgentCommand.java','AgentCommand'), 'event.schema.json':('event/AgentEvent.java','AgentEvent'), 'platform-operation.schema.json':('platform/PlatformOperation.java','Request'),'manifest.schema.json':('snapshot/ExportManifest.java','ExportManifest'),'receipt.schema.json':('command/CommandReceipt.java','CommandReceipt'),'worker-health.schema.json':('query/WorkerHealth.java','WorkerHealth'),'task-view.schema.json':('query/TaskView.java','TaskView'),'platform-result.schema.json':('platform/PlatformOperation.java','Result')}
 for sch,(f,n) in mapping.items():
  fields=record_fields((rp/f).read_text(),n);expected=set(SCHEMAS[sch].schema['properties']);require(set(fields)==expected,f'{n}: {set(fields)^expected}')
 cmd=(rp/'command/AgentCommand.java').read_text(); bytype={'INITIALIZE':'Initialize','SUBMIT_TASK':'Submit','STEER_TASK':'Steer','CANCEL_TASK':'Cancel','PREVIEW_CHANGES':'Preview','CHECKPOINT_SESSION':'Checkpoint','ACK_CHECKPOINT':'CheckpointAck','STOP_WORKER':'StopWorker'}
 for rule in SCHEMAS['command.schema.json'].schema['allOf']:
  t=rule['if']['properties']['type']['const']; fields=record_fields(cmd,bytype[t]);schemafields=rule['then']['properties']['payload']['properties'];require(set(fields)==set(schemafields),f'{t} payload drift')
 evcode=(rp/'event/EventType.java').read_text();body=evcode[evcode.index('{')+1:evcode.rfind('}')];enames=re.findall(r'\b[A-Z][A-Z_]+\b',body);require(set(enames)==set(SCHEMAS['event.schema.json'].schema['properties']['type']['enum']),'EventType drift')
 ccode=(rp/'command/CommandType.java').read_text();enames=re.findall(r'\b[A-Z][A-Z_]+\b',ccode[ccode.index('{')+1:ccode.rfind('}')]);require(set(enames)==set(bytype),'CommandType drift')
 return '8 outer/result records, 8 payload records, 2 enums'
def scopedrefs():
 required={'WorkerHealth.java','TaskView.java','CommandReceipt.java','ControlTypes.java'}
 files=[p for p in (ROOT/'reference').rglob('*.java') if p.name in required]
 require(len(files)==len(required),'missing scope reference')
 for p in files:require('worklineId' in p.read_text(),p.name+' missing workline')
 require('GITNOVA_WORKLINE_ID' in text('deploy/worker-entrypoint.sh'),'entrypoint scope missing')
 require('.env("GITNOVA_WORKLINE_ID", worklineId)' in text('06_OpenSandbox安装接线与联调.md'),'deployment scope missing')
 return len(files)
def core_message_boundary():
 core='reference/agent-core/src/main/java/com/gitnova/agent/core/'
 require(not (ROOT/core/'engine/TaskInput.java').exists(),'Core must not require platform TaskInput')
 session=text(core+'engine/SessionRuntime.java');control=text(core+'engine/ExecutionControl.java')
 model=text(core+'model/ModelTypes.java');engine=text(core+'engine/AgentEngine.java')
 history=text(core+'history/SessionLog.java');context=text(core+'context/ContextTypes.java')
 tool=text(core+'tool/ToolTypes.java')
 require(record_fields(session,'SessionRuntime')==['sessionId','workRoot','stateRoot','history','results'],'cloud binding leaked into local resources')
 require(record_fields(control,'Steer')==['inputId','message','acceptedEventId'] and 'ModelTypes.Message message' in control,'Steer must carry a user Message without platform command fields')
 require(record_fields(model,'Message')==['role','text','toolCalls','toolCallId','reasoningContent'],'shared Message shape drift')
 require(record_fields(history,'Entry')==['eventId','position','executionId','type','occurredAt','payload'],'Core history must not carry platform task fields')
 require(record_fields(history,'Position')==['streamId','sequence'],'local history position drift')
 require(record_fields(context,'Group')==['first','last','executionId','messages'],'Core groups must use local execution identity')
 require(record_fields(tool,'Context')==['executionId','toolCallId','session','control'],'platform identity leaked into tool context')
 require('append(' not in history.split('interface Writer')[0],'history must be read-only')
 require('String executionId();' in history and 'Entry append(' in history and 'void close()' in history,'bound writer contract missing')
 require('run(ModelTypes.Message input' in engine and 'run(String userText' in engine and 'SessionLog.Writer events' in engine,'message/text entry missing')
 require('new ModelTypes.Message("user", userText, List.of(), null, null)' in engine,'text entry must preserve original user text')
 require(not (ROOT/core/'platform/PlatformCapabilities.java').exists(),'platform port still in Core')
 require((ROOT/'reference/agent-worker/src/main/java/com/gitnova/agent/worker/platform/PlatformCapabilities.java').exists(),'Worker platform adapter missing')
 require('<artifactId>agent-protocol</artifactId>' not in text('deploy/agent-core.pom.xml'),'Core template still depends on protocol')
 require('<artifactId>agent-protocol</artifactId>' in text('deploy/agent-worker.pom.xml'),'Worker needs explicit protocol dependency')
 guide=text('05_接口与数据库契约.md')
 for term in ['不创建TaskInput','Writer','本地origin为空','初始用户消息只写一次','STEER_APPLIED','RUNTIME_CONTEXT']:
  require(term in guide,'message mapping/ownership explanation missing: '+term)
 plan=js('contracts/file-plan.json');byname={x['name']:x for x in plan}
 require('TaskInput' not in byname,'retired TaskInput remains in file plan')
 for name in ['PlatformCapabilities','ReportProgressTool','CreatePullRequestTool']:
  require('/agent-worker/' in byname[name]['path'],'platform implementation still assigned to Core: '+name)
 require('可选' in byname['LocalReadTool']['logic'],'read tool must not block shell-first path')
 return 'Reference field shapes and handbook mapping aligned; no production Engine execution claimed'
def tables():
 s=text('schema/target.sql.reference');names=re.findall(r'^CREATE TABLE\s+(\w+)',s,re.I|re.M);require(len(names)==len(set(names)),'duplicate CREATE')
 data=js('contracts/schema-columns.json');require(set(names)|{'agent_session'}==set(data),'table map difference')
 require(not re.search(r'\bDROP\s+TABLE\b',s,re.I),'destructive SQL')
 # Column parsing does not validate MySQL execution semantics, but catches schema/map drift.
 def split_columns(body):
  out=[];start=0;depth=0;quote=None;escape=False
  for i,ch in enumerate(body):
   if quote:
    if escape:escape=False
    elif ch=='\\':escape=True
    elif ch==quote:quote=None
   elif ch in "'\"`":quote=ch
   elif ch=='(':depth+=1
   elif ch==')':depth-=1
   elif ch==',' and depth==0:out.append(body[start:i].strip());start=i+1
  out.append(body[start:].strip());return out
 for m in re.finditer(r'^CREATE TABLE\s+(\w+)\s*\((.*?)\)\s*ENGINE=',s,re.S|re.M):
  actual={}
  for line in split_columns(m[2]):
   c=re.match(r'(\w+)\s+((?:BIGINT|VARCHAR|CHAR|TINYINT|INT|JSON|LONGTEXT|TEXT|BOOLEAN|DATETIME)(?:\([^)]*\))?(?:\s+UNSIGNED)?)(.*)$',line,re.S|re.I)
   if c:actual[c[1]]=c[2].upper()
  expected={x['name']:x['sqlType'] for x in data[m[1]]['columns']}
  require(actual==expected,'SQL vs column map drift '+m[1]+': '+str(set(actual)^set(expected)))

 sess=re.search(r'ALTER TABLE agent_session\s+(.*?);',s,re.S)[1]
 for bad in ['ADD COLUMN base_commit','ADD COLUMN agent_branch_name','ADD COLUMN last_published_head']:require(bad not in sess,'Session old line field '+bad)
 require("workflow_state IN ('OPEN','CLOSED')" in s,'Session state conflict')
 require('CREATE TABLE domain_event_failure' in s,'consumer failure table absent')
 return {'new_tables':len(names),'existing_session_increment':True,'real_mysql_executed':False}
def rowmapping():
 data=js('contracts/schema-columns.json');n=0
 for table,x in data.items():
  parts=x['javaRow'].split('.');outer='.'.join(parts[:-1]);rc=parts[-1]
  path=ROOT/'reference/server-control/src/main/java'/Path(outer.replace('.','/')+'.java')
  code=path.read_text();block=re.search(r'class '+rc+r'\s*\{(.*?)\n \}',code,re.S);require(block is not None,'row missing '+x['javaRow'])
  fields=set(re.findall(r'public\s+\w+\s+(\w+);',block[1]));cols={c['name'].split('_')[0]+''.join(t.title() for t in c['name'].split('_')[1:]) for c in x['columns']}
  require(fields==cols,f'{table} row differs {fields^cols}');n+=len(fields)
 return {'tables':len(data),'field_mappings':n}
def fk_scope():
 s=text('schema/target.sql.reference')
 for t in ['agent_sandbox_binding','agent_control_task','agent_control_attempt','agent_control_command','agent_event_archive','agent_snapshot_archive','agent_platform_operation','agent_publication']:
  m=re.search('CREATE TABLE '+t+r'\s*\((.*?)\) ENGINE',s,re.S);require(m and re.search(r'workline_id\s+CHAR\(36\).*?NOT NULL',m[1]),t+' scope absent')
 require('UNIQUE KEY uk_binding_scope(session_id,workline_id,runner_epoch)' in s,'no full binding scope unique')
 require('FOREIGN KEY(session_id,runner_epoch)' not in s,'old binding FK permits another line')
 return 'scope columns + composite binding/task constraints (static only)'
def filesmap():
 data=js('contracts/file-plan.json');paths=[x['path'] for x in data];require(len(paths)==len(set(paths)),'duplicate planned paths')
 for x in data:require((ROOT/x['document']).exists(),'map doc missing '+x['document'])
 for p in (ROOT/'reference').rglob('*.java'):
  rel=p.relative_to(ROOT/'reference').as_posix();mod,rest=rel.split('/',1)
  production=('agent-runtime/'+mod+'/'+rest) if mod.startswith('agent-') else rest
  require(production in paths,'reference not mapped '+production)
 require(not any('AgentPullRequestCoordinator' in x for x in paths),'obsolete merge checkpoint coordinator')
 return len(paths)
def acceptance():
 a=js('contracts/acceptance.json')['cases'];require(len(a)==len({x['id'] for x in a}),'duplicate acceptance IDs')

 for x in a:
  require(x['status'] in ['NOT_RUN','PASS','FAIL','BLOCKED'],'invalid acceptance state')
  if x['status']=='PASS':require(isinstance(x['evidence'],dict) and all(x['evidence'].get(k) for k in ['commit','command','artifact']),'PASS without execution evidence '+x['id'])
 doc=text('03_验收矩阵与资料依据.md')
 for x in a:require('| '+x['id']+' |' in doc,'matrix missing '+x['id'])
 return {'product_cases':len(a),'product_pass_count':sum(x['status']=='PASS' for x in a),'note':'script never marks a case PASS'}
def budgets():
 t=js('contracts/timeline.json');s=[sum(x['hours'][i] for x in t['stages']) for i in range(2)];require(s==t['forecast'],'time sum wrong')
 require(t['budget']==[42,60],'changed user budget');require(t['all_product_scope_preserved'],'scope cut')
 return {'budget':t['budget'],'planning_estimate':s,'not_a_promise':True}
def java_compile():
 require(shutil.which('javac') and shutil.which('java'),'JDK required')
 out=TMP/'classes';out.mkdir()
 protocol=list((ROOT/'reference/agent-protocol').rglob('*.java'));core=list((ROOT/'reference/agent-core').rglob('*.java'));worker=list((ROOT/'reference/agent-worker').rglob('*.java'));server=list((ROOT/'reference/server-control').rglob('*.java'))
 # Empty output directory: Core must compile before protocol/Worker exist.
 run(['javac','--release','21','-encoding','UTF-8','-cp',out,'-d',out,*core])
 run(['javac','--release','17','-encoding','UTF-8','-d',out,*protocol])
 run(['javac','--release','21','-encoding','UTF-8','-cp',out,'-d',out,*worker])
 run(['javac','--release','17','-encoding','UTF-8','-cp',out,'-d',out,*server])
 return {'protocol':len(protocol),'core':len(core),'worker_ports':len(worker),'server_types':len(server),'total':len(protocol+core+worker+server),'core_compiled_without_protocol':True}
def javasc():
 out=TMP/'classes';run(['javac','--release','17','-cp',out,'-d',out,ROOT/'probes/ScopeProbe.java']);return run(['java','-cp',out,'ScopeProbe'])
def runtimeconfig():
 # Actual reference config boundary behavior, not just a schema-only claim.
 code='''import java.util.*; import com.gitnova.agent.core.config.RuntimeConfig;
public class ConfigProbe { static RuntimeConfig make(List<String>x){return new RuntimeConfig("m",1,1,100,1000,100,.8,.9,.6,1,100,10,1024,"{}","{}",x);}
public static void main(String[]a){var c=make(List.of("shell","read_file")); if(!c.allowedTools().equals(List.of("read_file","shell")))throw new AssertionError();
if(!make(List.of()).allowedTools().isEmpty())throw new AssertionError();
for(var x:List.of(List.of("shell","shell"),List.of("sudo_anything"))){boolean fail=false;try{make(x);}catch(IllegalArgumentException e){fail=true;}if(!fail)throw new AssertionError();}
System.out.println("ConfigProbe PASS: immutable sorted tools/empty/duplicate/unknown");}}'''
 p=TMP/'ConfigProbe.java';p.write_text(code);out=TMP/'classes';run(['javac','--release','21','-cp',out,'-d',out,p]);return run(['java','-cp',out,'ConfigProbe'])
def specmodels():return run([sys.executable,'-m','unittest','discover','-s',ROOT/'tests','-p','test_spec_model.py','-v'])
def transport():
 out=TMP/'transport';out.mkdir();run(['javac','--release','17','-d',out,ROOT/'probes/TransportProbe.java']);return run(['java','-cp',out,'TransportProbe'],timeout=20)
def processes():return run([sys.executable,ROOT/'scripts/test_process_launcher.py'],timeout=35)
def import_probe():
 repo=TMP/'fake-repo';repo.mkdir();(repo/'.git').mkdir();(repo/'src').mkdir();(repo/'src/KEEP.java').write_text('UNCHANGED')
 old=repo/'docs/harness-refactor';old.mkdir(parents=True);(old/'OLD.md').write_text('old docs')
 backup=TMP/'external-backup'
 cli=[sys.executable,ROOT/'scripts/import_docs.py',repo,'--backup-dir',backup]
 dry=json.loads(run(cli));require(dry['mode']=='DRY_RUN' and (old/'OLD.md').exists() and not backup.exists(),'dryrun mutated')
 result=json.loads(run(cli+['--apply']));require(result['result']=='IMPORTED','import missing')
 require((backup/'OLD.md').read_text()=='old docs','backup invalid');require((repo/'src/KEEP.java').read_text()=='UNCHANGED','source mutated');require((old/'00_从这里开始.md').exists() and not (old/'OLD.md').exists(),'canonical replacement failed')
 require(not list((repo/'docs').glob('.harness-docs-*')),'temporary stages left')
 return 'dry-run, external backup, replacement, source unchanged'
def structure_links():
 errors=[];links=0
 for p in ROOT.rglob('*.md'):
  if 'audit' in p.relative_to(ROOT).parts:continue
  s=p.read_text();fences=re.findall(r'^\s*```',s,re.M);require(len(fences)%2==0,'unpaired fence '+str(p))
  for target in re.findall(r'\]\(([^)]+)\)',s):
   if target.startswith(('http:','https:','mailto:','#')):continue
   target=unquote(target.split('#')[0].split(' ')[0]);dest=(p.parent/target).resolve();links+=1
   if not dest.exists():errors.append(str(p.relative_to(ROOT))+': '+target)
 require(not errors,'broken links: '+'; '.join(errors[:60]));return {'checked_local_links':links}
def deps():
 names=[]
 for p in (ROOT/'reference').rglob('*.java'):
  c=p.read_text();pack=re.search(r'package\s+([\w.]+);',c).group(1);name=pack+'.'+p.stem;require(name not in names,'duplicate FQCN');names.append(name)
  if '/agent-core/' in str(p):
   require(not re.search(r'^import\s+(org\.springframework|org\.mybatis|com\.alibaba\.opensandbox|com\.gitnova\.(entity|mapper|agent\.(protocol|worker)))',c,re.M),'core infra/platform dependency '+p.name)
 return {'fqcn_count':len(names),'check_scope':'reference imports only, not user source architecture'}
def readability():
 s=text('09_文档修正与开发导航.md')
 for i in range(19):require(f'步骤{i}：' in s,'missing per-step guide'+str(i))
 for n in ['12','13','14','15']:
  p=next(ROOT.glob(n+'_*.md'));c=p.read_text();require('测试' in c and ('检索' in c or '查' in c) and ('输入' in c or '请求' in c),'missing learning prompts '+p.name)
 require('本次未进行低推理模型盲测' in text('20_从需求到第一项测试.md'),'readability overclaim boundary absent')
 return '19 step guides + 4 detailed business chapters (presence checks; no reader blind test)'
def protocoljson_contract():
    guide_path = 'reference/agent-protocol/src/main/java/com/gitnova/agent/protocol/json/ProtocolJson.md'
    guide = text(guide_path)
    steps = text('02_逐步手写开发与旧链路退役.md')
    step_one = steps.split('## 步骤 1：', 1)[1].split('## 步骤 2：', 1)[0]
    cards = re.findall(r'^#### (1\.\d+) ([^：\n]+)', step_one, re.M)
    expected_cards = [('1.1', 'CommandType'), ('1.2', 'AgentCommand'), ('1.3', 'ProtocolJson'),
                      ('1.4', '先完成Submit/Cancel往返'), ('1.5', 'CommandReceipt'),
                      ('1.6', 'EventType'), ('1.7', 'AgentEvent')]
    require(cards == expected_cards, 'Step 1 cards reordered or missing: ' + str(cards))
    plan = js('contracts/file-plan.json')
    expected_types = ['CommandType', 'AgentCommand', 'ProtocolJson', 'CommandReceipt', 'EventType', 'AgentEvent']
    require([x['name'] for x in plan if x['step'] == 1] == expected_types, 'file-plan Step 1 order drift')
    entry = next(x for x in plan if x['name'] == 'ProtocolJson')
    require(entry.get('reference') == guide_path, 'ProtocolJson detailed reference missing from file-plan')
    for path in ['02_逐步手写开发与旧链路退役.md', '04_源码迁移与包文件地图.md',
                 '05_接口与数据库契约.md', '09_文档修正与开发导航.md']:
        require(guide_path in text(path), path + ' missing ProtocolJson guide link')
    require(entry['logic'] in text('04_源码迁移与包文件地图.md'), 'ProtocolJson map/plan logic differs')
    require(guide.startswith('# ProtocolJson：步骤 1.3 '), 'ProtocolJson guide restored obsolete step')
    for signature in ['ObjectMapper mapper();', 'AgentCommand readCommand(byte[] body) throws IOException;',
                      'byte[] canonicalBytes(Object value) throws IOException;', 'String sha256(Object value) throws IOException;']:
        require(signature in guide and signature in steps, 'ProtocolJson signature mismatch: ' + signature)
        require(signature.rstrip(';') in entry['signature'], 'file-plan signature mismatch: ' + signature)
    for term in ['CanonicalJsonCodec.java', 'FAIL_ON_UNKNOWN_PROPERTIES', 'FAIL_ON_TRAILING_TOKENS',
                 'STRICT_DUPLICATE_DETECTION', 'UTF-8无符号字节', 'IllegalArgumentException',
                 'canonicalNestedOrder', 'unicodeKeyOrder', 'arrayOrderMatters', 'originalMessagePreserved',
                 'submitRoundTrip', 'cancelRoundTrip', 'typeMismatch', 'duplicateAndTrailing',
                 'unknownField', 'missingOrNull', 'scalarTypeAndRange', 'noDefaultTyping',
                 'parserLimits', 'hashIsStable', 'stringIsNotDocument', 'mapperIsolation', 'encodingFailure']:
        require(term in guide, 'ProtocolJson explanation or test case missing: ' + term)
    payload_rows = dict(re.findall(r'^\| ([A-Z_]+) \| AgentCommand\.\w+ \| ([^|]+) \|$', guide, re.M))
    schema = js('contracts/command.schema.json')
    require(set(payload_rows) == set(schema['properties']['type']['enum']), 'Payload table missing/extra command type')
    for branch in schema['allOf']:
        name = branch['if']['properties']['type']['const']
        required_keys = branch['then']['properties']['payload']['required']
        require(set(payload_rows[name].strip().split('、')) == set(required_keys), 'Payload keys differ: ' + name)
    return 'Step 1 cards/file-plan ordered; signatures/8 payload rows aligned; guide/test cases present, not Java execution'
def legacy_rules():
 bad=['拒绝新Coding Task，提示从目标最新HEAD新建','W≠S明确拒绝，不隐式提交或加载覆盖','AgentPullRequestCoordinator','EngineOutcome→Sealer→固定ZIP→ArchiveService→STORED','同Session先锁后分配，页面不漏迟提交事件X']
 paths=list(ROOT.glob('*.md'))+[ROOT/'contracts/component-methods.md',ROOT/'contracts/mapper-contracts.md',ROOT/'contracts/file-plan.json']
 for p in paths:
  for b in bad:require(b not in p.read_text(),f'active obsolete rule {p.name}: {b}')
 require('merge不取W/RECOVERY' in text('05_接口与数据库契约.md'),'no merge contract')
 return 'explicit superseded rules absent; semantic review still needed beyond keyword scan'
def corpus():
 inputs=js('audit/input-manifest.json');return {'input_manifest_records':len(inputs) if isinstance(inputs,list) else len(inputs.get('files',inputs)), 'note':'hash provenance recorded; report does not re-fetch external repos'}
def tools_available():return {x:shutil.which(x) for x in ['java','javac','mvn','docker','mysql']}
def main():
 global TMP
 with tempfile.TemporaryDirectory(prefix='gitnova-doc-audit-') as tmp:
  TMP=Path(tmp)
  for n,f,k in [('结构文件解析',structured,'STATIC'),('唯一手册/原施工顺序',single_canonical,'STATIC'),('JSON Schema合法性',schemas,'STATIC'),('样例与SSE正例',fixtures,'CONTRACT'),('协议负例拒绝',negatives,'CONTRACT'),('Java/Schema字段枚举一致',parity,'STATIC'),('全链作用域参考/部署',scopedrefs,'STATIC'),('唯一SQL目标检查',tables,'STATIC'),('SQL/Java行映射',rowmapping,'STATIC'),('数据库scope约束检查',fk_scope,'STATIC'),('唯一文件地图/参考覆盖',filesmap,'STATIC'),('验收来源与未执行标记',acceptance,'STATIC'),('工时预算与估计加总',budgets,'STATIC'),('JDK17/21参考编译',java_compile,'REFERENCE'),('作用域参考运行',javasc,'REFERENCE'),('运行配置参考运行',runtimeconfig,'REFERENCE'),('有限规格模型实验',specmodels,'SPEC_MODEL'),('本机HTTP/SSE探针',transport,'PROBE'),('Linux启动器探针',processes,'PROBE'),('安全文档导入演练',import_probe,'PROBE'),('Markdown链接/代码围栏',structure_links,'STATIC'),('参考依赖与FQCN',deps,'STATIC'),('施工提示存在性',readability,'READABILITY_STATIC'),('废弃规则扫描',legacy_rules,'STATIC'),('输入来源记录',corpus,'STATIC'),('实机工具可用性记录',tools_available,'ENVIRONMENT')]:
   check(n,f,k)
  check('ProtocolJson方法说明与施工顺序',protocoljson_contract,'READABILITY_STATIC')
  check('Core输入消息边界',core_message_boundary,'STATIC')
  result={'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'productTestsExecuted':False,'groups':REPORT,'passed':sum(x['status']=='PASS' for x in REPORT),'failed':sum(x['status']=='FAIL' for x in REPORT),'warnings':['No MySQL/RabbitMQ/OpenSandbox/user-source/real-model integration executed','Specification model != production implementation','No low-effort-model or independent-reader blind test']}
  (ROOT/'audit/check-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
  print(json.dumps({'passed':result['passed'],'failed':result['failed']},ensure_ascii=False))
  sys.exit(1 if result['failed'] else 0)
if __name__=='__main__':main()
