GreetingCard Android 应用

概述

这是一个基于 Android + Jetpack Compose 的签到/人脸识别示例应用（项目名 GreetingCard）。功能包括：人脸注册/识别、NFC 卡绑定与刷卡签到、签到数据的持久化（Room 数据库）、及部分本地机器学习嵌入（face embedding）逻辑。

本文档提供项目结构概览、构建与运行步骤、主要类说明，以及常见问题与调试建议。

快速开始

前提：
- Android Studio（推荐 Arctic Fox 或更高版本）
- 已安装与项目兼容的 Android SDK（参见项目的 Gradle 配置）
- 连接 Android 设备或启动模拟器

构建与运行（命令行/Windows）
- 在项目根目录打开 cmd.exe
- 同步与构建：
  .\gradlew.bat assembleDebug
- 直接安装到已连接设备：
  .\gradlew.bat installDebug
- 在 Android Studio 中打开项目并运行（推荐）

项目结构（重要文件与目录）
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

主要类简要说明
- MainActivity
  - 应用主界面调度，包含用于展示与触发各功能的 Compose 屏幕。
- AdminActivity
  - 提供管理员相关功能（例如导出签到数据、管理用户等）。
- CardScanActivity
  - 在人脸识别成功后进入该 Activity，监听 NFC 标签（enableReaderMode / foreground dispatch）并根据读取到的卡号与人脸信息创建签到记录或提示错误。
- MainViewModel
  - 封装数据库（DAO）访问、匹配 embedding 与 NFC、创建签到记录、检查重复签到等核心业务逻辑。
- AppDatabase / DAO
  - Room 的数据库和数据访问对象，负责持久化 Face、SignIn、Attendance 等实体。
- FaceEmbedder
  - 本地机器学习推断代码（加载模型、生成 embedding、计算相似度），与 ViewModel 配合完成识别。

导出 / 文件存储说明（调试提示）
- 如果你在使用应用的“导出签到数据”功能时遇到：文件只有一行、文件内容包含未替换的占位符（例如以 ${} 包围的字符串），或文件导出到意外目录，请检查实现导出功能的代码处（很可能在 AdminActivity 或 MainViewModel 的导出方法中）。常见问题与排查思路：
  - 文件内容为模板未替换：请确认字符串模板在写入前是否已用真实值替换（避免直接写入包含 ${var} 的原始字符串）。
  - 导出目录问题：Android 11+ 存储沙箱限制不同，确保使用正确的公共 Download 目录 API 或请求 MANAGE_EXTERNAL_STORAGE 权限（如果必要）。也可使用 MediaStore API 或者在应用可访问的公共目录（例如 Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) 的替代方案）创建并写入文件。你也可以临时将文件写入 app 的 cache 目录做调试，但这不是下载目录。
  - Toast/UI 提示位置错误：检查 Toast 的调用上下文（Activity/Fragment/Compose）与 show 时机。Compose 中显示短消息可考虑使用 Snackbar 替代。

