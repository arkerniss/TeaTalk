# 茶室 App 图标

## 概念

**一杯茶，也是一个对话框。**

- 奶油色茶杯：茶室的“茶”
- 杯身左下角的三角形：聊天气泡尾巴
- 三缕热气：既是茶的热气，也像聊天里“正在输入”的三个点
- 墨黑底：在任何壁纸/深浅模式下都稳定，与聊天页的纸色/陶土色同一套调性

## 色板

| 用途 | 色值 |
|---|---|
| 背景（墨黑，同 `--ink`） | `#1B1A17` |
| 杯身（纸色，同 `--paper`） | `#FAF8F3` |
| 茶汤（陶土，同 `--clay`） | `#AE5129` |
| 热气（浅陶土，同暗色 `--clay`） | `#D9784F` |

热气在墨黑底上使用暗色主题的陶土值，保证对比度 ≥ 4.5:1。

## 预览

- `teatalk-icon-1024.png`：完整方形
- `teatalk-icon-rounded.png`：圆角方形（README / 商店展示）
- `teatalk-icon-circle.png`：圆形
- `teatalk-icon-sizes.png`：32–256px 小尺寸检查
- `teatalk-icon.svg`：与 Android VectorDrawable 同几何的预览源

## Android 资源

- `drawable/ic_launcher_foreground.xml`：自适应图标前景
- `drawable/ic_launcher_monochrome.xml`：Android 13 主题图标
- `mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`
- `mipmap-anydpi-v33/ic_launcher.xml` / `ic_launcher_round.xml`
- `values/colors.xml`：`ic_launcher_background = #1B1A17`

前景内容统一缩放到自适应图标安全区（108dp 画布中的 66dp 中心区域）。
