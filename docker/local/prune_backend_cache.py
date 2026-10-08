"""Remove obsolete source files only from the dedicated local build cache."""
from pathlib import Path
import os
import sys

repository, destination = (Path(value).resolve() for value in sys.argv[1:])
if destination == repository or destination in repository.parents:
    raise RuntimeError('Build cache must be separate from the repository')
excluded = {'target', 'node_modules', 'dist', '.git'}
removed = 0
for module in ['ruoyi-api', 'ruoyi-auth', 'ruoyi-common', 'ruoyi-gateway', 'ruoyi-modules', 'ruoyi-visual', 'oms-modules']:
    for directory, dirs, files in os.walk(destination / module):
        dirs[:] = [name for name in dirs if name not in excluded and not (Path(directory) / name).is_symlink()]
        for name in files:
            cached = Path(directory) / name
            if not cached.resolve().is_relative_to(destination):
                raise RuntimeError('Unexpected path outside build cache')
            if not (repository / cached.relative_to(destination)).is_file():
                cached.unlink()
                removed += 1
print('Removed obsolete cached source files:', removed)
