#!/usr/bin/env python3
"""Import this handbook into an existing repository docs only; dry-run by default."""
import argparse,hashlib,json,shutil,tempfile,datetime,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def hashes(root):
    return {p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('repo',type=Path);ap.add_argument('--apply',action='store_true')
    ap.add_argument('--backup-dir',type=Path,help='Must be outside the repository')
    a=ap.parse_args();repo=a.repo.expanduser().resolve()
    if not repo.is_dir() or not (repo/'.git').exists():ap.error('repo must exist and contain .git (directory or worktree file)')
    docs=repo/'docs';target=docs/'harness-refactor'
    if docs.is_symlink() or target.is_symlink():ap.error('docs/target symlinks are not supported')
    if ROOT==target or ROOT.is_relative_to(target) or target.is_relative_to(ROOT):ap.error('source and destination must be independent directories')
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup=(a.backup_dir.expanduser().resolve() if a.backup_dir else repo.parent/(repo.name+'-docs-backup-'+stamp))
    if backup==repo or backup.is_relative_to(repo):ap.error('backup must be outside repository')
    if backup.exists():ap.error('backup path exists; choose another directory')
    report={'mode':'APPLY' if a.apply else 'DRY_RUN','source':str(ROOT),'target':str(target),
            'source_files':len(hashes(ROOT)),'old_docs_files':len(hashes(target)) if target.exists() else 0,
            'backup':str(backup) if target.exists() else None,'touches':['docs/harness-refactor only'],
            'does_not_execute':['git add/commit','source code overwrite','Flyway/SQL','network/dependency installation']}
    if not a.apply:print(json.dumps(report,ensure_ascii=False,indent=2));return
    docs.mkdir(exist_ok=True)
    stage=Path(tempfile.mkdtemp(prefix='.harness-docs-stage-',dir=docs));old=None
    try:
        shutil.copytree(ROOT,stage,dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
        if hashes(ROOT)!=hashes(stage):raise RuntimeError('stage copy hash mismatch')
        if target.exists():
            if not target.is_dir():raise RuntimeError('target exists but is not a directory')
            if any(p.is_symlink() for p in target.rglob('*')):raise RuntimeError('old docs contains symlink; back up and resolve manually')
            old_hash=hashes(target);shutil.copytree(target,backup)
            if hashes(backup)!=old_hash:raise RuntimeError('backup copy hash mismatch')
            old=docs/('.harness-docs-old-'+stage.name.rsplit('-',1)[-1]);target.rename(old)
        try:stage.rename(target)
        except BaseException:
            if old is not None and not target.exists():old.rename(target)
            raise
        if old is not None:shutil.rmtree(old)
        report['result']='IMPORTED';print(json.dumps(report,ensure_ascii=False,indent=2))
    finally:
        if stage.exists():shutil.rmtree(stage)
if __name__=='__main__':main()
