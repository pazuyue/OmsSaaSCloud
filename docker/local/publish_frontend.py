"""Publish assets before the entry page without replacing Nginx's mounted directory."""
from pathlib import Path
import shutil

here = Path(__file__).resolve().parent
source = here / 'dist-next'
destination = here / 'dist'
if not (source / 'index.html').is_file():
    raise RuntimeError('Frontend build is missing index.html')
destination.mkdir(exist_ok=True)
files = [file for file in source.rglob('*') if file.is_file()]
files.sort(key=lambda file: file.name == 'index.html')
for file in files:
    target = destination / file.relative_to(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + '.next')
    shutil.copy2(file, temporary)
    temporary.replace(target)
# Keep previous hashed bundles so already-open pages can still load lazy routes.
print('Frontend published:', destination)
