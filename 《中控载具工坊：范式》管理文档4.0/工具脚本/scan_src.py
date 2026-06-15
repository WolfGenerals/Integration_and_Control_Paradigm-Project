"""
scan_src.py — 自动扫描 src/main/java/ 生成代码文件索引骨架。

用法：
    python 工具脚本/scan_src.py

输出：
    更新 3-架构与代码索引/3.2-代码文件索引.md 的表格内容。
    人工补写每文件"一句话职责"即可。
"""

import os
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]  # 项目根目录
SRC_DIR = PROJECT_ROOT / "src" / "main" / "java" / "com" / "hainabaichuan75" / "iac_p"
OUTPUT_FILE = Path(__file__).resolve().parent.parent / "3-架构与代码索引" / "3.2-代码文件索引.md"

# 包 → 模块分组
PACKAGE_GROUPS = {
    "": "入口与配置",
    "index": "注册中心",
    "content/blocks/seat": "游戏内容 - Seat",
    "content/blocks/cockpit": "游戏内容 - 驾驶舱",
    "content/blocks/suspension_test": "游戏内容 - 悬挂",
    "content/blocks/turret": "游戏内容 - 炮塔",
    "content/blocks/debug_gear": "游戏内容 - 调试齿轮",
    "content/blocks/debug_swivel": "游戏内容 - 调试 SwivelBearing",
    "network/packets": "网络包",
    "network": "网络",
    "events": "事件",
    "client": "客户端",
    "client/renderer": "客户端 - 渲染器",
    "client/screen": "客户端 - 界面",
    "mixin": "Mixin",
    "affiliation": "归属系统",
}


def get_relative_path(filepath: Path) -> str:
    """返回相对于 SRC_DIR 的路径（Unix 风格）"""
    rel = filepath.relative_to(SRC_DIR)
    return str(rel.as_posix())


def get_package_group(rel_path: str) -> str:
    """根据相对路径推断模块分组"""
    # 入口文件（直接在 iac_p 根目录，不在子包中）
    if "/" not in rel_path and rel_path != "":
        return "入口与配置"
    for prefix, group in sorted(PACKAGE_GROUPS.items(), key=lambda x: -len(x[0])):
        if prefix and rel_path.startswith(prefix):
            return group
    return "其他"


def scan_java_files() -> dict[str, list[tuple[str, str]]]:
    """扫描 Java 文件，按模块分组返回 [(相对路径, 文件名), ...]"""
    groups: dict[str, list[tuple[str, str]]] = {}
    for f in sorted(SRC_DIR.rglob("*.java")):
        rel = get_relative_path(f)
        group = get_package_group(rel)
        groups.setdefault(group, []).append((rel, f.name))
    return groups


def generate_markdown(groups: dict[str, list[tuple[str, str]]]) -> str:
    """生成 markdown 表格"""
    lines = [
        "---",
        "自动生成于: 2026-06-15",
        "说明: 运行 `python 工具脚本/scan_src.py` 更新本文件",
        "---",
        "",
        "# 代码文件索引",
        "",
        "> 每文件一句话职责。按功能模块分组。",
        "> **人工维护**：新增文件后运行本脚本更新骨架，然后补写职责描述。",
        "",
    ]

    for group_name in [
        "入口与配置",
        "注册中心",
        "游戏内容 - 驾驶舱",
        "游戏内容 - 悬挂",
        "游戏内容 - 炮塔",
        "游戏内容 - 调试齿轮",
        "游戏内容 - 调试 SwivelBearing",
        "游戏内容 - Seat",
        "网络",
        "网络包",
        "事件",
        "客户端",
        "客户端 - 渲染器",
        "客户端 - 界面",
        "Mixin",
        "归属系统",
        "其他",
    ]:
        files = groups.get(group_name, [])
        if not files:
            continue

        lines.append(f"## {group_name}\n")
        lines.append("| 文件 | 职责 |")
        lines.append("|------|------|")
        for rel_path, fname in files:
            lines.append(f"| `{rel_path}` | （待补充） |")
        lines.append("")

    return "\n".join(lines)


def main():
    groups = scan_java_files()

    output = generate_markdown(groups)

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write(output)

    total = sum(len(v) for v in groups.values())
    print(f"✅ 已扫描 {total} 个 Java 文件")
    print(f"📝 输出: {OUTPUT_FILE}")
    print("⚠️  请人工补写每文件的 '一句话职责' 列")


if __name__ == "__main__":
    main()
