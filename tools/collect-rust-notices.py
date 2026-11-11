"""Collect unmodified license/notice files from a locked cargo metadata export."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("metadata", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
packages = json.loads(args.metadata.read_text(encoding="utf-8-sig"))["packages"]
by_id = {(item["name"], item["version"]): Path(item["manifest_path"]).parent for item in packages}
parts = ["Gouge native password component: third-party notices\n"
         "Includes locked build dependencies; some are not linked into Android binaries.\n"]
for package in sorted(packages, key=lambda item: (item["name"], item["version"])):
    if package["source"] is None:
        continue
    root = Path(package["manifest_path"]).parent
    licenses = sorted(path for path in root.iterdir() if path.is_file()
                      and path.name.upper().startswith(("LICENSE", "COPYING", "NOTICE", "COPYRIGHT")))
    # These published crate archives omit standalone license texts retained in their repository.
    if package["name"] == "jni-sys-macros":
        shared = Path(__file__).resolve().parent.parent / "native/password-kdf/licenses/jni-sys-macros-0.4.1"
        assert json.loads((root / ".cargo_vcs_info.json").read_text())["git"]["sha1"] == "64d77b7a5f119d7b55b4e2c169a4668067ff59e6"
        licenses = [shared / "LICENSE-MIT", shared / "LICENSE-APACHE"]
    if not licenses:
        raise SystemExit(f"Missing notices: {package['name']} {package['version']}")
    parts.append(f"\n{'=' * 72}\n{package['name']} {package['version']}\n"
                 f"License: {package['license']}\nRepository: {package.get('repository') or ''}\n")
    for path in licenses:
        parts.append(f"\n--- {path.name} ---\n{path.read_text(encoding='utf-8')}\n")
    if package["name"] == "cesu8":
        parts.append("\n" + "\n".join((root / "src/lib.rs").read_text(encoding="utf-8").splitlines()[:8]) + "\n")
        parts.append((by_id[("jni-sys", "0.4.1")] / "LICENSE-APACHE").read_text(encoding="utf-8"))
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text("".join(parts), encoding="utf-8")
