#!/usr/bin/env python3
"""Copy reviewed DDL as a NEW Flyway migration; never executes SQL or modifies old files."""
import argparse,re,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('repo',type=Path);p.add_argument('--write',action='store_true');a=p.parse_args()
d=a.repo/'src/main/resources/db/migration'
if not d.is_dir():sys.exit('Migration directory not found: '+str(d))
source=Path(__file__).resolve().parents[1]/'schema/agent_control.sql'
for previous in d.glob('V*__agent_sandbox_control.sql'):
 if previous.read_text(encoding='utf-8') == source.read_text(encoding='utf-8'):
  print('Already installed:',previous);sys.exit(0)
 sys.exit('Existing control migration differs; inspect manually: '+str(previous))
versions=[]
for f in d.glob('V*__*.sql'):
 m=re.match(r'V([0-9]+)__',f.name)
 if not m:sys.exit('Non-integer Flyway version found; inspect manually: '+f.name)
 versions.append(int(m[1]))
target=d/f'V{max(versions,default=0)+1}__agent_sandbox_control.sql'
print('Target:',target);print('Review local schema changes before copying; no DDL is executed.')
if a.write:
 source=Path(__file__).resolve().parents[1]/'schema/agent_control.sql'
 with target.open('x',encoding='utf-8') as out:out.write(source.read_text(encoding='utf-8'))
 print('Created. Existing migrations untouched.')
