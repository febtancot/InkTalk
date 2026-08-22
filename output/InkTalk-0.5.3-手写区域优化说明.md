---
title: InkTalk 0.5.3 手写区域优化说明
version: 0.5.3
status: release
date: 2026-08-21
---

# InkTalk 0.5.3 手写区域优化说明

## 版本内容

- 手写模式回收画板下方 `24dp` 外部状态栏，增加手机、外屏和窄分屏中的可写高度。
- 手写面板左右内边距从 `10dp` 缩小为 `6dp`，顶部内边距从 `6dp` 缩小为 `2dp`。
- 模型准备、识别中、部分模型不可用和识别失败状态统一显示在手写框内部。
- 离开手写模式后，语音、数字键盘和指令模式继续使用原有外部状态栏。
- 候选栏继续保留固定高度，避免候选出现或消失时重新引发布局跳动。

## 版本

- `versionCode 10`
- `versionName 0.5.3`

## 验证边界

自动测试和构建用于验证状态逻辑、资源契约、代码质量与签名制品。真实设备仍需检查手写区域尺寸、提示位置、触控轨迹、候选选择以及宿主编辑器行为。

## 自动验证结果

- 67 项 JVM 单元测试通过。
- Debug 与签名 Release 构建通过。
- Release Lint：`0 errors, 72 warnings`。
- APK Signature Scheme v2、v3、v4 验证通过，zipalign 检查通过。
- Release APK SHA-256：`005670bbabc51ebfef38ffc8353a36bbb97c3d4caafb821208e08ee82c1c1fd5`。
