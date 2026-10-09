"""Executable SPECIFICATION model. This is NOT GitNova production code or an integration test."""
from dataclasses import dataclass,field
import copy,itertools,unittest
@dataclass
class Spec:
    q:str='Q'; line:str='L1'; epoch:int=1; allocated:int=1; workflow:str='OPEN'
    line_status:str='ACTIVE'; baseline:dict=field(default_factory=lambda:{'a':'v1'})
    tree:dict=field(default_factory=lambda:{'a':'v1','draft':'keep'})
    active_task:object=None; pending_op:bool=False; sync:object=None
    prepared:object=None; activated_syncs:dict=field(default_factory=dict)
    switch_events:set=field(default_factory=set)
    def merge(self):
        if self.active_task or self.pending_op or self.sync:raise ValueError('BUSY')
        self.line_status='MERGED' # does NOT mutate q, tree, baseline, binding
    def publish(self,line,epoch):
        if (line,epoch)!=(self.line,self.epoch):raise ValueError('SCOPE')
        if self.line_status!='ACTIVE' or self.sync:raise ValueError('NOT_PUBLISHABLE')
        return 'PUBLISHED'
    def begin(self,syncid,H,policy,confirmed=None):
        if self.active_task or self.pending_op or self.sync:raise ValueError('BUSY')
        if policy=='DISCARD_CHANGES' and confirmed!=self.tree:raise ValueError('STALE_CONFIRMATION')
        self.allocated+=1;self.sync=(syncid,copy.deepcopy(H),policy,self.allocated)
    def prepare(self):
        sid,H,policy,e=self.sync
        old=copy.deepcopy(self.tree)
        if policy=='REQUIRE_CLEAN':
            if old!=self.baseline:raise ValueError('DIRTY')
            candidate=copy.deepcopy(H)
        elif policy=='DISCARD_CHANGES':candidate=copy.deepcopy(H)
        else:candidate=merge_files(self.baseline,H,old)
        self.prepared=(sid,'L2',e,H,candidate)
    def activate(self):
        sid,l,e,H,W=self.prepared
        if not self.sync or self.sync[0]!=sid:raise ValueError('GATE')
        self.line=l;self.epoch=e;self.baseline=H;self.tree=W;self.line_status='ACTIVE'
        self.activated_syncs[sid]=(l,e);self.sync=None
    def first_submit(self):
        if self.sync:raise ValueError('SYNC')
        self.active_task='task';self.switch_events.update(self.activated_syncs)
    def finish(self,line,epoch,task):
        if (line,epoch,task)==(self.line,self.epoch,self.active_task):self.active_task=None
ABSENT=object()
def merge_files(B,H,W):
    out={}
    for p in B.keys()|H.keys()|W.keys():
        b=B.get(p,ABSENT);h=H.get(p,ABSENT);w=W.get(p,ABSENT)
        if h==w:v=h
        elif h==b:v=w
        elif w==b:v=h
        else:raise ValueError('CONFLICT')
        if v is not ABSENT:out[p]=v
    return out
class ModelTests(unittest.TestCase):
    def test_merge_dirty_keeps_session_tree(self):
        s=Spec();old=copy.deepcopy(s.tree);s.merge()
        self.assertEqual((s.q,s.workflow,s.tree),('Q','OPEN',old))
        s.first_submit();self.assertEqual(s.active_task,'task')
        with self.assertRaises(ValueError):s.publish('L1',1)
    def test_merge_gate_interleavings(self):
        for flag in ['active_task','pending_op','sync']:
            s=Spec();setattr(s,flag,True)
            with self.assertRaises(ValueError):s.merge()
    def test_clean_rejects_no_replacement(self):
        s=Spec();old=copy.deepcopy(s.tree);s.begin('Y',{'a':'v2'},'REQUIRE_CLEAN')
        with self.assertRaises(ValueError):s.prepare()
        self.assertEqual((s.line,s.tree),('L1',old))
    def test_carry_baseline_and_new_draft(self):
        s=Spec();s.begin('Y',{'a':'v2'},'CARRY_CHANGES');s.prepare()
        self.assertEqual(s.line,'L1');self.assertEqual(s.switch_events,set())
        s.activate();self.assertEqual(s.baseline,{'a':'v2'});self.assertEqual(s.tree,{'a':'v2','draft':'keep'})
        self.assertEqual(s.switch_events,set());s.first_submit();s.first_submit();self.assertEqual(s.switch_events,{'Y'})
    def test_carry_conflict_preserves(self):
        s=Spec(tree={'a':'mydraft'});old=copy.deepcopy(s.tree);s.begin('Y',{'a':'remote'},'CARRY_CHANGES')
        with self.assertRaises(ValueError):s.prepare()
        self.assertEqual(s.tree,old)
    def test_discard_requires_specific_old_tree(self):
        s=Spec()
        with self.assertRaises(ValueError):s.begin('Y',{'a':'v2'},'DISCARD_CHANGES',{'a':'v1'})
        s.begin('Y',{'a':'v2'},'DISCARD_CHANGES',copy.deepcopy(s.tree));s.prepare();s.activate();self.assertEqual(s.tree,{'a':'v2'})
    def test_old_epoch_cannot_change_new(self):
        s=Spec();s.begin('Y',{'a':'v2'},'CARRY_CHANGES');s.prepare();s.activate();s.first_submit()
        s.finish('L1',1,'task');self.assertEqual(s.active_task,'task')
        with self.assertRaises(ValueError):s.publish('L1',1)
    def test_failed_epoch_not_reused(self):
        s=Spec();s.begin('Y',{'a':'v2'},'CARRY_CHANGES');a=s.allocated;s.sync=None
        s.begin('Z',{'a':'v3'},'CARRY_CHANGES');self.assertGreater(s.allocated,a)
    def test_file_merge_truth_table(self):
        for b,h,w in itertools.product([None,'x','y'],repeat=3):
            d=lambda v: {} if v is None else {'a':v}
            if h==w or h==b or w==b:merge_files(d(b),d(h),d(w))
            else:
                with self.assertRaises(ValueError):merge_files(d(b),d(h),d(w))
    def test_receipt_projection_rollback_model(self):
        db={'receipts':set(),'notifications':set()}
        tx=copy.deepcopy(db);tx['receipts'].add('E') # simulate failure before notification, discard tx
        self.assertNotIn('E',db['receipts'])
        tx=copy.deepcopy(db);tx['receipts'].add('E');tx['notifications'].add('E');db=tx
        for _ in range(3):
            if 'E' not in db['receipts']:db['notifications'].add('duplicate')
        self.assertEqual(db['notifications'],{'E'})
    def test_old_read_cas(self):
        row={'read':False,'version':3}
        def update(desired,version):
            if row['read']==desired:return
            if row['version']!=version:raise ValueError('STALE')
            row['read']=desired;row['version']+=1
        update(True,3);update(False,4)
        with self.assertRaises(ValueError):update(True,3)
    def test_mutation_retry_does_not_reclose(self):
        state='OPEN';receipts={}
        def apply(key,target):
            nonlocal state
            if key in receipts:return receipts[key]
            state=target;receipts[key]=target;return target
        apply('close1','CLOSED');apply('reopen1','OPEN');apply('close1','CLOSED');self.assertEqual(state,'OPEN')
if __name__=='__main__':unittest.main(verbosity=2)
