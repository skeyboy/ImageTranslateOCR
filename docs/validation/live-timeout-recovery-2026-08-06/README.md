# 真机持续翻译卡死修复验证（2026-08-06）

## 结论

真机长时间停在“正在翻译”的直接原因已经修复。旧实现的 35 秒外层超时会抛出 `TimeoutCancellationException`，但该异常先被通用 `CancellationException` 分支捕获并重新抛出，导致 `captureInProgress`、连续识别状态和悬浮层没有进入失败清理路径。由于 `TimeoutCancellationException` 是 `CancellationException` 的子类，旧悬浮层会一直保留“正在翻译”。

修复后先处理翻译超时，再处理用户停止、页面移动和代次失效等普通取消；真正超时会清理状态、恢复“开始识别”入口，并提示“识别翻译超时，已停止本次任务，请重试”。同时按执行链路提供不同预算，避免自建 Qwen 9B 的正常慢请求被 35 秒提前取消。

## 修复内容

| 执行链路 | 超时预算 |
|---|---:|
| 端侧 OCR + 端侧翻译 | 35,000ms |
| Paddle 网络 OCR/翻译一体服务 | 40,000ms |
| 端侧 OCR + 自建语义翻译 | 75,000ms |
| 端侧 OCR + Pnuts 网络翻译 | 90,000ms |

关键行为：

1. `TimeoutCancellationException` 在普通 `CancellationException` 之前进入 `failScreenshot`。
2. 失败清理会重置截屏、处理、展示和连续翻译状态，恢复可重试悬浮窗。
3. 超时只提示一次，不把用户主动取消误报为失败。
4. 新增 `backend / engine / timeoutMs / generation` 启动日志，便于真机维护时直接判断当前预算。

## 旧版现象证据

用户截图和安装前留存截图在 12:32 与 12:39 均停在“正在翻译”，可确认至少持续 7 分钟，远超旧版 35 秒上限。应用进程、前台服务和录屏投影当时仍存活，Axum 审计表没有对应的新完成记录，因此不是进程崩溃或悬浮窗被系统回收，而是协程超时后没有收口 UI 状态。

![旧版卡死状态](before-stuck.png)

## 真机请求与输出

验证环境：

```text
device=23113RKC6C
package=com.example.imagetranslate
backend=SELF_HOSTED
self_hosted_base_url=http://192.168.0.63:8090
model=qwen3.5:9b
modelReachable=true
modelAvailable=true
```

Axum 收到的真实页面请求审计：

```text
request_id=5c667336-b849-4b54-960e-3c89f75b4a9b
scene=LIVE_SCREEN
group_count=14
region_count=14
input_chars=786
model=qwen3.5:9b
status=FAILED
duration_ms=48248
```

本轮服务端请求失败后，Android 保持原有兼容策略，对 14 个组执行端侧翻译回退。最终端侧输出：

```text
generation=1
total_ms=49838
ocr_ms=620
translation_ms=48874
render_ms=292
recognized=28
translated_regions=12
patches=9
failed=2
source_latin_tokens=105
retained_latin_tokens=2
retained_latin_ratio=0.0190476
```

关键日志：

```text
Self-hosted semantic fallback: count=14, codes=[SELF_HOSTED_REQUEST_FAILED]
Overlay translation completed: totalMs=49838, recognized=28, patches=9
overlay_translation_presented: generation=1, patches=9, presentation_ms=15
```

结果在约 49.8 秒提交，处于自建链路 75 秒预算内。悬浮条已从“正在翻译”切换为“总耗时 49786ms”，并显示翻译补丁。

![修复后完成状态](after-success.png)

## 受控超时终态验证

为真实覆盖外层超时分支，临时在局域网 `8091` 启动只接收请求但不返回响应的测试端点，并把 Pnuts 网络后端临时指向该端口。请求包含与上轮相同页面的 14 个 OCR 区域：

```text
request_id=8dc6fc00-520d-4038-9e65-3dfbb701add2
schema_version=1
scene=ANDROID_CLIENT
region_count=14
backend=NETWORK
engine=LOCAL_PIPELINE
timeout_ms=90000
started_at=2026-08-06 12:54:09.191
failed_at=2026-08-06 12:55:39.199
elapsed_ms=90008
result=TimeoutCancellationException
```

端点按测试设计没有返回翻译响应。90 秒到期后日志明确输出：

```text
Screen capture failed
kotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 90000 ms
```

失败处理随后清除忙碌状态，真机悬浮窗恢复完整控制条和“开始识别”按钮，原网页未被错误覆盖。这证明本次修复不仅让 49.8 秒慢请求可以完成，也真实覆盖了超时后的可重试终态。

![受控超时后恢复可重试](after-timeout-recovered.png)

验证结束后已关闭 `8091` 临时监听，真机配置恢复为 `SELF_HOSTED / http://192.168.0.63:8090`，`/healthz` 再次确认 `qwen3.5:9b` 可达且已安装。

## 构建与安装

```text
unit_tests=167
failures=0
errors=0
skipped=0
apk=app/build/outputs/apk/debug/app-debug.apk
apk_size=231042767 bytes
apk_sha256=b78195c5e567d7f12c9215338e1fb7523e468b380cf872e3f330f72234a04a16
adb_install=Success
lastUpdateTime=2026-08-06 12:47:27
```

## 边界说明

本轮证明了“慢请求可以在合理预算内完成；预算超时后不会永久转圈”。正常自建请求的 Axum 审计本身为 `FAILED`，端侧依靠兼容回退完成了回贴，因此 Qwen 对该 14 组、786 字符请求的失败原因仍应单独分析。当前审计表只保存状态和耗时，没有保存错误码或安全摘要；后续建议为服务审计补充 `error_code`，但不要记录完整屏幕文本。
