# 胶片引擎（engine/）

联机 App 的冲洗引擎：spektrafilm 的光谱胶片 / 相纸模型，用 Vulkan 计算着色器（vkdt filmsim）在手机 GPU 上冲洗。
这里是 **Photo All 0.3.0（安卓 versionCode 143）** 的引擎，取自独立移植包 `F:\PHOTO ALL phohe\engine-port`
（2026-11-23 快照），替换原来从子模块 `third_party/Photo-All-Android/engine`（Photo All 0.2.2）编译的旧引擎。

## 来源与改动

| 这里 | engine-port | 说明 |
|---|---|---|
| `src/` | `core/src/` | 原样复制，**去掉了 `camera_raw.cpp`**（引擎自带的 LibRaw 读取） |
| `stubs/camera_raw.h`、`stubs/camera_raw_none.cpp` | `core/src/camera_raw.h`、`core/stubs/camera_raw_none.cpp` | 本 App 自己用 `native/src/camera_raw.cpp` + `third_party/LibRaw` 解 RAW，不需要引擎再链一份 LibRaw（会重复符号）；`stubs/` 只对引擎可见，免得和 App 的同名头文件冲突 |
| `shaders/` | `shaders/` | 原样复制（vkdt filmsim、自有着色器、降噪） |
| `cmake/embed_spirv.cmake` | `cmake/embed_spirv.cmake` | 原样复制 |
| `CMakeLists.txt` | — | 新写：用 NDK 的 glslc 编所有 kernel（优化版 + `_safe` 未优化版），不编 LibRaw；有 `third_party/libjxl`（子模块，v0.11.2）时编进 JPEG XL 解码，LibRaw 解不了的 DNG（JPEG XL 压缩，DNG 1.7）改由本引擎的 `dng.cpp` 读 |

没有复制：`prebuilt/`（NDK 自带 glslc，不需要预编译 SPIR-V）、`third_party/libraw`、`third_party/vulkan-headers`
（用 NDK 的）、`data/`（`filmsim.lut`、`spectra-em.lut`、创意 LUT 与子模块 assets 里的**逐字节相同**，
仍由 Gradle 从子模块 `android/app/src/main/assets` 打包）、桌面工具和 `bindings/`。

**本 App 对引擎的改动**（同步新版时要保留）：16 位输出。`DevelopOptions::output16` 打开时，最后一趟编码改用
`encode16`（`shaders/own/encode.comp` 以 `-DOUTPUT_HALF` 再编一份，写半精度浮点图），回读后得到
`DevelopResult::rgba16`（与 `rgba8` 编码完全相同，只是 16 位），`rgba8` 由它换算。用于无损 16 位 TIFF
（`native/src/tiff16.cpp`）。涉及 `src/film_engine.{h,cpp}`、`shaders/own/encode.comp`、本目录 `CMakeLists.txt`。

更新引擎：重新生成 engine-port 后，把 `core/src/`（除 `camera_raw.cpp`）、`shaders/`、`cmake/embed_spirv.cmake`
再复制过来；若 engine-port 的 `CMakeLists.txt` 增删了 kernel，同步改这里的 kernel 列表。

## 与旧引擎（0.2.2）相比

- **重新标定到原版 spektrafilm**：颗粒（同一组参数下强约 3.6 倍、更细）、光晕和乳剂散射、DIR 耦合剂默认值
  （`cp amt` 0.5→1，`lang r/g/b` 1→1.5）、胶片光源改用 CAT16 色适应（灯光片误差减半）、反转片直接扫描不加滤色。
- **相纸白点 / 黑点校正**（`calibrate_print`）：纸白 → 白，最深密度 → sRGB 0.01，中灰对齐原版 117.5/255。
- **新参数**：`grain amt`、`frame mm`（画幅）、`p gamma r/g/b`、`uv cut` / `ir cut`；
  阶段：扩散滤镜 `stage diffusion` / `diff amt` / `diff size` / `diff warm`，扫描仪 `stage scan blur` / `scan sharp` /
  `scan radius` / `black` / `white`，`stage grain blur`、`stage gamut`、`stage meter`、`stage display`、`stage baseline`、`stage negative`。
- **原版测光**：`match_key < 0` 用原版的中央重点平均测光（不低于拍摄曝光）。
- 降噪强度按 RAW 自身噪声自动缩放；线性 DNG（Expert RAW / ProRAW）直接支持；`create(..., safe_shaders)` 未优化着色器。
- HDR+ 合成已删除（GPL 代码）。
- 胶卷 / 相纸列表与序号不变，配方里的 `film` / `paper` 序号仍然有效。

## 许可

引擎自有代码（`src/`、`shaders/own`、本目录的构建脚本）：CC BY-NC 4.0，版权 YZH2006517。
vkdt 着色器与 `src/vkdt/*.h`、`shaders/denoise`：BSD-2-Clause（`shaders/vkdt/LICENCE.bsd2`）。
spektrafilm 胶卷 / 相纸数据（`filmsim.lut`，在子模块 assets 里）：CC BY-SA 4.0（Andrea Volpato）。
