from __future__ import annotations

import json
import pathlib
import subprocess
import urllib.request

ROOT = pathlib.Path('/home/ubuntu/projects/Usagi/docs/metadata_samples')
ROOT.mkdir(parents=True, exist_ok=True)
INDEX_URL = 'https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json'
AAPT = '/home/ubuntu/android-sdk/build-tools/37.0.0/aapt'

with urllib.request.urlopen(INDEX_URL, timeout=60) as response:
    index = json.load(response)

extensions = index.get('extensionList', {}).get('extensions', [])
selected = {}
for item in extensions:
    lib = str(item.get('extensionLib', ''))
    warning = str(item.get('contentWarning', ''))
    key = (lib, warning)
    if lib in {'1.4', '1.6'} and key not in selected:
        apk_url = item.get('resources', {}).get('apkUrl')
        if apk_url:
            selected[key] = item

report = []
for lib in ('1.4', '1.6'):
  for warning in ('CONTENT_WARNING_SAFE', 'CONTENT_WARNING_MIXED', 'CONTENT_WARNING_NSFW'):
    item = selected.get((lib, warning))
    if not item:
        report.append({'extensionLib': lib, 'catalogContentWarning': warning, 'error': 'no sample in index'})
        continue
    package_name = item['packageName']
    apk_path = ROOT / f'{package_name}.apk'
    urllib.request.urlretrieve(item['resources']['apkUrl'], apk_path)
    result = subprocess.run([AAPT, 'dump', 'badging', str(apk_path)], capture_output=True, text=True, check=False)
    tree = subprocess.run([AAPT, 'dump', 'xmltree', str(apk_path), 'AndroidManifest.xml'], capture_output=True, text=True, check=False)
    metadata = [line for line in result.stdout.splitlines() if line.startswith('application:') or line.startswith('uses-feature:')]
    manifest_metadata = [line.strip() for line in tree.stdout.splitlines() if 'tachiyomi' in line.lower() or 'contentwarning' in line.lower()]
    report.append({
        'extensionLib': lib,
        'packageName': package_name,
        'name': item.get('name'),
        'catalogContentWarning': item.get('contentWarning'),
        'apkUrl': item['resources']['apkUrl'],
        'badging': metadata,
        'manifestMetadata': manifest_metadata,
        'aaptExitCode': result.returncode,
        'aaptStderr': result.stderr,
    })

(ROOT / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
print(json.dumps(report, indent=2, ensure_ascii=False))
