"""Adapt the canonical Android palette to the viewer; --check detects drift."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
colors = (root / 'android/app/src/main/java/com/archery/delaycam/ui/theme/Color.kt').read_text()
names = {'primary': 'AccentOrange', 'background': 'PhoneBg', 'surface': 'PanelBg',
         'text': 'TextPrimary', 'muted': 'TextDim', 'danger': 'DangerRed', 'success': 'OkGreen'}
tokens = {key: '#' + re.search(r'val ' + name + r' = Color\(0xFF([0-9A-F]{6})\)', colors)[1].lower()
          for key, name in names.items()}
path = root / 'android/app/src/main/assets/viewer/viewer.css'
original = path.read_text()
output = original
for name, value in tokens.items():
    output = re.sub(r'--' + name + r':#[0-9a-fA-F]{6}', '--' + name + ':' + value, output)
if '--check' in sys.argv:
    if output != original:
        raise SystemExit('Viewer palette drift; run scripts/sync-viewer-theme.py')
    print('VIEWER_THEME_OK')
else:
    path.write_text(output)
