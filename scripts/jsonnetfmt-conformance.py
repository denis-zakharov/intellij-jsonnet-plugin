#!/usr/bin/env python3
"""Keep the Kotlin port of go-jsonnet's formatter (fmt/) honest against the real `jsonnetfmt`.

Run this after bumping the port to a newer go-jsonnet (update src/main/kotlin/.../fmt/PortedFrom.kt first), and
before a release.

  scripts/jsonnetfmt-conformance.py version
  scripts/jsonnetfmt-conformance.py goldens [--write]
  scripts/jsonnetfmt-conformance.py fetch --dest ~/.cache/jsonnetfmt-corpus
  scripts/jsonnetfmt-conformance.py differential --corpus DIR[:DIR...] [--variants default|all]

Needs `jsonnetfmt` (go install github.com/google/go-jsonnet/cmd/jsonnetfmt@<version>) on PATH or via --bin.

`version`       Checks the binary is the go-jsonnet release the port is pinned to (PortedFrom.kt).
`goldens`       Runs jsonnetfmt (default options) over the hand-written cases in src/test/resources/fmt/oracle and
                compares with their checked-in goldens; --write regenerates them. Also confirms the upstream goldens
                (src/test/resources/fmt/upstream) still match the binary, i.e. that the pinned version didn't change any.
`fetch`         Sparse-clones the corpora worth running: k8s-libsonnet (one version dir) and go-jsonnet's testdata at the
                pinned tag. Your own Tanka projects are the best corpus of all; pass them to `differential` as well.
`differential`  Runs JsonnetFormatterDifferentialTest (the port vs the binary, every file, byte for byte) and the
                whitespace-only property test over the corpus. `--variants all` covers every option variant
                (see the `variants` list in the test); the default is the default options only.
"""
import argparse, os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORTED_FROM = os.path.join(ROOT, "src/main/kotlin/io/github/denis_zakharov/jsonnettanka/fmt/PortedFrom.kt")
FMT_TESTDATA = os.path.join(ROOT, "src/test/resources/fmt")
K8S_REPO = "https://github.com/jsonnet-libs/k8s-libsonnet"
GO_JSONNET_REPO = "https://github.com/google/go-jsonnet"


def pinned():
    src = open(PORTED_FROM).read()
    return {k: re.search(rf'const val {k} = "([^"]+)"', src).group(1) for k in ("GO_JSONNET_VERSION", "GO_JSONNET_COMMIT")}


def binary(args):
    return os.path.expanduser(args.bin) if args.bin else (shutil.which("jsonnetfmt") or sys.exit("jsonnetfmt not on PATH (or use --bin)"))


def check_version(args):
    want = pinned()["GO_JSONNET_VERSION"]
    out = subprocess.run([binary(args), "--version"], capture_output=True, text=True).stdout.strip()
    ok = out.endswith(want)
    print(f"{'ok' if ok else 'MISMATCH'}: binary says {out!r}, port is pinned to {want}")
    return ok


def fmt(args, path):
    p = subprocess.run([binary(args), "--", path], capture_output=True, text=True)
    return p.stdout if p.returncode == 0 else None


def cmd_version(args):
    return 0 if check_version(args) else 1


def cmd_goldens(args):
    if not check_version(args):
        return 1
    bad = 0
    oracle = os.path.join(FMT_TESTDATA, "oracle")
    for name in sorted(f for f in os.listdir(oracle) if f.endswith(".jsonnet")):
        golden = os.path.join(oracle, name[:-len(".jsonnet")] + ".fmt.golden")
        out = fmt(args, os.path.join(oracle, name))
        if out is None:
            print(f"jsonnetfmt rejects oracle case {name}"); bad += 1; continue
        current = open(golden).read() if os.path.exists(golden) else None
        if out == current:
            continue
        if args.write:
            open(golden, "w").write(out); print(f"wrote {os.path.relpath(golden, ROOT)}")
        else:
            print(f"oracle golden differs: {os.path.relpath(golden, ROOT)} (rerun with --write to update)"); bad += 1
    upstream = os.path.join(FMT_TESTDATA, "upstream")
    for name in sorted(f for f in os.listdir(upstream) if f.endswith(".jsonnet")):
        golden = open(os.path.join(upstream, name[:-len(".jsonnet")] + ".fmt.golden")).read()
        out = fmt(args, os.path.join(upstream, name))
        # Error goldens hold go-jsonnet's message text, not output: only check that the binary rejects the input too.
        if out is None:
            if re.match(r"^\S+:\d+:\d+", golden): continue
            print(f"jsonnetfmt rejects upstream input {name}"); bad += 1
        elif out != golden:
            print(f"upstream golden {name} no longer matches jsonnetfmt (upstream changed it?)"); bad += 1
    print("goldens: " + ("all match" if bad == 0 else f"{bad} problems"))
    return 1 if bad else 0


def sparse_clone(repo, dest, paths, ref=None):
    if os.path.isdir(os.path.join(dest, ".git")):
        print(f"{dest} exists, leaving it as is"); return
    subprocess.run(["git", "clone", "--depth", "1", "--filter=blob:none", "--sparse"] + (["--branch", ref] if ref else []) + [repo, dest], check=True)
    subprocess.run(["git", "-C", dest, "sparse-checkout", "set"] + paths, check=True)


def cmd_fetch(args):
    dest = os.path.expanduser(args.dest)
    os.makedirs(dest, exist_ok=True)
    sparse_clone(K8S_REPO, os.path.join(dest, "k8s-libsonnet"), [args.k8s_version])
    sparse_clone(GO_JSONNET_REPO, os.path.join(dest, "go-jsonnet"), ["testdata", "formatter/testdata"], ref=pinned()["GO_JSONNET_VERSION"])
    print(os.pathsep.join(os.path.join(dest, d) for d in ("k8s-libsonnet", "go-jsonnet")))
    return 0


def cmd_differential(args):
    if not check_version(args):
        return 1
    env = dict(os.environ, JSONNETFMT_BIN=binary(args), JSONNETFMT_VARIANTS=args.variants,
               JSONNETFMT_CORPUS=os.pathsep.join(os.path.expanduser(c) for c in args.corpus))
    # cleanTest: Gradle doesn't treat env vars as inputs, so a bare rerun would be "up to date".
    return subprocess.run(["./gradlew", "cleanTest", "test", "--tests", "*Differential*", "--tests", "*WhitespaceOnly*", "-i", "--console=plain"],
                          cwd=ROOT, env=env).returncode


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bin", help="path to jsonnetfmt (default: from PATH)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("version").set_defaults(fn=cmd_version)
    g = sub.add_parser("goldens"); g.add_argument("--write", action="store_true"); g.set_defaults(fn=cmd_goldens)
    f = sub.add_parser("fetch"); f.add_argument("--dest", required=True); f.add_argument("--k8s-version", default="1.34"); f.set_defaults(fn=cmd_fetch)
    d = sub.add_parser("differential")
    d.add_argument("--corpus", nargs="+", required=True, help="directories (each arg may itself be a ':'-separated list)")
    d.add_argument("--variants", choices=["default", "all"], default="default")
    d.set_defaults(fn=cmd_differential)
    args = ap.parse_args()
    if hasattr(args, "corpus"):
        args.corpus = [c for arg in args.corpus for c in arg.split(os.pathsep)]
    sys.exit(args.fn(args))


if __name__ == "__main__":
    main()
