import json
import hashlib
import sqlite3
import threading
import time
import uuid
from pathlib import Path

MODES = {'server', 'assistant', 'collaboration'}
KINDS = {'report', 'csv', 'collaboration', 'harness'}

class Conflict(ValueError):
    pass

class Control:
    """Single-node authority. All admission and result commits share one lock."""
    def __init__(self, root, recover=True):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.root / 'control.sqlite', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS node(id INTEGER PRIMARY KEY, mode TEXT, version INTEGER, epoch INTEGER);
        INSERT OR IGNORE INTO node VALUES(1,'assistant',1,1);
        CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY, title TEXT, kind TEXT, source TEXT,
          input TEXT, status TEXT, epoch INTEGER, attempt TEXT, deadline REAL, created REAL, updated REAL,
          progress INTEGER, stage TEXT, artifact TEXT, error TEXT);
        CREATE TABLE IF NOT EXISTS submissions(key TEXT PRIMARY KEY, digest TEXT, task TEXT);
        CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT, task TEXT, type TEXT, detail TEXT, time REAL);
        ''')
        columns = {row['name'] for row in self.db.execute('PRAGMA table_info(tasks)')}
        if 'executor' not in columns:
            self.db.execute("ALTER TABLE tasks ADD COLUMN executor TEXT NOT NULL DEFAULT 'local'")
        with self.lock, self.db:
            if recover:
                # A crash leaves ambiguous work. Never replay automatically.
                self.db.execute("UPDATE tasks SET status='UNKNOWN', error='进程重启，上一轮执行需人工核对', updated=? WHERE status IN ('RUNNING','STOPPING')", (time.time(),))
                self.db.execute('UPDATE node SET epoch=epoch+1 WHERE id=1')
                self.event(None, 'startup', '本地控制服务启动；历史未完成执行不自动重放')

    def event(self, task, kind, detail):
        self.db.execute('INSERT INTO events(task,type,detail,time) VALUES(?,?,?,?)', (task,kind,detail,time.time()))

    def node(self):
        return dict(self.db.execute('SELECT mode,version,epoch FROM node WHERE id=1').fetchone())

    def mode(self, mode, version):
        if mode not in MODES or type(version) is not int:
            raise ValueError('模式或版本无效')
        with self.lock, self.db:
            n = self.node()
            if n['version'] != version:
                raise Conflict('模式已变化，请刷新后重试')
            if n['mode'] == mode:
                return n
            self.db.execute('UPDATE node SET mode=?,version=version+1,epoch=epoch+1 WHERE id=1', (mode,))
            self.db.execute("UPDATE tasks SET status='STOPPING',stage='模式切换，等待执行退出',updated=? WHERE status='RUNNING'", (time.time(),))
            # No queued server work silently resumes after employee takeover.
            self.db.execute("UPDATE tasks SET status='CANCELLED',stage='模式切换撤销排队任务',updated=? WHERE status='QUEUED'", (time.time(),))
            self.event(None,'mode',f"切换模式：{mode}；撤销旧执行授权")
            return self.node()

    def create(self, title, kind, source, text, key=None, executor='local'):
        if not isinstance(title,str) or not 1 <= len(title.strip()) <= 120:
            raise ValueError('任务标题需为1至120字')
        if kind not in KINDS or source not in {'server','employee'} or not isinstance(text,str) or len(text)>50000:
            raise ValueError('任务类型、来源或输入无效')
        if key is not None and (not isinstance(key,str) or not 1 <= len(key) <= 80):
            raise ValueError('提交标识无效')
        if executor not in {'local','desktop'} or (executor == 'desktop' and kind != 'harness'):
            raise ValueError('执行端无效')
        digest=hashlib.sha256(json.dumps([title.strip(),kind,source,text,executor],ensure_ascii=False).encode()).hexdigest()
        with self.lock, self.db:
            if key is not None:
                prior=self.db.execute('SELECT digest,task FROM submissions WHERE key=?',(key,)).fetchone()
                if prior:
                    if prior['digest']!=digest: raise Conflict('提交标识已绑定其他内容')
                    return prior['task']
            n = self.node()
            if source == 'server' and n['mode'] == 'assistant':
                raise Conflict('员工辅助模式暂停中央接单')
            if source == 'employee' and n['mode'] == 'server':
                raise Conflict('服务端接管模式暂停员工接单')
            if kind == 'collaboration' and n['mode'] != 'collaboration':
                raise Conflict('请先进入协作模式')
            task = uuid.uuid4().hex
            now = time.time()
            self.db.execute('INSERT INTO tasks(id,title,kind,source,input,status,epoch,attempt,deadline,created,updated,progress,stage,artifact,error,executor) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (task,title.strip(),kind,source,text,'QUEUED',n['epoch'],None,None,now,now,0,'等待调度',None,None,executor))
            if key is not None:
                self.db.execute('INSERT INTO submissions VALUES(?,?,?)',(key,digest,task))
            self.event(task,'created',f'{"中央" if source == "server" else "员工"}提交任务：{title.strip()}')
            return task

    def claim(self, executor='local'):
        if executor not in {'local','desktop'}:
            raise ValueError('执行端无效')
        with self.lock, self.db:
            if self.db.execute("SELECT 1 FROM tasks WHERE status IN ('RUNNING','STOPPING')").fetchone():
                return None
            n = self.node()
            row = self.db.execute("SELECT * FROM tasks WHERE status='QUEUED' AND executor=? ORDER BY created LIMIT 1", (executor,)).fetchone()
            if row is None:
                return None
            task = dict(row)
            if task['epoch'] != n['epoch'] or (task['source']=='server' and n['mode']=='assistant'):
                self.db.execute("UPDATE tasks SET status='CANCELLED',stage='授权已失效' WHERE id=?",(task['id'],))
                return None
            attempt = uuid.uuid4().hex
            deadline = time.time()+120
            self.db.execute("UPDATE tasks SET status='RUNNING',attempt=?,deadline=?,stage='开始执行',updated=? WHERE id=?",(attempt,deadline,time.time(),task['id']))
            self.event(task['id'],'claimed','任务已领取，本轮执行授权有效')
            task.update(attempt=attempt,deadline=deadline,status='RUNNING')
            return task

    def valid(self, task):
        row = self.db.execute('SELECT status,attempt,epoch,deadline FROM tasks WHERE id=?',(task['id'],)).fetchone()
        return bool(row and row['status']=='RUNNING' and row['attempt']==task['attempt']
            and row['epoch']==self.node()['epoch'] and row['deadline']>time.time())

    def progress(self, task, percent, stage):
        with self.lock, self.db:
            if not self.valid(task):
                raise Conflict('执行授权已撤销或过期')
            self.db.execute('UPDATE tasks SET progress=?,stage=?,updated=? WHERE id=?',(percent,stage,time.time(),task['id']))
            self.event(task['id'],'stage',stage)

    def finish(self, task, content, suffix):
        if suffix not in {'html','csv','txt'}:
            raise ValueError('不支持的结果格式')
        with self.lock, self.db:
            if not self.valid(task):
                raise Conflict('拒绝旧执行提交结果')
            folder = self.root/'artifacts'
            folder.mkdir(exist_ok=True)
            name = task['id']+'.'+suffix
            target = folder/name
            target.write_text(content,encoding='utf-8')
            self.db.execute("UPDATE tasks SET status='SUCCEEDED',progress=100,stage='结果已交付',artifact=?,updated=? WHERE id=?",(name,time.time(),task['id']))
            self.event(task['id'],'completed','结果已提交并记录审计')

    def stop(self, task_id):
        with self.lock, self.db:
            row=self.db.execute('SELECT status FROM tasks WHERE id=?',(task_id,)).fetchone()
            if row is None:
                raise ValueError('任务不存在')
            if row['status'] not in {'RUNNING','QUEUED'}:
                return
            state='STOPPING' if row['status']=='RUNNING' else 'CANCELLED'
            self.db.execute('UPDATE tasks SET status=?,stage=?,updated=? WHERE id=?',(state,'员工要求停止',time.time(),task_id))
            self.event(task_id,'stop','停止请求；执行退出后释放槽位')

    def failed(self, task, message, uncertain=False):
        with self.lock,self.db:
            row=self.db.execute('SELECT status,attempt FROM tasks WHERE id=?',(task['id'],)).fetchone()
            if not row or row['attempt']!=task['attempt'] or row['status'] not in {'RUNNING','STOPPING'}:
                return
            state='UNKNOWN' if uncertain else ('CANCELLED' if row['status']=='STOPPING' else 'FAILED')
            self.db.execute('UPDATE tasks SET status=?,stage=?,error=?,updated=? WHERE id=?',(state,'执行已退出',message[:500],time.time(),task['id']))
            self.event(task['id'],'exited',{'UNKNOWN':'执行已退出，需人工核对','CANCELLED':'任务已停止，执行槽位已释放','FAILED':'执行失败，未自动重试'}[state])

    def snapshot(self):
        with self.lock:
            tasks=[dict(r) for r in self.db.execute('SELECT * FROM tasks ORDER BY created DESC LIMIT 100')]
            for t in tasks:
                t.pop('input')
            return {'node':self.node(),'tasks':tasks,'events':[dict(r) for r in self.db.execute('SELECT * FROM events ORDER BY seq DESC LIMIT 100')]}
