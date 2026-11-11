"""Prepare the debug APK, updater manifest, and QR. Requires qrcode[pil]."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.tag):
        parser.error('Use a tag containing letters, digits, dots, underscores, or hyphens.')
    import qrcode
    root = Path(__file__).resolve().parents[1]
    source = root / 'app/build/outputs/apk/debug/app-debug.apk'
    metadata = json.loads((source.parent / 'output-metadata.json').read_text())
    assert metadata['applicationId'] == 'com.example.installlock'
    assert len(metadata['elements']) == 1, 'A single universal APK is required.'
    version = metadata['elements'][0]
    out = root / 'build/update-release' / args.tag
    out.mkdir(parents=True, exist_ok=True)
    apk = out / 'install-lock-debug.apk'
    shutil.copy2(source, apk)
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').digest()
    info = dict(packageName=metadata['applicationId'], versionCode=version['versionCode'],
                versionName=version['versionName'], minSdk=metadata['minSdkVersionForDexing'],
                apkName=apk.name, sha256=digest.hex())
    (out / 'update.json').write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')
    url = f'https://github.com/timothyjacobmail/Gouge/releases/download/{args.tag}/{apk.name}'
    payload = {
        'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': 'com.example.installlock/.AdminReceiver',
        'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': url,
        'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM': base64.urlsafe_b64encode(digest).decode().rstrip('='),
        'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': True,
    }
    encoded = json.dumps(payload, separators=(',', ':'))
    (out / 'provisioning.json').write_text(encoded + '\n', encoding='utf-8')
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
    qr.add_data(encoded)
    qr.make(fit=True)
    qr.make_image(fill_color='black', back_color='white').save(out / 'install-lock-setup-qr.png')
    names = [apk.name, 'update.json', 'provisioning.json', 'install-lock-setup-qr.png']
    checksums = []
    for name in names:
        with (out / name).open('rb') as stream:
            checksums.append(f'{hashlib.file_digest(stream, "sha256").hexdigest()}  {name}\n')
    (out / 'SHA256SUMS.txt').write_text(''.join(checksums), encoding='utf-8')
    print(json.dumps(dict(output=str(out), download=url, **info)))


if __name__ == '__main__':
    main()
