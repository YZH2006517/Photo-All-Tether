# 相机拍摄后自动传至手机，在手机中模拟真实胶片冲洗效果

相机拍摄后自动传至手机，在手机中模拟真实胶片冲洗效果。

胶片引擎、胶片 / 相纸光谱数据和 LUT 都来自 [Photo-All-Android](https://github.com/YZH2006517/Photo-All-Android)，原样复用；它是 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 在 [vkdt](https://github.com/hanatos/vkdt) 中 GPU 实现的移植。这个项目做的是另一半：USB 连接相机、管理存储卡里的照片，以及解码各品牌的 RAW。

> 当前版本 **0.0.2**：修复导入相机旧照片，界面全面重做，加入浅色模式、全屏和横屏沉浸数据栏，见 [更新记录](CHANGELOG.md)。真机只测过**尼康 Z 6II**，其他品牌已按 libgphoto2 的做法适配，还没在真机上验证，见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 下载

**[下载 PhotoAll-Tether-0.0.2.apk](https://github.com/YZH2006517/Photo-All-Tether/releases/download/v0.0.2/PhotoAll-Tether-0.0.2.apk)** · [所有版本](https://github.com/YZH2006517/Photo-All-Tether/releases) · [更新记录](CHANGELOG.md)

- 在手机上打开 APK 安装，需要允许安装未知来源应用
- Android 10（API 29）及以上，64 位 ARM（arm64-v8a），GPU 支持 Vulkan 1.1，手机支持 USB OTG
- 这是 debug 签名的安装包，以后换了签名的版本需要先卸载再安装
- 安装后用数据线连接相机并开机，手机弹出提示时选「Photo All 联机」并允许访问

遇到问题：在应用里「设置 → 诊断 → 复制诊断报告」，粘贴到 [Issues](https://github.com/YZH2006517/Photo-All-Tether/issues)，报告不含照片。

## 功能

**联机拍摄**
- 用 USB-C 线连接相机，插上后应用自动打开
- 用相机自己的快门拍，RAW 自动传到手机（约 25 MB/s），按当前配方冲洗；连拍时会排队，一张在传，一张在冲
- 冲洗前先把亮度对齐相机机内 JPEG 的亮度，所见即所得（尼康 RAW 一般比机内 JPEG 暗 1–2 档）
- 沉浸模式：全屏只显示冲洗好的成片，新照片冲好就自动淡入换上；横屏时右侧一栏显示相机、胶卷、本张参数和暗房状态
- 胶卷计数：一卷 36 张，3D 暗盒随拍摄走片，拍完自动倒片
- 前台服务：锁屏或切到别的应用时，照片照样接收和冲洗

**存储卡管理**
- 按日期分组浏览双卡槽里的照片；RAW+JPEG 合成一张显示，标出是否已导入、是否受保护
- 筛选：全部 / RAW / JPEG / 未导入 / 受保护
- 长按后滑动多选，可以整天全选；对选中的照片导入原片、冲洗、保护或删除
- 大图预览：显示 JPEG，或 NEF 里内嵌的全尺寸 JPEG；左右滑动切换

**冲洗配方**
- 22 种胶片、9 种相纸、400 多个创意 LUT，放大机滤色（自动中性 / 保留胶片本色）
- 曝光补偿；白平衡可选相机、按胶片、日光、钨丝灯或自定义色温；颗粒、光晕、DIR 耦合剂、RAW 降噪
- 胶片引擎的全部可调参数，按类别折叠
- 配方页用最新一张 RAW 实时预览

**暗房**
- 处理队列：显示传输和冲洗进度
- 成片 / 原片两个相册：重冲、分享、删除；每张照片都有拍摄参数和冲洗档案
- 可以打开手机里已有的 RAW 或 JPEG 来冲洗

**其他**
- 横屏专门排版：左侧导航栏，相机页和暗房页分两栏，配方页左边是大图预览
- 深色 / 浅色 / 跟随系统；默认全屏；转动手机时出现横竖屏切换按钮
- 相机信息页：型号、固件、电量、存储卡、拍摄设置和 USB 传输统计
- 适配不同屏幕尺寸和系统字体大小
- 错误日志：USB 每一步的记录、出错时的状态和崩溃报告都会保存，可以一键复制诊断报告

## 支持的相机

| 品牌 | 状态 |
|---|---|
| 尼康 | ✅ 真机测过（Z 6II）。用尼康 GetEvent 发现新照片 |
| 佳能 EOS | 已适配：进入 EOS 联机模式，轮询佳能的事件 |
| 索尼 | 已适配：「PC 遥控」模式从相机内存收照片；「MTP」模式只能浏览导入 |
| 富士 | 已适配：联机模式下定时查找新文件；X-Trans 由 LibRaw 去马赛克 |
| 松下、OM System、宾得、徕卡、适马、哈苏 | 按标准 PTP 处理 |

RAW 解码用 [LibRaw](https://www.libraw.org/) 0.22.3，支持 1200 多种机型。拜耳传感器的去马赛克和后续处理都由胶片引擎在 GPU 上做；X-Trans、Foveon 等其他传感器先由 LibRaw 去马赛克。**尼康 HE / HE★ 高效 RAW 不支持**，请在相机里选「无损压缩」或「压缩」。

各品牌在相机上要怎么设置、有哪些已知问题，见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 系统要求

- Android 10（API 29）及以上，64 位 ARM（arm64-v8a）
- 手机支持 USB OTG（作为主机），GPU 支持 Vulkan 1.1
- USB-C 对 USB-C 的数据线，或 OTG 转接头加相机原配线

## 从源码构建

需要：

- Android SDK（compileSdk 36）、NDK `28.2.13676358`、CMake `3.31.6`
- JDK 17

```bash
git clone --recursive https://github.com/YZH2006517/Photo-All-Tether.git
cd Photo-All-Tether/android
./gradlew assembleDebug
```

两个子模块：

- `third_party/Photo-All-Android`：胶片引擎源码和胶片 / LUT 数据，原样编译进来。想用自己的另一份代码，可以加参数 `-Pphotoall.phone=<路径>`。
- `third_party/LibRaw`：RAW 解码库。

### 桌面测试工具

`native/desktop` 里的 `nef_develop` 用同一套 RAW 解码和胶片引擎在电脑上冲洗 RAW，方便对比参数。Windows 上要在 MSVC 环境（`vcvars64.bat`）里构建：

```bash
cmake -S native/desktop -B build/desktop -G Ninja -DCMAKE_BUILD_TYPE=Release -DFILMENGINE_NDK=<NDK 路径>
cmake --build build/desktop --target nef_develop
build/desktop/nef_develop --assets third_party/Photo-All-Android/android/app/src/main/assets \
    --in DSC_0001.NEF --out out.ppm --long-edge 2048 --set film=4 --match-key 0.18
```

`--generic` 让拜耳文件也走 LibRaw 去马赛克的通用路径，用来检查这条路径。`--kelvin` / `--tint` 用来指定色温和色调。

## 项目结构

```
android/                Android 应用（Kotlin，无第三方依赖）
  app/src/main/cpp/     JNI 桥：RAW → 胶片引擎
  app/src/main/java/org/photoall/tether/
    ptp/                USB 上的 PTP 协议：事务、数据集、出错恢复
    camera/             相机连接、各品牌适配（Vendors）、存储卡文件、前台服务
    develop/            传输 / 冲洗队列、本机相册、配方预览
    engine/             胶片引擎的 Kotlin 接口、配方、LUT
    ui/                 相机、暗房、配方、设置四个页面，看图和沉浸模式
native/
  src/camera_raw.*      LibRaw → 引擎的 RawImage（拜耳）或线性 Rec.2020（其他传感器）
  desktop/              桌面测试工具 nef_develop
docs/COMPATIBILITY.md   各品牌兼容性与调研记录
third_party/            子模块：Photo-All-Android、LibRaw
```

## 第三方代码与许可

| 内容 | 来源 | 许可 |
|---|---|---|
| 胶片引擎、光谱数据、LUT | [Photo-All-Android](https://github.com/YZH2006517/Photo-All-Android)（其中各部分的来源见该仓库） | GPL-3.0 及各自的许可 |
| RAW 解码 | [LibRaw](https://github.com/LibRaw/LibRaw) 0.22.3 | LGPL-2.1 / CDDL-1.0 |
| 各品牌 PTP 扩展的资料 | [libgphoto2](https://github.com/gphoto/libgphoto2) `camlibs/ptp2`（只作参考，代码是重新写的） | LGPL-2.1 |

本项目以 [GPL-3.0](LICENSE) 发布（引擎中移植的 hdr-plus-swift 代码要求如此）。相机品牌和型号名称属于各自的所有者，本项目与这些公司没有关联。
