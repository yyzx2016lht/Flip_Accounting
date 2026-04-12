翻转记账 - MVP 项目（说明）

包名：tao.test.flipaccounting
minSdk：24
UI：XML（传统 View）

功能（MVP）：
- 前台服务（OverlayService）保持运行（由 MainActivity 启动/停止）。
- 手动触发悬浮窗（MainActivity -> "手动显示悬浮窗"）。
- 悬浮窗表单：金额、类型、账户、分类、货币、时间、备注；保存后构造 qianji:// URL 调用钱迹。
- 设置页：用逗号分隔管理账户/分类/货币列表（SharedPreferences 简单实现）。

如何在 Android Studio 打开并测试：
1. 将上述文件放入一个新的 Android 项目（确保包名为 tao.test.flipaccounting），或用现有项目按路径替换相应文件。
2. 在真机上运行（推荐真机）：连接设备并运行 app。
3. 在应用中先点击“请求悬浮窗权限”，在系统界面里允许“在其它应用上层显示”权限。
4. 点击“启动前台服务”启动 OverlayService（服务在通知栏显示）。
5. 点击“手动显示悬浮窗”测试悬浮窗弹出并输入数据。
6. 点击“保存”后，应用会尝试通过 Intent 打开 qianji://publicapi/addbill?...（需要钱迹 App 已安装才能成功跳转）。

备注与后续：
- 我已参考你给出的 AutoAccounting 源码（FlipDetector、FloatingTip、BillWindowManager、OverlayService），并按 MVP 精简实现。后续如需把翻转检测接入：把 FlipDetector 的实现（你可粘入原仓库中的 FlipDetector.kt）放入项目，并在 OverlayService 中注册/启动检测器，在回调中调用 overlayManager.showOverlay() 即可。
- 当你提供钱迹的官方文档或确认参数规则后，我会把 Utils.buildQianjiUrl 调整为文档精确实现（支持 fee、coupon、catechoose、catetheme、showresult 等可选参数）。

如果这些文件没问题，我可以继续：
- 把整个项目打包成 ZIP（包含 gradle wrapper 与 settings.gradle），
- 或把代码扩展为更接近 AutoAccounting 的悬浮窗样式（倒计时、位置配置、动画），
- 或在你粘入 FlipDetector 之后把翻转触发接入服务。
