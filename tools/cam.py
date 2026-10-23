"""Turns the emulator camera of emulator-5568 (posted right-button drags through tools/harness/emu.sh,
0.044 deg per framebuffer px, TESTING.md "Camera movement") and reads the head pose from the app.

    python -I tools/cam.py pose
    python -I tools/cam.py reset              level, facing Stage -Z
    python -I tools/cam.py look YAW PITCH     reset, then YAW° to the right (negative: left) and PITCH° up
    python -I tools/cam.py turn YAW PITCH     relative turn
"""
import os
import re
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
EMU = os.path.join(ROOT, "tools", "harness", "emu.sh")
DEV = os.path.join(ROOT, "tools", "dev.sh")
DEG_PER_PX = 0.044


def sh(*args):
    return subprocess.run(["bash", *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def pose():
    sh(DEV, "cmd", "pose")
    time.sleep(0.6)
    line = sh(DEV, "log", "CMD pose", "1").strip()
    m = re.search(r"yaw=(-?[0-9.]+) pitch=(-?[0-9.]+)", line)
    if not m:
        raise SystemExit("no pose: " + line)
    # the app's yaw is + to the left; report + to the right
    return -float(m.group(1)), float(m.group(2)), line


def drag_pitch(deg):
    while abs(deg) > 0.05:
        step = max(-45.0, min(45.0, deg))
        px = int(round(step / DEG_PER_PX))
        y0 = 2100 if px >= 0 else 60
        sh(EMU, "drag", "1080", str(y0), "1080", str(y0 - px), "-Button", "right", "-Steps", "24", "-StepMs", "16")
        deg -= step


def drag_yaw(deg):
    while abs(deg) > 0.05:
        step = max(-80.0, min(80.0, deg))
        px = int(round(step / DEG_PER_PX))
        x0 = 60 if px >= 0 else 2100
        sh(EMU, "drag", str(x0), "1079", str(x0 + px), "1079", "-Button", "right", "-Steps", "24", "-StepMs", "16")
        deg -= step


def reset():
    for _ in range(4):
        yaw, pitch, _ = pose()
        if abs(yaw) < 0.6 and abs(pitch) < 0.6:
            break
        # pitch first back to level, then yaw (yaw drags at a pitch tilt the horizon)
        drag_pitch(-pitch)
        time.sleep(0.3)
        yaw, pitch, _ = pose()
        drag_yaw(-yaw)
        time.sleep(0.3)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "pose"
    if cmd == "reset":
        reset()
    elif cmd == "look":
        reset()
        drag_yaw(float(sys.argv[2]))
        time.sleep(0.2)
        drag_pitch(float(sys.argv[3]) if len(sys.argv) > 3 else 0.0)
    elif cmd == "turn":
        drag_yaw(float(sys.argv[2]))
        drag_pitch(float(sys.argv[3]) if len(sys.argv) > 3 else 0.0)
    print(pose()[2])


if __name__ == "__main__":
    main()
