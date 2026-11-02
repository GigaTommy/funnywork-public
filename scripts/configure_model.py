"""Import a local provider profile without printing or persisting plaintext keys."""
import argparse
import re
import json
import sqlite3
from contextlib import closing
from pathlib import Path
from funnyworker.model_config import ModelService

def main():
    parser=argparse.ArgumentParser()
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--source')
    source.add_argument('--cc-switch',action='store_true')
    parser.add_argument('--data',default='.local/prototype')
    parser.add_argument('--model',help='覆盖来源配置中已失效的模型名称')
    args=parser.parse_args()
    values={}
    if args.cc_switch:
        database=Path.home()/'.cc-switch'/'cc-switch.db'
        with closing(sqlite3.connect(database.as_uri()+'?mode=ro',uri=True)) as connection:
            rows=connection.execute("SELECT settings_config FROM providers WHERE lower(name)='deepseek' AND app_type='claude'").fetchall()
        if len(rows)!=1:raise ValueError('Switch 中必须有一个明确的 DeepSeek 配置')
        settings=json.loads(rows[0][0])['env']
        if settings.get('ANTHROPIC_BASE_URL','').rstrip('/')!='https://api.deepseek.com/anthropic':
            raise ValueError('Switch 中的 DeepSeek 配置不是官方 Anthropic 兼容接口')
        values={'LLM_PROVIDER':'deepseek','LLM_BASE_URL':'https://api.deepseek.com',
                'LLM_MODEL':settings['ANTHROPIC_MODEL'],'DEEPSEEK_API_KEY':settings['ANTHROPIC_AUTH_TOKEN']}
    else:
        for line in Path(args.source).read_text(encoding='utf-8').splitlines():
            m=re.match(r'\s*(?:export\s+)?([A-Z_][A-Z0-9_]*)\s*=\s*(.*)',line)
            if m:values[m[1]]=m[2].strip().strip('\"\'')
    provider=values.get('LLM_CHAT_PROVIDER',values.get('LLM_PROVIDER'))
    if provider!='deepseek':raise ValueError('来源聊天模型不是DeepSeek')
    service=ModelService(args.data)
    service.configure(values.get('LLM_CHAT_BASE_URL') or values['LLM_BASE_URL'],args.model or values.get('LLM_CHAT_MODEL') or values['LLM_MODEL'],
        values.get('LLM_CHAT_API_KEY') or values.get('LLM_API_KEY') or values['DEEPSEEK_API_KEY'],thinking=values.get('LLM_CHAT_THINKING','disabled'),max_tokens=2048)
    service.publish(service.version)
    service.activate(service.package())
    print('模型配置已加密保存，并发布到已绑定客户端。凭证未输出。')

if __name__=='__main__':main()
