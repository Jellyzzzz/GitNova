#!/usr/bin/env python3
"""Read-only inventory: prints metadata, import dependencies and anchor presence; never copies source text."""
import argparse,csv,hashlib,json,re,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('repo',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
repo=a.repo.resolve();root=Path(__file__).resolve().parents[1]
if not (repo/'src').exists():sys.exit('Expected GitNova repository with src directory')
rows=list(csv.DictReader((root/'contracts/migration-map.csv').open(encoding='utf-8-sig')))
# Column name is explicit in the generated CSV; tolerate only the recorded alternatives.
key=next((k for k in rows[0] if k in ('old_path','旧路径','source_path')),None)
if key is None:sys.exit('Cannot find source path column in migration map')
known={x[key] for x in rows}
actual={str(f.relative_to(repo)):f for f in (repo/'src').rglob('*.java')}
report=[]
for path in sorted(known|{s for s in actual if '/agent/' in s or '/service/session/' in s}):
 f=actual.get(path)
 if f is None:report.append({'path':path,'status':'MISSING_FROM_LOCAL'});continue
 raw=f.read_bytes();text=raw.decode('utf-8',errors='replace')
 report.append({'path':path,'status':'MATCHED_PATH' if path in known else 'UNMAPPED_LOCAL_FILE',
  'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw),
  'imports':re.findall(r'^import\s+(?:static\s+)?([^;]+);',text,re.M),
  'anchors':{x:(x in text) for x in ('handleNaturalStop','ANSWER_DELIVERED','finishTask','appendModelResponse','WorkspaceGateway')}})
try:
 head=subprocess.run(['git','-C',str(repo),'rev-parse','HEAD'],capture_output=True,text=True,check=True).stdout.strip()
 status=subprocess.run(['git','-C',str(repo),'status','--porcelain'],capture_output=True,text=True,check=True).stdout.splitlines()
except (subprocess.CalledProcessError,FileNotFoundError):head='NOT_A_GIT_CHECKOUT';status=[]
result={'repo':str(repo),'head':head,'git_status':status,'files':report,
 'warning':'Path matching is not semantic approval. Review all UNMAPPED/MISSING before deleting. No files modified.'}
data=json.dumps(result,ensure_ascii=False,indent=2)
if a.output:a.output.write_text(data,encoding='utf-8')
else:print(data)
