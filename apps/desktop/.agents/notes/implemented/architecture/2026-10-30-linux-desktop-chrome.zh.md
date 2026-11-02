# Agent Note: Linux 共用 Mac 桌面命令模型

Status: implemented

[English](2026-10-30-linux-desktop-chrome.md) | 中文

## Problem

Linux 原生标题栏重复会话标题，Electron role 菜单在中文工作区显示英文标签。Linux 快捷键准入与分发也不同于原生 Desktop 策略，仅统一菜单文案不足以获得相同的交互模型。

## Decision

Mac 与 Linux 在桌面外壳共享本地化的文件、编辑、视图、帮助命令分组。Mac 保留系统应用菜单与内嵌控制按钮；Linux 复用现有一体化顶栏布局，使用原生窗口控制按钮并同步文档配色。历史 Windows 布局及 IPC 名称保留为内部兼容细节。原生弹出菜单仍校验所属窗口、顶层 frame、坐标及来源。

Linux Desktop 使用现有原生快捷键优先级、单键及双键准入和版本 2 偏好写入器。版本 1 文档继续可读，成功编辑前不重写。其他配置分区与 Session 格式不变，Web 保留浏览器限制。原生编辑菜单通过已有按键分发保留编辑器自有的撤销行为。

## Alternatives considered

**只翻译 Electron 默认菜单。** 仍会重复窗口标题并保留不同的快捷键策略。

**另建 Linux 渲染外壳。** 会重复桌面顶栏已有的布局、焦点恢复、键盘导航及配色同步。

## Consequences

顶栏菜单、语言变化、原生全屏避让与快捷键分发共用已有实现。Linux 保留右侧原生窗口控制按钮，关闭工作区时按任务状态确认退出，没有后台托盘；Mac 保留原生应用生命周期及 Command 修饰键。到达 Electron 前被操作系统拦截的快捷键不受应用控制。平台模拟测试验证命令一致性，Linux 原生检查验证渲染布局与交互。当前环境不提供可用于视觉验证的 Mac 原生运行时。
