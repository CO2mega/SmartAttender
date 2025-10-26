# GreetingCard (SmartAttender) — Android 签到 / 人脸识别 示例应用
***
## **ai味十足，自行判断是否好用**
***
## 项目简介
这是一个基于 Android + Jetpack Compose 的签到与人脸识别示例应用（项目原名 GreetingCard）。主要功能包括：
- 人脸注册与识别（本地 embedding）
- NFC 卡绑定与刷卡签到
- 签到数据的持久化与导出（Room 数据库）
本示例演示了将 ML 推断、NFC 与本地数据库结合用于考勤/签到场景的实现思路。

## 主要特性
- 本地人脸 embedding 生成与相似度匹配（无需云端识别）
- 支持将人脸与 NFC 卡绑定，刷卡同时验证人脸与卡片
- 使用 Room 持久化 Face、签到记录等数据
- 使用 Jetpack Compose 构建界面

## 运行环境 / 前提
- Android Studio（建议 Arctic Fox 或更高版本）
- 已安装与项目兼容的 Android SDK（参见项目的 Gradle 配置）
- 连接物理 Android 设备或启动 Android 模拟器
- 若使用 NFC 功能，请使用支持 NFC 的真实设备进行测试

## 快速开始（命令行）
在项目根目录中：

Windows:
```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

macOS / Linux:
```bash
./gradlew assembleDebug
./gradlew installDebug
```

建议：在 Android Studio 中打开项目并运行（便于调试与使用 Compose Preview）。

## 项目结构（重要文件与目录）
- app/src/main/java/com/example/greetingcard/
  - MainActivity.kt            — 应用主入口（Compose UI）
  - AdminActivity.kt           — 管理/配置界面（管理员功能）
  - CardScanActivity.kt        — 人脸识别后等待 NFC 刷卡的 Activity，负责 NFC 读取与签到创建
  - viewmodel/MainViewModel.kt — 业务逻辑与数据层桥接（Room 操作、匹配逻辑）
  - data/
    - AppDatabase.kt           — Room 数据库定义
    - FaceEntity.kt / FaceDao.kt
    - AttendanceRecordEntity.kt / AttendanceRecordDao.kt
    - SignInRecord.kt / SignInDao.kt
  - ml/FaceEmbedder.kt         — 用于生成/匹配人脸 embedding 的本地封装
  - ui/                       — Compose 组件与主题（composables/、theme/ 等）

（实际路径请以仓库为准，上述为常见结构示例）

## 主要类简要说明
- MainActivity
  - 应用主界面调度，包含用于展示与触发各功能的 Compose 屏幕。
- AdminActivity
  - 管理员功能（例如导出签到数据、管理用户、数据清理等）。
- CardScanActivity
  - 在人脸识别成功后进入该 Activity，监听 NFC 标签（enableReaderMode / foreground dispatch）并根据读取到的卡号与人脸信息创建签到记录或提示错误。
- MainViewModel
  - 封装数据库（DAO）访问、匹配 embedding 与 NFC、创建签到记录、检查重复签到等核心业务逻辑。
- AppDatabase / DAO
  - Room 的数据库和数据访问对象，负责持久化 Face、SignIn、Attendance 等实体。
- FaceEmbedder
  - 本地机器学习推断代码（加载模型、生成 embedding、计算相似度），与 ViewModel 配合完成识别。

## 导出 / 文件存储说明（调试提示）
如果使用“导出签到数据”功能时遇到问题，可检查以下事项：
- 文件只有一行或包含未替换的占位符（例如以 ${} 包围的字符串）：
  - 请确认在写入前已用真实值替换模板变量（不要直接将包含 `${var}` 的原始模板写入文件）。
- Android 存储策略与导出目录：
  - Android 11+ 引入分区存储（Scoped Storage），请使用 MediaStore 或 SAF（Storage Access Framework）将文件导出到公共目录，或者在确有必要时申请 MANAGE_EXTERNAL_STORAGE（注意该权限受限且需合理使用场景说明）。
- 导出失败或权限问题：
  - 检查运行时权限（READ/WRITE 外部存储，或使用更合规的 API）。
- UI 提示或 Toast 看不到：
  - 检查 Toast/Snackbar 的调用上下文（Activity/Compose）和调用时机；在 Compose 中优先使用 Scaffold + SnackbarHost 显示短消息。

## 常见问题与调试建议
- 人脸识别误差偏大：
  - 确认模型输入预处理（归一化、尺寸、色彩空间）与训练/导入模型时一致。
  - 调整相似度阈值或使用更稳定的比对策略（例如余弦相似度 +阈值）。
- NFC 无法读取：
  - 确认设备支持 NFC 并已打开，应用有相应权限（NFC 权限一般为 android.permission.NFC）。
  - 检查是否正确使用前台分发（foreground dispatch）或 enableReaderMode。
- 数据库读写异常：
  - 使用 Room 的迁移或在开发阶段开启 destructiveMigration（仅用于开发）以避免版本冲突。
  - 在多线程环境下确保对数据库操作使用协程或合适的线程池。、

