"""Run PipeTown's production level-generator audit on a connected Android device.

This keeps validation tied to the Java implementation that players use. The app
logs a compact audit result after independently regenerating and checking each
requested level.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path


PACKAGE = "com.pipetown.game"
ACTIVITY = f"{PACKAGE}/.MainActivity"
TAG = "PipeTownGeneratorAudit"
SUMMARY_RE = re.compile(
    r"levels=(?P<levels>\d+) ms=(?P<ms>\d+) "
    r"invalid=(?P<invalid>\d+) nondeterministic=(?P<nondeterministic>\d+) "
    r"duplicates=(?P<duplicates>\d+) geometryDuplicates=(?P<geometry_duplicates>\d+) "
    r"belowProfile=(?P<below_profile>\d+) "
    r"fallback=(?P<fallback>\d+)"
)


def default_adb() -> str:
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk_root:
        candidate = Path(sdk_root) / "platform-tools" / "adb.exe"
        if candidate.exists():
            return str(candidate)
    local_app_data = os.environ.get("LOCALAPPDATA", "")
    candidate = Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    return str(candidate) if candidate.exists() else "adb"


def run(args: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=check, text=True, capture_output=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--levels", type=int, default=120, help="number of levels to validate")
    parser.add_argument("--timeout", type=float, default=180.0, help="maximum seconds to wait for a result")
    parser.add_argument("--adb", default=default_adb(), help="path to adb")
    parser.add_argument("--apk", type=Path, help="APK to install before the audit")
    options = parser.parse_args()

    if options.levels < 1:
        parser.error("--levels must be positive")
    if options.apk:
        install = run([options.adb, "install", "-r", str(options.apk)], check=False)
        if install.returncode != 0:
            sys.stderr.write(install.stdout + install.stderr)
            return install.returncode

    devices = run([options.adb, "devices"], check=False)
    if "\tdevice" not in devices.stdout:
        sys.stderr.write("No online Android device found.\n" + devices.stdout + devices.stderr)
        return 2

    run([options.adb, "logcat", "-c"])
    run([options.adb, "shell", "am", "force-stop", PACKAGE])
    launch = run(
        [
            options.adb,
            "shell",
            "am",
            "start",
            "-n",
            ACTIVITY,
            "--ez",
            "generator_audit",
            "true",
            "--ei",
            "audit_levels",
            str(options.levels),
        ],
        check=False,
    )
    if launch.returncode != 0:
        sys.stderr.write(launch.stdout + launch.stderr)
        return launch.returncode

    deadline = time.monotonic() + options.timeout
    while time.monotonic() < deadline:
        logs = run([options.adb, "logcat", "-d", "-s", f"{TAG}:I", "*:S"], check=False)
        match = SUMMARY_RE.search(logs.stdout)
        if match:
            values = {name: int(value) for name, value in match.groupdict().items()}
            result_line = next((line for line in logs.stdout.splitlines() if TAG in line and "levels=" in line), match.group(0))
            print(result_line[result_line.find("levels=") :])
            failures = [
                name
                for name in ("invalid", "nondeterministic", "duplicates", "geometry_duplicates", "below_profile", "fallback")
                if values[name] != 0
            ]
            if failures:
                sys.stderr.write("Generator audit failed: " + ", ".join(failures) + "\n")
                return 1
            return 0
        time.sleep(1.0)

    sys.stderr.write(f"Timed out waiting {options.timeout:.0f}s for {TAG} result.\n")
    return 3


if __name__ == "__main__":
    raise SystemExit(main())
