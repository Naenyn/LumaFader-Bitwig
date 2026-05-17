#!/usr/bin/env python3
"""
Deploy src/ to a LumaFader running CircuitPython.

Normal use: plug in USB (do NOT hold the Pico BOOT button). Files copy over the
USB serial / REPL connection via mpremote — no /Volumes mount required.

Pico BOOT + plug USB = RP2040 UF2 bootloader (RPI-RP2) only. That accepts .uf2
images, not individual .py files. Use that path only for installing CircuitPython.
"""

import os
import shutil
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO_ROOT, "src")
VOLUME_NAMES = ("LUMAFADER", "CIRCUITPY", "LUMA")


def have_mpremote():
    return shutil.which("mpremote") is not None


def install_hint():
    print("Install mpremote:  python3 -m pip install mpremote")
    print("Then retry:        python3 scripts/deploy.py")


def _mpremote_port():
    """Prefer explicit LumaFader / CircuitPython serial port over auto."""
    try:
        out = subprocess.check_output(
            ["mpremote", "connect", "list"],
            text=True,
            stderr=subprocess.STDOUT,
        )
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "auto"
    for line in out.splitlines():
        if "usbmodem" in line and ("LumaFader" in line or "CircuitPython" in line or "DJBB" in line):
            parts = line.split()
            if parts:
                return parts[0]
    return "auto"


def deploy_mpremote():
    port = _mpremote_port()
    files = []
    for root, _dirs, filenames in os.walk(SRC):
        for name in filenames:
            local = os.path.join(root, name)
            rel = os.path.relpath(local, SRC).replace(os.sep, "/")
            files.append((local, rel))

    print(f"Deploying {len(files)} files via mpremote @ {port} ...")
    for local, rel in files:
        print(f"  {rel}")
        remote = ":" + rel
        cp = subprocess.run(
            ["mpremote", "connect", port, "cp", local, remote],
            capture_output=True,
            text=True,
        )
        if cp.returncode != 0:
            data = open(local, encoding="utf-8").read()
            script = f"open('/{rel}','w').write({data!r})\n"
            wr = subprocess.run(
                ["mpremote", "connect", port, "exec", script],
                capture_output=True,
                text=True,
            )
            if wr.returncode != 0:
                print(wr.stderr or wr.stdout)
                raise subprocess.CalledProcessError(wr.returncode, wr.args)

    print("Soft reboot...")
    subprocess.run(["mpremote", "connect", port, "reset"], check=True)
    return True


def find_volume():
    for name in VOLUME_NAMES:
        path = os.path.join("/Volumes", name)
        if os.path.isdir(path):
            return path
    return None


def deploy_volume(target):
    print(f"Copying to {target} ...")
    count = 0
    for root, _dirs, filenames in os.walk(SRC):
        for name in filenames:
            local = os.path.join(root, name)
            rel = os.path.relpath(local, SRC)
            dst = os.path.join(target, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(local, dst)
            count += 1
            print(f"  {rel}")
    print(f"Done ({count} files). Unplug/replug or press reset on the device.")
    return True


def main():
    print("=" * 60)
    print("LumaFader Bitwig — deploy")
    print()
    print("  Pico BOOT held + USB  →  RPI-RP2 volume  →  .uf2 flash ONLY")
    print("  Normal plug-in (no BOOT)  →  mpremote copies .py over USB")
    print("=" * 60)
    print()

    vol = find_volume()
    if vol and os.path.basename(vol) == "RPI-RP2":
        print("RPI-RP2 is mounted (Pico BOOT loader).")
        print("This tool cannot copy .py files there.")
        print("  • Flash CircuitPython: drag uf2/*.uf2 onto RPI-RP2")
        print("  • Then release BOOT, replug normally, run this script again")
        sys.exit(1)

    if vol:
        print(f"Found {vol} — using drag-and-drop copy.")
        deploy_volume(vol)
        return

    if not have_mpremote():
        install_hint()
        sys.exit(1)

    try:
        deploy_mpremote()
    except subprocess.CalledProcessError as e:
        print(f"\nmpremote failed: {e}")
        if not have_mpremote():
            install_hint()
        else:
            print("Is the device plugged in WITHOUT holding Pico BOOT?")
            print("Check System Settings → Privacy → USB / serial access if needed.")
        sys.exit(1)
    except FileNotFoundError:
        install_hint()
        sys.exit(1)


if __name__ == "__main__":
    main()
