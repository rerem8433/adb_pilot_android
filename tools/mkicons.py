import sys, os
sys.path.insert(0, "/home/claude/adbpilot")
os.environ["QT_QPA_PLATFORM"] = "offscreen"
from PySide6.QtWidgets import QApplication
app = QApplication([])
from adbpilot.ui import style
out = sys.argv[1]
for name in style.SVG:
    if name == "logo":
        continue
    col = "#cfcfcf" if name == "debloat" else ("#3ddc84" if name == "app" else "#ffffff")
    style.svg_image(name, 96, col).save(os.path.join(out, "assets/icons", name + ".png"))
style.svg_image("logo", 192).save(os.path.join(out, "assets/icons/logo.png"))
for d, s in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    p = os.path.join(out, "res", "mipmap-" + d)
    os.makedirs(p, exist_ok=True)
    style.svg_image("logo", s).save(os.path.join(p, "ic_launcher.png"))
print("ok")
