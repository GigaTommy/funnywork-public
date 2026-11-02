---
description: "记录持久化类型更改及其兼容性确认。"
kind: persistence-change
---

# 2026-10-12-fixed-result-files

[English](2026-10-12-fixed-result-files.md) | 中文

## 概述

为已声明文件增加可选固定版本结果清单。

## 目录

- [声明](#declaration)
- [兼容性](#compatibility)
- [验证](#verification)
- [开发备注](#dev-note)

<a id="declaration"></a>
## 声明

```yaml persistence-change
schemaVersion: 1
id: 2026-10-12-fixed-result-files
baseline: false
changes:
  - root: "event:deliverables/presented"
    previous: "2026-09-11-initial"
    after: "f7d4dd9433a8f51ca0349e408afb3c1d5e861e1700c8aae7325a54b24426f903"
    decision: same-version
```

<a id="compatibility"></a>
## 兼容性

已有只含路径的交付事件继续有效；结果原文件与预览变体是不可变附件引用。延迟预览完成归属于原结果版本和轮次，不增加原生事件类型。

<a id="verification"></a>
## 验证

发布插件 Loader 组合、已保存值工作表预览与交付界面测试通过，已有声明文件行为继续受测试覆盖。

<a id="dev-note"></a>
## 开发备注

无。
