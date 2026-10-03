#!/usr/bin/env python3
"""Tests the included launcher only, not a completed Java ProcessSupervisor."""
import json,os,signal,subprocess,sys,tempfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
launcher=ROOT/'deploy/process-launch.py'
def wait_record(path,p):
    end=time.monotonic()+5
    while time.monotonic()<end:
        if path.exists():return json.loads(path.read_text())
        if p.poll() is not None:raise AssertionError('launcher exited before identity')
        time.sleep(.02)
    raise AssertionError('identity timeout')
def running(pid):
    f=Path(f'/proc/{pid}/stat')
    if not f.exists():return False
    try:return f.read_text().split(')')[-1].split()[0]!='Z'
    except FileNotFoundError:return False
results=[]
with tempfile.TemporaryDirectory() as td:
    td=Path(td)
    for name,code,expected in [('zero','print("ok")',0),('nonzero','import sys; print("error",file=sys.stderr); sys.exit(3)',3),('dual','import sys; print("x"*100000); print("y"*100000,file=sys.stderr)',0)]:
        control=td/(name+'.json')
        p=subprocess.Popen([sys.executable,str(launcher),str(control),'--',sys.executable,'-c',code],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        out,err=p.communicate(timeout=8)
        assert p.returncode==expected,(name,p.returncode,err)
        rec=json.loads(control.read_text());assert rec['pgid']==rec['pid'] and rec['pid']==p.pid
        if name=='dual':assert len(out)>100000 and len(err)>100000
        results.append(name)
    control=td/'cancel.json'
    p=subprocess.Popen([sys.executable,str(launcher),str(control),'--','/bin/bash','-c','sleep 60 & echo $!; wait'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        rec=wait_record(control,p);assert rec['pgid']>1 and rec['pgid']!=os.getpgrp()
        # This path tests a known ordinary child group; hostile setsid escape is not covered.
        child=int(p.stdout.readline().decode().strip())
        os.killpg(rec['pgid'],signal.SIGTERM)
        p.communicate(timeout=5)
        end=time.monotonic()+3
        while running(child) and time.monotonic()<end:time.sleep(.02)
        assert not running(child),'child survived group cancellation'
        results.append('cancel_regular_child_group')
    finally:
        if p.poll() is None:
            os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
print(json.dumps({'status':'PASS','cases':results,'limit':'launcher only; no Docker/cgroup/hostile escape guarantee'},ensure_ascii=False))
