// Protocol presets are preparation aids, never evidence of hardware or model compatibility.
export const deploymentPresets = [
  { id: 'mindie-deepseek', label: 'MindIE · DeepSeek' },
  { id: 'mindie-qwen', label: 'MindIE · Qwen' },
  { id: 'vllm-ascend-deepseek', label: 'vLLM-Ascend · DeepSeek' },
  { id: 'vllm-ascend-qwen', label: 'vLLM-Ascend · Qwen' }
] as const;
