# deepseek harness-Android

蓝色鲸鱼娘主题的 DSH Android 移动端定制版本。首页、启动页和文件空白状态使用蓝白女仆装角色插画，并改善手机布局、返回操作与启动恢复。

[下载 APK](https://github.com/shuoshuochen44-commits/deepseek-harness-Android/releases/latest) · [版本说明](docs/更新日志.md) · [真机验证](docs/真机验证.md) · [构建与测试](docs/构建与测试.md)

## 安装

1. 在 Releases 下载 DeepSeek-Harness-Android-v0.2.3.apk。
2. 手机允许本次安装后打开应用。首次运行需要解压内置运行时，请等待启动完成。
3. 在设置中配置自己的模型服务或登录账号。应用不附带 API 密钥。

系统要求：Android 8.0 及以上，64 位 ARM（arm64-v8a）。当前版本：0.2.3-mobile，包名 io.dsh.android。

## 页面预览

| 浅色首页 | 深色首页 |
| --- | --- |
| ![浅色首页](docs/images/home-light.png) | ![深色首页](docs/images/home-dark.png) |

## 本版改进

- 一致的蓝色鲸鱼娘女仆插画，资源随安装包离线加载。
- 手机左侧栏覆盖展开，点击空白处可收起，首页不再被挤成窄条。
- 返回键优先收起键盘、设置和右侧面板；插件等全局功能页回到首页。
- 适配设置页、明暗主题、长代码滚动和触控区域。
- 启动就绪检查在后台执行，页面变化合并处理，隐藏时暂停观察。
- 加载失败提示与重试、渲染进程退出处理，以及旧界面监听清理。

## 源码范围与验证

本仓库提供这次移动端优化的源码、资源和构建脚本补丁，需要与对应版本的原 DSH Android 工程配合使用；不是包含全部运行时依赖的独立源码工程。可直接使用 Releases 中的完整 APK。

38 项自动检查通过；已在 V2458A / Android 16 真机验证安装、启动、首页明暗主题、设置、左右侧栏返回、插件返回、键盘收起和前后台恢复。没有进行启动提速百分比测量，完整边界见真机验证文档。

## 维护

维护者：[@shuoshuochen44-commits](https://github.com/shuoshuochen44-commits)。仓库公开供查看和下载，原仓库的提交与发布权限由维护者管理，未添加其他协作者。

这是非官方 Android 定制发布；DeepSeek / DSH 品牌、官方图标与运行时的权利归原权利人。插画为本次定制生成，提示词见[素材说明](docs/素材说明.md)。
