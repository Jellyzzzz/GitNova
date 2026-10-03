#!/usr/bin/env python3
"""Trusted Linux launcher; not a complete hostile-process isolation boundary."""
import json,os,sys,tempfile
from pathlib import Path
if len(sys.argv)<4 or sys.argv[2]!='--':
    raise SystemExit('usage: process-launch.py <trusted-control-file> -- <program> [args...]')
control=Path(sys.argv[1]); argv=sys.argv[3:]
if control.exists():raise SystemExit('control identity already exists')
control.parent.mkdir(parents=True,exist_ok=True)
os.setsid()
pid=os.getpid(); raw=Path(f'/proc/{pid}/stat').read_text()
# fields after comm's closing parenthesis begin with field 3; starttime is field 22.
starttime=raw[raw.rfind(')')+2:].split()[19]
record={'pid':pid,'pgid':os.getpgrp(),'starttime':starttime,'bootId':Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
fd,tmp=tempfile.mkstemp(prefix='.launch-',dir=control.parent)
try:
    with os.fdopen(fd,'w') as f:json.dump(record,f);f.flush();os.fsync(f.fileno())
    # link publishes only when destination is absent; no silent overwrite.
    os.link(tmp,control);os.unlink(tmp)
    os.execvp(argv[0],argv)
finally:
    if os.path.exists(tmp):os.unlink(tmp)
