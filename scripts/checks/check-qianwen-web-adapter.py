#!/usr/bin/env python3
"""Verify an existing qianwen.com adapter; fixture results never count as live tests.

Use the isolated test environment's Python, with fastapi/httpx installed.
--fixture-module points to an audited adapter COPY with a non-secret config.json.
--base-url tests a running local gateway and reads its key from --config locally.
Reports contain verdicts and protocol metadata, never credentials or chat bodies.
"""
import argparse
import asyncio
import importlib.util
import json
from pathlib import Path
import time

import httpx


def inspect_response(response):
    result = {"http_status": response.status_code,
              "content_type": response.headers.get("content-type", "")}
    if response.status_code != 200:
        result["passed"] = False
        try:
            code = response.json().get("error", {}).get("code")
            if code in {"risk_control", "upstream_error", "rate_limited", "cookie_missing", "cookie_expired", "invalid_api_key"}:
                result["error_code"] = code
        except (ValueError, AttributeError):
            pass
    return result


async def checks(client, model, authorization):
    headers = {"Authorization": "Bearer " + authorization}
    path = "/v1/chat/completions"
    base = {"model": model, "messages": [{"role": "user", "content": "请只回复 FW_PROBE_OK。"}]}
    results = {}
    response = await client.post(path, headers=headers, json={**base, "stream": False})
    results["conversation"] = inspect_response(response)
    if response.status_code != 200:
        results["streaming"] = {"passed": None, "reason": "upstream conversation failed"}
        results["tools"] = {"passed": None, "reason": "upstream conversation failed"}
        return results
    message = response.json().get("choices", [{}])[0].get("message", {})
    results["conversation"]["passed"] = message.get("content", "").strip().rstrip("。.!！") == "FW_PROBE_OK"
    start = time.monotonic()
    async with client.stream("POST", path, headers=headers, json={**base, "stream": True}) as response:
        stream = inspect_response(response)
        stream["sse"] = "text/event-stream" in stream["content_type"]
        count, done, invalid, first = 0, False, False, None
        async for line in response.aiter_lines():
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                done = True
                continue
            try:
                item = json.loads(payload)
                choices = item.get("choices", [])
                if choices and "delta" in choices[0]:
                    count += 1
                    if first is None:
                        first = round(time.monotonic() - start, 3)
                else:
                    invalid = True
            except ValueError:
                invalid = True
        stream.update({"chunk_count": count, "done_event": done, "first_chunk_seconds": first,
                       "passed": response.status_code == 200 and stream["sse"] and count > 0 and done and not invalid})
        results["streaming"] = stream
    tool = {"type": "function", "function": {"name": "fw_probe_add",
            "description": "合成验收工具，仅计算两个整数的和；不操作文件或运行命令。",
            "parameters": {"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
                           "required": ["a", "b"], "additionalProperties": False}}}
    messages = [{"role": "user", "content": "这是工具调用验收。请先调用 fw_probe_add，参数 a=2、b=3。不要直接心算。收到工具结果后，原样返回结果中的 receipt。"}]
    response = await client.post(path, headers=headers, json={"model": model, "messages": messages, "tools": [tool], "stream": False})
    result = inspect_response(response)
    results["tools"] = result
    result["passed"] = False
    if response.status_code != 200:
        return results
    choice = response.json().get("choices", [{}])[0]
    assistant = choice.get("message", {})
    calls = assistant.get("tool_calls", [])
    result["structured_tool_call"] = len(calls) == 1 and choice.get("finish_reason") == "tool_calls"
    if not result["structured_tool_call"]:
        return results
    call = calls[0]
    try:
        arguments = json.loads(call["function"]["arguments"])
        if call["function"]["name"] != "fw_probe_add" or arguments != {"a": 2, "b": 3} or not call.get("id"):
            return results
    except (KeyError, ValueError, TypeError):
        return results
    # This is the only local tool execution. No model-supplied code is executed.
    receipt = "FW_TOOL_RECEIPT_5"
    messages += [assistant, {"role": "tool", "tool_call_id": call["id"],
                             "content": json.dumps({"sum": 2 + 3, "receipt": receipt})}]
    response = await client.post(path, headers=headers, json={"model": model, "messages": messages, "stream": False})
    result["roundtrip_http_status"] = response.status_code
    if response.status_code == 200:
        content = response.json().get("choices", [{}])[0].get("message", {}).get("content", "") or ""
        result["roundtrip_receipt_received"] = receipt in content
        result["passed"] = result["roundtrip_receipt_received"]
    return results


async def guarded_checks(client, model, key):
    try:
        return await checks(client, model, key)
    except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
        # Never serialize exception messages: third-party errors may include bodies.
        return {"protocol_or_transport": {"passed": False, "error_type": type(error).__name__}}


async def main(args):
    config = json.loads(Path(args.config).read_text())
    report = {"mode": "fixture" if args.fixture_module else "live", "checks": {}}
    if args.fixture_module:
        spec = importlib.util.spec_from_file_location("qwen_adapter_under_test", args.fixture_module)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        async def synthetic_complete(prompt, upstream, mode):
            if "[工具执行结果] (工具 " in prompt:
                return "FW_TOOL_RECEIPT_5", ""
            if "fw_probe_add" in prompt:
                return '<tool_call>{"name":"fw_probe_add","arguments":{"a":2,"b":3}}</tool_call>', ""
            return "FW_PROBE_OK", ""

        module.qwen_complete = synthetic_complete
        transport = httpx.ASGITransport(app=module.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://fixture.local") as client:
            report["checks"] = await guarded_checks(client, args.model, config["api_key"])
    else:
        url = httpx.URL(args.base_url)
        if url.scheme != "http" or url.host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("This probe only sends credentials to a local isolated gateway")
        async with httpx.AsyncClient(base_url=args.base_url.rstrip("/"), timeout=100, trust_env=False) as client:
            report["checks"] = await guarded_checks(client, args.model, config["api_key"])
    report["all_passed"] = all(c.get("passed") is True for c in report["checks"].values())
    report["safe_for_funnywork_integration"] = report["mode"] == "live" and report["all_passed"]
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-module")
    parser.add_argument("--base-url", default="http://127.0.0.1:18301")
    parser.add_argument("--config", required=True)
    parser.add_argument("--model", default="qwen-web")
    parser.add_argument("--output", required=True)
    asyncio.run(main(parser.parse_args()))
