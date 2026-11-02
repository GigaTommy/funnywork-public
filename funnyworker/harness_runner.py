"""SDK bridge. Provider credentials arrive only through the private process pipe."""
import json
import os
import sys
from deepseek_harness import DeepSeekHarness

def main():
    config=json.load(sys.stdin)
    model=config.get('model_config',{})
    with DeepSeekHarness(dsh_bin=config.get('dsh_bin'),profile=config['profile'],
        patches=tuple(config['patches']),provider=config['provider'],model=model.get('model',config.get('model')),
        cwd=config['workspace'],dsh_home=config['home'],base_url=model.get('endpoint',config.get('base_url')),
        api_key=model.get('api_key',os.environ.get('FUNNYWORKER_MODEL_KEY')),
        env={'FUNNYWORKER_THINKING':model.get('thinking','disabled'),'FUNNYWORKER_MAX_TOKENS':str(model.get('max_tokens',2048))},
        request_timeout_seconds=100,max_tokens=model.get('max_tokens',2048)) as harness:
        result=harness.run(config['prompt'],session_id='work')
    print(json.dumps({'text':result.final_response,'finish_reason':result.finish_reason},ensure_ascii=False))

if __name__=='__main__':main()
