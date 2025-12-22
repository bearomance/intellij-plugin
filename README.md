# API Search

快速搜索 Spring API 端点的 IntelliJ IDEA 插件。

## 功能

- 🔍 **快速搜索** - 通过 URL 路径快速定位 Spring Controller 方法
- ⚡ **实时索引** - 项目打开时自动索引，文件变化时自动更新
- 🎯 **一键跳转** - 回车或双击直接跳转到对应方法
- 📦 **多模块支持** - 支持多模块项目，显示模块名称
- 🎨 **HTTP 方法高亮** - GET/POST/PUT/DELETE 等方法用不同颜色区分

## 支持的注解

- `@RequestMapping`
- `@GetMapping`
- `@PostMapping`
- `@PutMapping`
- `@DeleteMapping`
- `@PatchMapping`

## 使用方法

1. 按 <kbd>Option</kbd> + <kbd>F</kbd>（macOS）或 <kbd>Alt</kbd> + <kbd>F</kbd>（Windows/Linux）打开搜索面板
2. 输入 URL 路径关键词进行搜索
3. 使用 <kbd>↑</kbd> <kbd>↓</kbd> 键选择结果
4. 按 <kbd>Enter</kbd> 或双击跳转到对应方法
5. 再次按 <kbd>Option</kbd> + <kbd>F</kbd> 关闭面板并恢复之前的侧边栏

## 安装

### 从本地安装

1. 下载或构建插件 zip 文件
2. <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>
3. 选择 zip 文件并重启 IDE

### 从源码构建

```bash
./gradlew buildPlugin
```

构建产物位于 `build/distributions/` 目录。

## 开发

```bash
# 运行开发版 IDE
./gradlew runIde
```

## 要求

- IntelliJ IDEA 2024.2+
- Java 插件
