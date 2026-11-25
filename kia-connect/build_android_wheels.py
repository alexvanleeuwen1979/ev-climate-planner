"""Build Android wheels for the exact PyCryptodome version used by Kia Connect."""

import ast
import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tarfile
from urllib.request import urlretrieve
import zipfile

VERSION = "3.24.1"
SHA256 = "3f9e74444c0ecbec7af232a95d282c74b114d53212ce075ed17b7fd7dca32bb3"
SOURCE = f"https://files.pythonhosted.org/packages/source/p/pycryptodome/pycryptodome-{VERSION}.tar.gz"
ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build" / "android-dependencies"
WHEELS = ROOT / "build" / "kia-wheels"


def extensions(source):
    config = ast.parse((source / "setup.py").read_text(encoding="utf-8"))
    for statement in config.body:
        if isinstance(statement, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "ext_modules"
            for target in statement.targets
        ):
            return statement.value.elts
    raise RuntimeError("PyCryptodome compiler configuration was not found")


def make_wheel(source, abi, target, clang):
    tag = f"py3-none-android_26_{abi}"
    output = WHEELS / f"pycryptodome-{VERSION}-{tag}.whl"
    binary = WORK / abi
    binary.mkdir(exist_ok=True)
    setup = extensions(source)
    dist = f"pycryptodome-{VERSION}.dist-info"
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as wheel:
        entries = []

        def add(name, data):
            digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip("=")
            entries.append(f"{name},sha256={digest},{len(data)}")
            wheel.writestr(name, data)

        for path in sorted((source / "lib" / "Crypto").rglob("*")):
            if path.is_file() and "SelfTest" not in path.parts:
                add(path.relative_to(source / "lib").as_posix(), path.read_bytes())
        for entry in setup:
            module = ast.literal_eval(entry.args[0])
            if module.endswith(("._raw_aesni", "._ghash_clmul")):
                # Optional accelerators. The upstream package selects the portable variants.
                continue
            options = {item.arg: ast.literal_eval(item.value) for item in entry.keywords}
            native = binary / (module.split(".")[-1] + ".so")
            subprocess.run([
                str(clang), f"--target={target}26", "-shared", "-fPIC", "-O2", "-fwrapv", "-Wl,-z,max-page-size=16384",
                "-DHAVE_STDINT_H", "-DPYCRYPTO_LITTLE_ENDIAN", "-DSYS_BITS=64", "-DLTC_NO_ASM",
                "-DHAVE_UINT128", "-DHAVE_POSIX_MEMALIGN",
                *("-I" + item for item in options.get("include_dirs", ())),
                *options["sources"], "-o", str(native),
            ], cwd=source, check=True)
            add(module.replace(".", "/") + ".so", native.read_bytes())
        add(f"{dist}/licenses/LICENSE.rst", (source / "LICENSE.rst").read_bytes())
        add(f"{dist}/METADATA", f"Metadata-Version: 2.1\nName: pycryptodome\nVersion: {VERSION}\n".encode())
        add(f"{dist}/WHEEL", f"Wheel-Version: 1.0\nRoot-Is-Purelib: false\nTag: {tag}\n".encode())
        wheel.writestr(f"{dist}/RECORD", "\n".join(entries) + f"\n{dist}/RECORD,,\n")
    print(output.name)


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    WHEELS.mkdir(parents=True, exist_ok=True)
    archive = WORK / f"pycryptodome-{VERSION}.tar.gz"
    if not archive.exists():
        urlretrieve(SOURCE, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("Unexpected PyCryptodome source checksum")
    with tarfile.open(archive) as bundle:
        bundle.extractall(WORK, filter="data")
    source = WORK / f"pycryptodome-{VERSION}"
    ndk = Path(os.environ["ANDROID_HOME"]) / "ndk" / "28.2.13676358"
    clang = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    for abi, target in [("arm64_v8a", "aarch64-linux-android"), ("x86_64", "x86_64-linux-android")]:
        make_wheel(source, abi, target, clang)


if __name__ == "__main__":
    main()



