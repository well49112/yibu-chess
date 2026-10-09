#!/usr/bin/env python3
"""Package a signed release and explicitly selected test evidence, source and separate key."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import shutil
import zipfile
import xml.etree.ElementTree as ET
import argparse

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--test-report", action="append", required=True,
                    help="JUnit XML report from this release's focused checks; repeat for each report")
args = parser.parse_args()
version = re.search(r'versionName\s*=\s*"([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
out = root / "artifacts"
out.mkdir(exist_ok=True)
apk = root / "app/build/outputs/apk/release/app-release.apk"
assert apk.is_file(), "Build the release APK first"
excluded = {".git", ".gradle", ".kotlin", ".cxx", "build", "artifacts", "signing", "__pycache__"}
sources = [p for p in root.rglob("*") if p.is_file() and not any(part in excluded for part in p.relative_to(root).parts)
           and p.name != "local.properties" and not p.name.endswith(".log")]
code = [p for p in sources if (p.suffix in {".kt", ".java", ".cpp", ".h", ".xml"} or p.name.endswith(".gradle.kts"))
        and "src/test" not in str(p.relative_to(root)) and "src/androidTest" not in str(p.relative_to(root))
        and "tools" not in p.relative_to(root).parts]
assert all(p.stat().st_mtime <= apk.stat().st_mtime for p in code), "Rebuild APK after the most recent source change"
shutil.copy2(apk, out / f"yibu-{version}-arm64.apk")
with zipfile.ZipFile(out / f"yibu-{version}-source.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for p in sorted(sources): archive.write(p, "yibu-chess/" + str(p.relative_to(root)))
with zipfile.ZipFile(out / "yibu-personal-test-signing.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    archive.write(root / "signing/personal.jks", "signing/personal.jks")
    archive.writestr("README.txt", "个人测试签名备份\n后续构建恢复到项目 signing/personal.jks。\nalias: yibu\nstore/key password: yibu-personal-test\n保持同一签名才能覆盖安装；提高 versionCode。\n此文件不放入 Git 仓库。\n")
files = [out / f"yibu-{version}-arm64.apk", out / f"yibu-{version}-source.zip", out / "yibu-personal-test-signing.zip"]
def test_status(path):
    assert path.is_file(), f"Missing selected test report: {path}"
    suite = ET.parse(path).getroot()
    assert int(suite.get("failures", 0)) + int(suite.get("errors", 0)) == 0, f"Tests failed: {path}"
    assert int(suite.get("skipped", 0)) == 0, f"Tests skipped: {path}"
    assert int(suite.get("tests", 0)) > 0, f"No selected tests ran: {path}"
    return {"passed": int(suite.get("tests")),
            "cases": [case.get("name") for case in suite.findall("testcase")]}
selected_reports = [root / p for p in args.test_report]
validation = {
    "scope": "only changed features and directly affected behavior",
    "selected_test_suites": {str(p.relative_to(root)): test_status(p) for p in selected_reports},
    "full_regression": "not run", "full_android_lint": "not run",
    "release_apk": "assembled with existing personal signing configuration",
    "upload_verification": "not performed",
    "xiaomi_17_pro": "not run on a physical device in Cloud"
}
metadata = {
    "built_at": datetime.now(timezone.utc).isoformat(),
    "version": version, "application_id": "cn.yibu.chess", "abi": "arm64-v8a",
    "onnx_runtime_android": re.search(r'val onnxRuntimeVersion = "([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1),
    "min_sdk": 26, "target_sdk": 35, "engine": "Stockfish 19 (remote)",
    "remote_stockfish": {"base_url": "https://chess.jeefy.top", "auth_header": "X-Access-Token",
                         "token_in_apk": False, "local_stockfish": False,
                         "live_service_verification": "not repeated for this release"},
    "chess_com_import": {"public_api": "https://api.chess.com/pub/player/{username}/games/archives",
                         "password_required": False, "remember_username": True, "batch_limits": [30, 100, None],
                         "incremental_month_save": True, "rating_effect": "none"},
    "opening_courses": {"total": 10, "white": 5, "black": 5, "routes": 20, "offline": True,
                        "manual_playback": True, "training_rating_effect": "none"},
    "automatic_review": {"profile": "lightning", "all_saved_games": True, "both_colors": True,
                         "incremental_save": True, "resume_from_missing_steps": True,
                         "background_service": "dataSync foreground service",
                         "notification_permission_required_to_start": False,
                         "android_15_background_limit": "6 hours per 24 hours",
                         "lessons": "on demand"},
    "human_model": json.loads((root / "app/src/main/assets/models/maia3-metadata.json").read_text()),
    "certificate_sha256": "ac84a14d5fe6aa75a8550e375c24221048c9abc9b85d64fe02e1e4e58be1df03",
    "files": {p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in files},
    "validation": validation
}
(out / "build-manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
(out / "SHA256SUMS.txt").write_text("".join(f"{metadata['files'][p.name]['sha256']}  {p.name}\n" for p in files))
for p in files: print(f"{p.name}: {p.stat().st_size / 1024 / 1024:.1f} MiB")
