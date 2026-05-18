import os
import shutil
import time

# ------ USER SETTINGS ------

NUKE = False

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NUKE_FP = os.path.join(REPO_ROOT, "firmware", "uf2", "flash_nuke.uf2")
UF2_FP = os.path.join(
    REPO_ROOT,
    "firmware",
    "uf2",
    "adafruit-circuitpython-raspberry_pi_pico-en_US-9.2.8.uf2",
)
SRC_FOLDER_FP = os.path.join(REPO_ROOT, "firmware", "src")
# ---------------------------

RPI_INIT_FP = "/Volumes/RPI-RP2"
RPI_CIRCUITPYTHON_PATHS = ["/Volumes/CIRCUITPY", "/Volumes/LUMAFADER", "/Volumes/LUMA"]
TIMEOUT_THRESHOLD = 80


def flash_uf2():
    time_prev = time.monotonic()

    if NUKE:
        try:
            shutil.copy(NUKE_FP, RPI_INIT_FP)
        except Exception as e:
            print(f"no folder named RPI-RP2 found {e}")
            return False
        print("Nuking...")

    ready_for_copy = False
    print("Waiting for RPI-RP2 to mount...")
    while not ready_for_copy:
        try:
            shutil.copy(UF2_FP, RPI_INIT_FP)
            ready_for_copy = True
            print("copied uf2 to RPI-RP2")
            time_prev = time.monotonic()
        except Exception:
            print("Retrying in 2s...")
            time.sleep(2)

        if time.monotonic() - time_prev > TIMEOUT_THRESHOLD:
            print("Timeout")
            return False

    time.sleep(10)
    return True


def find_circuitpy_path():
    for path in RPI_CIRCUITPYTHON_PATHS:
        if os.path.exists(path):
            return path
    return None


def copy_src_files():
    success = False
    print("Waiting for device volume to mount...")
    print(f"  Looking for: {RPI_CIRCUITPYTHON_PATHS}")
    print(f"  Current volumes: {os.listdir('/Volumes/')}")
    time_prev = time.monotonic()
    while not success:
        try:
            target = find_circuitpy_path()
            print(f"  find_circuitpy_path() returned: {target}")
            if target:
                print(f"  Copying files to {target}...")
                count = 0
                for root, dirs, files in os.walk(SRC_FOLDER_FP):
                    for f in files:
                        src_file = os.path.join(root, f)
                        rel_path = os.path.relpath(src_file, SRC_FOLDER_FP)
                        dst_file = os.path.join(target, rel_path)
                        os.makedirs(os.path.dirname(dst_file), exist_ok=True)
                        shutil.copy2(src_file, dst_file)
                        count += 1
                        print(f"    [{count}] {rel_path}")
                success = True
                print(f"Done! Copied {count} files to {target}")
                time_prev = time.monotonic()
            else:
                raise FileNotFoundError("No matching volume found")
        except Exception as e:
            print(f"Retrying in 2s... ({e})")
            time.sleep(2)

        if time.monotonic() - time_prev > TIMEOUT_THRESHOLD * 2:
            print("Timeout")
            return False

    return True


def main():
    print("Deploy: plug USB → LUMAFADER (hold all four buttons while plugging in)")
    print("UF2:    hold Pico BOOT button → RPI-RP2")
    print()
    while True:
        print("Ready. Mount LUMAFADER or RPI-RP2, then press Enter...")
        input()

        do_uf2 = input("Flash UF2? (y/N): ").strip().lower() == "y"

        if do_uf2:
            if not os.path.isfile(UF2_FP):
                print(f"UF2 not found: {UF2_FP}")
                print("Download into firmware/uf2/ — see README (CircuitPython UF2 files).")
                continue
            if not flash_uf2():
                print("UF2 flashing failed.")
                continue

        if copy_src_files():
            print("Device flashed successfully.")
        else:
            print("Copying src files failed.")


if __name__ == "__main__":
    main()
