# 茶室 App 图标

## 概念

**一杯茶，也是一个对话框。**

- 奶油色茶杯：茶室的“茶”
- 杯身左下角的三角形：聊天气泡尾巴
- 三缕薄荷色热气：既是茶的热气，也像聊天里“正在输入”的三个点
- 深茶绿底：夜间也稳定，和聊天页的茶绿色主色一致

## 色板

| 用途 | 色值 |
|---|---|
| 背景 | `#0E3B35` |
| 杯身 | `#F6EBDD` |
| 茶汤 | `#D79A4A` |
| 热气 | `#9AD9CB` |

## 预览

- `teatalk-icon-1024.png`：完整方形
- `teatalk-icon-rounded.png`：圆角方形
- `teatalk-icon-circle.png`：圆形
- `teatalk-icon-sizes.png`：32–256px 小尺寸检查
- `teatalk-icon.svg`：与 Android VectorDrawable 同几何的预览源

## Android 资源

- `drawable/ic_launcher_foreground.xml`：自适应图标前景
- `drawable/ic_launcher_monochrome.xml`：Android 13 主题图标
- `mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`
- `mipmap-anydpi-v33/ic_launcher.xml` / `ic_launcher_round.xml`
- `values/colors.xml`：`ic_launcher_background`

前景内容统一缩放到自适应图标安全区（108dp 画布中的 66dp 中心区域）。
