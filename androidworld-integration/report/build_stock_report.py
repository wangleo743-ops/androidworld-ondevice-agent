"""Build an offline HTML + TXT report from one finalized AndroidWorld batch.

Reads explicitly selected finalized batches, their phone-result/step-timing,
and preflight.json only to verify matching artifacts for a continuation report.
Screenshot paths are linked, never fetched or modified. No device/API access.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import html
import json
import math
import os
from pathlib import Path
import re
from urllib.parse import quote

FINAL_STATES = {"completed", "completed_with_unscored_failures", "stopped_incomplete"}
TASK_LABELS = {
    "ClockStopWatchRunning": "启动秒表",
    "ClockTimerEntry": "输入计时器（不启动）",
    "SystemBrightnessMax": "屏幕亮度调到最大",
    "CameraTakePhoto": "拍一张照片",
}
STATUS_LABELS = {
    "passed": "成功", "failed": "未完成目标", "not_run": "未运行",
    "agent_output_error_unscored": "模型格式错误／未评分",
    "infrastructure_error": "运行故障／未完成", "cleanup_error": "清理未确认",
    "profile_mismatch": "运行配置不一致",
}
BUCKETS = [
    ("model_seconds", "模型调用", "model_seconds"),
    ("control_seconds", "Control 操作", "control_seconds"),
    ("observation_seconds", "截图与观察", "observation_seconds"),
    ("host_coordination_wait_seconds", "等待主机协调", "host_coordination_wait_seconds"),
    ("other_seconds", "其余程序处理", "program_other_seconds"),
]
HOST_STAGES = [
    ("preflight", "只读预检"),
    ("environment_setup", "环境准备（总项）"),
    ("environment_connection", "  其中：连接模拟器"),
    ("environment_configuration", "  其中：配置官方环境"),
    ("app_setup", "  其中：准备目标 App"),
    ("task_initialization", "官方任务初始化"),
    ("agent_episode", "Agent 全轮（包含 Android 运行）"),
    ("official_evaluation", "官方判分"),
    ("official_evidence", "保存官方截图与界面证据"),
    ("agent_cleanup_and_evidence_pull", "Agent 收尾与取回证据"),
    ("final_cleanup", "最终环境清理"),
    ("task_wall_through_official_score", "任务初始化 → 官方判分结束（区间项）"),
    ("host_run_excluding_key_wait", "本任务完整流程（排除密钥等待）"),
    ("key_input_wait", "本任务密钥等待（单独列出）"),
]


def obj(value):
    return value if isinstance(value, dict) else {}


def number(value):
    return value if type(value) in (int, float) and math.isfinite(value) and value >= 0 else None


def fmt(value, digits=3):
    value = number(value)
    return "—" if value is None else f"{value:.{digits}f}"


def count(value):
    return "—" if number(value) is None else str(value)


def read_json(path: Path):
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected JSON object: {path.name}")
    return value


@dataclass(frozen=True)
class Link:
    label: str
    path: Path


class Report:
    def __init__(self, output: Path):
        self.output = output
        self.html = []
        self.text = []

    def href(self, path: Path):
        return quote(os.path.relpath(path, self.output).replace(os.sep, "/"), safe="/.-_")

    def cell(self, value, rich=True):
        if value is None:
            value = "—"
        if isinstance(value, Link):
            if rich:
                return f'<a href="{html.escape(self.href(value.path), quote=True)}">{html.escape(value.label)}</a>'
            return f"{value.label}: {value.path}"
        return html.escape(str(value)) if rich else str(value).replace("\n", " ")

    def heading(self, title, level=2):
        self.html.append(f"<h{level}>{html.escape(title)}</h{level}>")
        self.text.extend(["", title, ""])

    def paragraph(self, text, notice=False):
        self.html.append(f'<p class="{"notice" if notice else ""}">{html.escape(text)}</p>')
        self.text.extend([text, ""])

    def table(self, headers, rows):
        self.html.append('<div class="table-wrap"><table><thead><tr>' + "".join(f"<th>{self.cell(x)}</th>" for x in headers) + "</tr></thead><tbody>")
        self.text.append(" | ".join(str(x) for x in headers))
        for row in rows:
            self.html.append("<tr>" + "".join(f"<td>{self.cell(x)}</td>" for x in row) + "</tr>")
            self.text.append(" | ".join(self.cell(x, False) for x in row))
        self.html.append("</tbody></table></div>")
        self.text.append("")

    def image(self, label, path):
        href = html.escape(self.href(path), quote=True)
        self.html.append(f'<figure><a href="{href}"><img loading="lazy" src="{href}" alt="{html.escape(label, quote=True)}"></a><figcaption>{html.escape(label)}（点击查看原图）</figcaption></figure>')
        self.text.extend([f"{label}: {path}", ""])

    def save(self):
        self.output.mkdir(parents=True, exist_ok=False)
        css = """
        :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#eef2f6;color:#243447;font:16px/1.65 'Segoe UI','Microsoft YaHei',sans-serif}
        main{max-width:1360px;margin:32px auto;padding:36px;background:white;border-top:7px solid #236188;border-radius:7px}
        h1{font-size:30px;line-height:1.3;color:#173b55;margin:0 0 20px}h2{font-size:23px;color:#173b55;border-bottom:1px solid #d5e0e8;padding-bottom:8px;margin-top:38px}h3{font-size:19px;margin-top:28px}
        p{max-width:1150px}.notice{padding:12px 16px;background:#fff5e7;border-left:4px solid #d19a3a}.table-wrap{overflow:auto;margin:14px 0 26px}table{border-collapse:collapse;width:100%;font-size:14px}th{text-align:left;background:#e8f0f6;white-space:nowrap}td,th{padding:9px 11px;border-bottom:1px solid #dce4eb;vertical-align:top}td{font-variant-numeric:tabular-nums}tr:nth-child(even) td{background:#f8fafc}a{color:#116c98;overflow-wrap:anywhere}figure{display:inline-block;margin:12px 20px 16px 0;vertical-align:top}img{max-height:390px;max-width:100%;border:1px solid #c9d8e4}figcaption{font-size:13px;color:#526575}footer{margin-top:40px;color:#617387;font-size:13px}
        @media print{body{background:white}main{margin:0;padding:12px;border:0}table{font-size:10px}.table-wrap{overflow:visible}tr,figure{break-inside:avoid}h2,h3{break-after:avoid}a{color:inherit}img{max-height:260px}}
        """
        page = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>AndroidWorld 标准安卓基线实验报告</title><style>' + css + '</style></head><body><main>' + "\n".join(self.html) + '<footer>离线报告；没有外部字体、脚本或网络资源。截图链接依赖本工作区证据目录。</footer></main></body></html>'
        (self.output / "report.html").write_text(page, encoding="utf-8")
        (self.output / "report.txt").write_text("\n".join(self.text).strip() + "\n", encoding="utf-8")


def load_batch(summary_path):
    path = Path(summary_path).resolve(strict=True)
    summary = read_json(path)
    if summary.get("status") not in FINAL_STATES:
        raise ValueError("批次尚未结束；running/preflight 不能生成最终报告")
    if (summary.get("profile") != "stock_android_mcp_baseline_v1"
            or summary.get("requested_control_transport") != "stdio_jsonrpc"
            or summary.get("formal_user_grants") is not False):
        raise ValueError("本生成器仅用于无正式用户许可的 stock stdio MCP 基线批次")
    if path.parent.name != summary.get("batch_id"):
        raise ValueError("批次目录与 batch_id 不匹配")
    planned, rows = summary.get("planned_tasks"), summary.get("rows")
    if not isinstance(planned, list) or not isinstance(rows, list) or len(planned) != summary.get("planned_count"):
        raise ValueError("任务计划或结果列表不完整")
    if len(set(planned)) != len(planned) or len(rows) != len(planned) or {x.get("task_name") for x in rows} != set(planned):
        raise ValueError("最终结果必须逐项列出每个计划任务，包括未运行项")
    records, sources = [], [path]
    for row in rows:
        task = row["task_name"]
        run_id = row.get("run_id")
        data = {"row": row, "phone": {}, "steps": [], "directory": None, "warnings": [],
                "batch_id": summary["batch_id"], "summary_path": path}
        if row.get("profile", summary.get("profile")) != summary.get("profile"):
            raise ValueError(f"任务 profile 与本批次不匹配: {task}")
        if run_id is not None:
            if not isinstance(run_id, str) or not re.fullmatch(r"aw-[a-z0-9_-]{1,100}", run_id):
                raise ValueError("非法 run_id")
            directory = path.parent / "tasks" / run_id
            # Resolve from this batch, never follow an arbitrary output path in a row.
            if not directory.resolve().is_relative_to(path.parent.resolve()):
                raise ValueError("任务证据路径越出本批次")
            data["directory"] = directory
            for filename, key in (("phone-result.json", "phone"), ("phone/step-timing.json", "step_document")):
                source = directory / filename
                if not source.is_file():
                    data["warnings"].append(f"缺少 {filename}，相关缺值保留为 —")
                    continue
                if not source.resolve().is_relative_to(path.parent):
                    raise ValueError("证据链接指向其他批次")
                value = read_json(source)
                for field, expected in (("run_id", run_id), ("task_name", task), ("profile", summary.get("profile"))):
                    if value.get(field) != expected:
                        raise ValueError(f"{filename} 的 {field} 不属于本任务")
                data[key] = value
                sources.append(source)
            data["steps"] = data.get("step_document", {}).get("steps", [])
            if not isinstance(data["steps"], list):
                raise ValueError("步骤计时必须是列表")
            for step in data["steps"]:
                if not isinstance(step, dict) or step.get("run_id") != run_id or step.get("task_name") != task:
                    raise ValueError("步骤计时混入其他任务")
        elif row.get("status") != "not_run":
            data["warnings"].append("没有 run_id，无法定位手机回执；仅显示本批 summary 已记录的字段")
        data["timing"] = obj(data["phone"].get("timing")) or obj(row.get("android_timing_seconds"))
        data["host"] = obj(row.get("host_timing_seconds"))
        records.append(data)
    return path, summary, records, sources


def bucket(data, key, row_key):
    return data["timing"].get(key, data["row"].get(row_key))


def display_status(data):
    row = data["row"]
    # Old evidence is immutable. Explain its historical summary-classifier bug
    # in the presentation, retaining both raw status fields in the appendix.
    if (row.get("status") == "profile_mismatch" and row.get("source_status") == "infrastructure_error"
            and not data["phone"] and row.get("model_called") is False
            and row.get("observed_control_transport") is None):
        return "环境故障（旧汇总误标配置不符）"
    return STATUS_LABELS.get(row.get("status"), row.get("status", "—"))


def verify_continuation(first, second, mapped_tasks):
    first_path, a, first_records, _ = first
    second_path, b, second_records, _ = second
    if first_path == second_path:
        raise ValueError("补跑批次必须与首批不同")
    if not mapped_tasks or len(set(mapped_tasks)) != len(mapped_tasks):
        raise ValueError("必须显式列出不重复的补跑任务映射")
    if set(b["planned_tasks"]) != set(mapped_tasks) or not set(mapped_tasks).issubset(a["planned_tasks"]):
        raise ValueError("补跑计划必须恰好对应显式映射的原计划任务")
    for key in ("profile", "model", "effort", "suite_seed", "upstream_commit", "requested_control_transport"):
        if a.get(key) is None or a.get(key) != b.get(key):
            raise ValueError(f"补跑批次的 {key} 与首批不一致")
    originals = {x["row"]["task_name"]: x for x in first_records}
    replacements = {x["row"]["task_name"]: x for x in second_records}
    for task in mapped_tasks:
        original, replacement = originals[task]["row"], replacements[task]["row"]
        if original.get("status") == "passed":
            raise ValueError("不能挑选补跑结果替换已成功的原任务")
        for field in ("params", "target_package", "max_steps"):
            if original.get(field) is not None and original.get(field) != replacement.get(field):
                raise ValueError(f"补跑任务的 {field} 改变: {task}")
    preflight_paths = [first_path.parent / "preflight.json", second_path.parent / "preflight.json"]
    preflights = [read_json(x) for x in preflight_paths]
    for value in preflights:
        if value.get("profile") != a["profile"] or value.get("upstream_commit") != a["upstream_commit"]:
            raise ValueError("预检证据与批次 profile/官方版本不一致")
    for field in ("jar_sha256", "apk_sha256"):
        values = [obj(x.get("ondevice_artifacts")).get(field) for x in preflights]
        if not isinstance(values[0], str) or not re.fullmatch(r"[a-f0-9]{64}", values[0]) or values[0] != values[1]:
            raise ValueError(f"两批已安装的 {field} 缺失或不一致")
    if not preflights[0].get("official_source_sha256") or preflights[0].get("official_source_sha256") != preflights[1].get("official_source_sha256"):
        raise ValueError("两批官方评分源码哈希缺失或不一致")
    selected = [replacements[name] if name in mapped_tasks else originals[name] for name in a["planned_tasks"]]
    return selected, preflight_paths, preflights[0]["ondevice_artifacts"]


def build_report(summary_path, output_dir=None, *, continuation_summary=None, use_continuation_tasks=None, historical_summary=None):
    path, summary, records, sources = load_batch(summary_path)
    batches = [(path, summary, records, sources)]
    evidence_roots = [path.parent]
    history = None
    compatibility = None
    if continuation_summary is not None:
        supplement = load_batch(continuation_summary)
        records, preflight_paths, compatibility = verify_continuation(batches[0], supplement, use_continuation_tasks)
        batches.append(supplement)
        sources = [*sources, *supplement[3], *preflight_paths]
        evidence_roots.append(supplement[0].parent)
    elif use_continuation_tasks:
        raise ValueError("补跑任务映射需要 --continuation-summary")
    if historical_summary is not None:
        history = load_batch(historical_summary)
        if history[0] in [batch[0] for batch in batches]:
            raise ValueError("历史诊断批次不能也是本报告采用的批次")
        sources = [*sources, *history[3]]
        evidence_roots.append(history[0].parent)
    default_name = summary["batch_id"] + ("-with-continuation" if len(batches) > 1 else "")
    output = Path(output_dir).resolve() if output_dir else Path(__file__).resolve().parent / "generated" / default_name
    if output.exists():
        raise FileExistsError("报告输出目录已存在；请选择新目录，避免覆盖旧报告")
    if any(output.is_relative_to(root) for root in evidence_roots):
        raise ValueError("报告应放在证据目录之外，不能修改批次内容")
    report = Report(output)
    report.heading("AndroidWorld 标准安卓基线实验报告", 1)
    success = sum(x["row"].get("status") == "passed" and x["row"].get("success") is True for x in records)
    scored = sum(x["row"].get("official_evaluation_completed") is True for x in records)
    attempted = sum(x["row"].get("status") != "not_run" for x in records)
    if len(batches) == 1:
        report.paragraph(f"本批计划 {len(records)} 项，已尝试 {attempted} 项，官方已评分 {scored} 项，成功 {success} 项。批次状态：{summary['status']}。")
    else:
        all_attempts = sum(record["row"].get("status") != "not_run" for batch in batches for record in batch[2])
        report.paragraph(f"任务覆盖汇总：显式选取 {len(records)} 个任务记录，其中已尝试 {attempted} 项、官方已评分 {scored} 项、成功 {success} 项。两批实际共有 {all_attempts} 次尝试，包含后文保留的失败尝试。")
        report.paragraph("首批前两项完成后在环境准备阶段中断；剩余任务另建批次补跑。下表是相同 Agent／Control 二进制与模型配置下的任务覆盖结果，不是一次连续四任务运行，也不隐藏首批失败。", True)
    report.paragraph(f"批次：{summary['batch_id']}；模型：{summary.get('model', '—')}；推理强度：{summary.get('effort', '—')}；任务种子：{summary.get('suite_seed', '—')}。")
    report.paragraph(f"执行配置：{summary.get('profile', '—')}。Android 中的 Agent 通过独立 Control 进程的 stdio MCP 操作官方 App；主机负责官方任务初始化、判分与证据收集。")
    report.paragraph("这是少量不同任务的小样本结果，不是同一任务的重复实验，也不是完整 AndroidWorld 成功率。本报告不把不同任务的时间混成平均值。", True)
    report.paragraph("所选运行是标准 Android 基线，没有逐任务正式用户授权；不能把它当作 MobiClaw 系统后端或正式授权流程的验收。")

    report.heading("1. 成功情况与任务总时间")
    rows = []
    for data in records:
        row = data["row"]
        rows.append([TASK_LABELS.get(row["task_name"], row["task_name"]), display_status(data),
                     count(row.get("official_raw_score")), count(row.get("agent_score")), count(row.get("model_calls")), count(row.get("actions")),
                     fmt(row.get("full_run_excluding_key_wait_seconds")), fmt(bucket(data, "total_wall_seconds", "android_agent_total_seconds"))])
    report.table(["任务", "结果", "官方原始分", "Agent 得分", "模型调用次数", "执行动作数", "完整流程 / 秒", "Android Agent / 秒"], rows)
    report.table(["选用任务", "来源批次", "run_id", "原始汇总"], [[TASK_LABELS.get(data["row"]["task_name"], data["row"]["task_name"]), data["batch_id"], data["row"].get("run_id", "—"), Link("summary.json", data["summary_path"])] for data in records])
    report.paragraph("完整流程：本任务的预检、环境准备、执行、官方判分、证据取回和清理，排除输入密钥等待。Android Agent：设备内 Agent 从启动到关闭的时间，已包含在完整流程中，不能再相加。")
    report.paragraph("官方原始分检查实际 App／系统状态；Agent 得分还要求模型自己结束任务。达到步数上限但模型未结束时，Agent 得分可能为 0。未评分、未运行及缺失数据统一显示 —，不会补成 0。")
    for _, batch_summary, _, _ in batches:
        report.paragraph(f"批次 {batch_summary['batch_id']}：墙钟时间（含预检、任务间开销，排除密钥等待）{fmt(batch_summary.get('wall_seconds_excluding_key_wait'))} 秒；密钥输入等待 {fmt(batch_summary.get('key_input_wait_seconds'))} 秒；状态 {batch_summary['status']}。")
    report.paragraph("各批次墙钟时间单独列出，不伪造连续四任务总时间，也不把它当作单项平均值。")

    report.heading("2. Android Agent 的五个互斥时间桶")
    bucket_rows = []
    for data in records:
        values = [bucket(data, key, row_key) for key, _, row_key in BUCKETS]
        total = number(bucket(data, "total_wall_seconds", "android_agent_total_seconds"))
        measured = [number(x) for x in values]
        difference = total - sum(measured) if total is not None and all(x is not None for x in measured) else None
        check = "—" if difference is None else "一致" if abs(difference) < 0.001 else f"差 {difference:.6f} 秒，需核对"
        bucket_rows.append([TASK_LABELS.get(data["row"]["task_name"], data["row"]["task_name"]), *[fmt(x) for x in values], fmt(total), check])
    report.table(["任务", *[label + " / 秒" for _, label, _ in BUCKETS], "Agent 总计 / 秒", "总量校验"], bucket_rows)
    report.paragraph("模型调用 = 向独立模型进程传递请求、网络往返、等待模型推理和接收结果；当前不能把服务器纯推理时间单独拆出来。Control 操作 = 通过 MCP 请求并执行点击、滑动等动作。截图与观察 = 获取并保存操作前后画面。等待主机协调 = 等待下一步命令及官方评分反馈。其余程序处理 = 初始化、请求解析、日志和收尾等剩余墙钟时间，不等于纯 CPU 计算时间。")
    report.paragraph("只有上表这五个时间桶可相加得到 Android Agent 总时间。下方主机区间、Control 子进程内部时间、逐步开始到下一步区间均可能包含上表时间，不能重复相加。", True)

    report.heading("3. 主机各阶段明细")
    labels = [TASK_LABELS.get(x["row"]["task_name"], x["row"]["task_name"]) for x in records]
    report.table(["阶段（秒）", *labels], [[label, *[fmt(x["host"].get(key)) for x in records]] for key, label in HOST_STAGES])
    report.paragraph("“环境准备”包含其下三个“其中”子项；“Agent 全轮”包含 Android Agent 时间；“任务初始化 → 判分结束”是多个阶段组成的区间。表中总项和子项不能一起求和。")
    child_rows = []
    for data in records:
        control = obj(data["phone"].get("control"))
        timing = obj(obj(control.get("server")).get("timing")) or obj(data["row"].get("control_service_timing_seconds"))
        child_rows.append([TASK_LABELS.get(data["row"]["task_name"], data["row"]["task_name"]), fmt(timing.get("server_wall_seconds")), fmt(timing.get("execution_seconds")), fmt(timing.get("observation_seconds"))])
    report.table(["Control 子进程内部（秒）", "服务存活区间", "动作执行子集", "观察子集"], child_rows)
    report.paragraph("Control 内部计时用于定位开销，不是额外耗时；动作和观察子集已经计入 Agent 对应时间桶。服务存活区间还包含等待请求。")

    report.heading("4. 每一步从开始到下一步的时间与截图")
    action_names = {"open_app": "打开目标 App", "click": "点击", "swipe": "滑动", "go_back": "返回", "wait": "等待", "take_screenshot": "请求截图"}
    for data in records:
        row, directory = data["row"], data["directory"]
        report.heading(TASK_LABELS.get(row["task_name"], row["task_name"]) + " · " + row["task_name"], 3)
        report.paragraph(f"run_id：{row.get('run_id', '—')}；结束原因：{data['phone'].get('stop_reason', row.get('phone_stop_reason', '—'))}。")
        for warning in data["warnings"]:
            report.paragraph(warning, True)
        step_rows = []
        for step in data["steps"]:
            action = obj(step.get("action")).get("name")
            operation = action_names.get(action, action or ("模型结束" if step.get("kind") == "finish" else step.get("kind", "—")))
            boundary = {"next_agent_step_start": "到下一步开始", "agent_end_including_scorer_coordination": "到 Agent 结束（含判分协调）"}.get(step.get("interval_end"), step.get("interval_end", "—"))
            screenshot = step.get("observation_file")
            link = "—"
            if directory and isinstance(screenshot, str) and re.fullmatch(r"screen-[0-9]+\.png", screenshot):
                image = directory / "phone" / screenshot
                if image.is_file():
                    link = Link("步骤截图", image)
            step_rows.append([count(step.get("turn")), operation, fmt(step.get("start_to_next_seconds")), fmt(step.get("execution_wall_seconds")), fmt(step.get("model_seconds")), fmt(step.get("control_seconds")), fmt(step.get("observation_seconds")), boundary, link])
        report.table(["步骤", "动作／决定", "开始到下一步 / 秒", "本步执行 / 秒", "模型 / 秒", "Control / 秒", "观察 / 秒", "区间终点", "截图"], step_rows or [["—"] * 9])
        report.paragraph("“本步执行”是这一轮决策和操作的时间；“开始到下一步”还包含两轮之间的主机握手等待。最后一步的区间终点为 Agent 结束，包含官方评分协调；步骤区间不覆盖 Agent 开始前和首步前的所有准备时间。")
        if directory:
            final_image = directory / "official-final-screen.png"
            if final_image.is_file():
                report.image("官方评分时保留的界面", final_image)
            evidence_links = []
            for filename, title in (("phone-result.json", "Android 最终回执"), ("phone/step-timing.json", "逐步计时原始证据")):
                evidence = directory / filename
                if evidence.is_file():
                    evidence_links.append([title, Link(filename, evidence)])
            if evidence_links:
                report.table(["证据", "文件"], evidence_links)

    report.heading("5. 失败尝试与历史中断记录（独立附录）")
    failures = [data for batch in batches for data in batch[2] if data["row"].get("status") != "passed"]
    if history:
        failures.extend(data for data in history[2] if data["row"].get("status") != "passed")
    failure_rows = []
    for data in failures:
        row = data["row"]
        failure_rows.append([data["batch_id"], row["task_name"], row.get("run_id", "—"), display_status(data),
            row.get("status", "—"), row.get("source_status", "—"), row.get("error_type") or row.get("phone_stop_reason") or row.get("reason", "—"),
            "未调用" if row.get("model_called") is False else "已调用" if row.get("model_called") is True else "—",
            fmt(row.get("full_run_excluding_key_wait_seconds")), Link("原始汇总", data["summary_path"])])
    report.table(["来源批次", "任务", "run_id", "解释", "原 summary 状态", "原 host 状态", "错误／原因", "模型", "完整流程 / 秒", "证据"], failure_rows or [["本报告指定批次没有失败或未运行记录", *["—"] * 9]])
    report.paragraph("原始记录不改写。没有启动 Agent、没有手机回执的环境异常，历史汇总曾误标为 profile_mismatch；本附录同时保留原 summary 状态与原 host 状态，说明它不是模型失败或模型配置被替换。未运行项不是一次失败尝试。")
    if history:
        report.paragraph("显式提供的历史批次仅用于展示失败记录，不补入本次任务成绩，也不参与本次时间统计。")
    report.heading("6. 数据来源与可复核性")
    if compatibility:
        report.paragraph("补跑兼容性检查通过：两批 profile、模型、推理强度、suite seed、官方 commit、已安装 JAR/APK 哈希、官方评分源码哈希均相同。主机汇总分类修复不改变手机 Agent／Control 或评分逻辑。")
        report.table(["已核对的设备产物", "两批一致的 SHA256"], list(compatibility.items()))
    report.paragraph(f"生成时间：{datetime.now(timezone.utc).isoformat(timespec='seconds')}；官方源码版本：{summary.get('upstream_commit', '—')}。原始数据没有被修改。")
    def label(source):
        for root in evidence_roots:
            if source.is_relative_to(root):
                return root.name + "/" + str(source.relative_to(root))
        return str(source)
    report.table(["源文件（仅显式输入批次）", "SHA256"], [[Link(label(source), source), hashlib.sha256(source.read_bytes()).hexdigest()] for source in dict.fromkeys(sources)])
    report.save()
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("summary", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--continuation-summary", type=Path)
    parser.add_argument("--use-continuation-tasks", nargs="+")
    parser.add_argument("--historical-summary", type=Path)
    args = parser.parse_args()
    try:
        output = build_report(args.summary, args.output_dir, continuation_summary=args.continuation_summary,
            use_continuation_tasks=args.use_continuation_tasks, historical_summary=args.historical_summary)
    except (ValueError, OSError, TypeError, KeyError) as error:
        parser.exit(2, f"报告未生成：{error}\n")
    print(json.dumps({"html": str(output / "report.html"), "text": str(output / "report.txt")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
