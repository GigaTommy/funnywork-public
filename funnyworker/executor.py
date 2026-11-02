"""Trusted, bounded demonstration recipes; no model-generated code execution."""
import csv
import html
import io
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from .control import Conflict

class Executor:
    def __init__(self, control, shutdown, harness_config=None,model_receiver=None):
        self.control,self.shutdown,self.harness_config=control,shutdown,harness_config
        self.model_receiver=model_receiver

    def checkpoint(self, task, percent, text):
        self.control.progress(task,percent,text)
        for _ in range(10):
            if self.shutdown.wait(.04):
                raise Conflict('服务正在停止')
            with self.control.lock:
                if not self.control.valid(task):
                    raise Conflict('执行已停止')

    def execute(self, task):
        try:
            self.checkpoint(task,10,'检查任务授权与输入')
            text=task['input']
            if task['kind']=='harness':
                content=self.harness(task)
                suffix='txt'
            elif task['kind']=='csv':
                rows=list(csv.reader(io.StringIO(text)))
                if not rows or len(rows)>2000 or max(map(len,rows))>50:
                    raise ValueError('CSV需为1至2000行、最多50列')
                self.checkpoint(task,45,'读取表格并核对列结构')
                header=rows[0]
                totals=[]
                for col in range(len(header)):
                    values=[]
                    for r in rows[1:]:
                        try: values.append(float(r[col]))
                        except (ValueError,IndexError): pass
                    if values: totals.append(f'{header[col]}：{len(values)}个数值，总计 {sum(values):g}')
                content=self.document(task,'表格分析',f'数据行数：{len(rows)-1}\n列数：{len(header)}\n'+'\n'.join(totals),rows[:21])
                suffix='html'
            elif task['kind']=='collaboration':
                self.checkpoint(task,30,'资料整理员：提取输入要点')
                lines=[x.strip() for x in text.splitlines() if x.strip()]
                self.checkpoint(task,60,'报告撰写员：整理交付结构')
                self.checkpoint(task,85,'复核员：检查材料完整性')
                content=self.document(task,'协作报告','\n'.join(f'{i+1}. {line}' for i,line in enumerate(lines)) or '未提供材料。',None)
                suffix='html'
            else:
                self.checkpoint(task,50,'根据提供材料生成办公报告')
                content=self.document(task,'办公报告',text or '未提供材料。')
                suffix='html'
            self.checkpoint(task,95,'校验执行授权并准备交付')
            self.control.finish(task,content,suffix)
        except Exception as exc:
            # Harness may have performed profile-owned effects; never auto-retry.
            self.control.failed(task,str(exc),uncertain=task['kind']=='harness')

    def document(self,task,heading,text,rows=None):
        table=''
        if rows:
            table='<table>'+''.join('<tr>'+''.join('<td>'+html.escape(c)+'</td>' for c in row)+'</tr>' for row in rows)+'</table>'
        return '<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>'+html.escape(task['title'])+'</title><style>body{max-width:850px;margin:60px auto;padding:24px;font:16px/1.8 system-ui;color:#203833}pre{white-space:pre-wrap;font:inherit}table{border-collapse:collapse}td{border:1px solid #ddd;padding:8px}small{color:#657870}</style><small>FUNNYWORKER</small><h1>'+html.escape(task['title'])+'</h1><h2>'+heading+'</h2><pre>'+html.escape(text)+'</pre>'+table+'</html>'

    def harness(self,task):
        if not self.harness_config and not self.model_receiver:
            raise ValueError('智能助手暂不可用，请联系管理员')
        folder=self.control.root/'attempts'/task['attempt']
        folder.mkdir(parents=True)
        if self.model_receiver:
            model=self.model_receiver()
            payload={'model_config':model,'profile':'sdk-minimal','provider':'deepseek-official',
                'patches':[str(Path(__file__).with_name('harness-policy.yml'))]}
        else:payload=dict(self.harness_config)
        payload.update(prompt=task['input'],workspace=str(folder),home=str(folder/'home'))
        env={k:v for k,v in os.environ.items() if k in {'PATH','SYSTEMROOT','WINDIR','TEMP','TMP','LANG','HOME'}}
        if os.environ.get('FUNNYWORKER_MODEL_KEY'):
            env['FUNNYWORKER_MODEL_KEY']=os.environ['FUNNYWORKER_MODEL_KEY']
        proc=subprocess.Popen([sys.executable,'-m','funnyworker.harness_runner'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            text=True,env=env,start_new_session=os.name!='nt')
        # communicate drains output while waiting; request payload is sent exactly once.
        initial=json.dumps(payload)
        try:
            while True:
                try:
                    out,err=proc.communicate(initial,timeout=.2)
                    break
                except subprocess.TimeoutExpired:
                    initial=None
                    with self.control.lock:
                        active=self.control.valid(task)
                    if self.shutdown.is_set() or not active:
                        raise Conflict('Harness执行取消或授权过期')
            if proc.returncode:
                raise ValueError('智能助手执行失败，请联系管理员检查连接')
            result=json.loads(out)
            if result.get('finish_reason')!='completed':
                raise ValueError('Harness未正常完成，本轮需核对')
            return str(result['text'])
        finally:
            if os.name!='nt':
                # Reap the whole group, including descendants left after runner exit.
                try: os.killpg(proc.pid,signal.SIGTERM)
                except ProcessLookupError: pass
                time.sleep(.1)
                try: os.killpg(proc.pid,signal.SIGKILL)
                except ProcessLookupError: pass
            elif proc.poll() is None:
                subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'],capture_output=True,timeout=10)
            if proc.poll() is None: proc.kill()
            proc.wait()
