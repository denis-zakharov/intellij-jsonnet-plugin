#!/usr/bin/env python3
"""Compare the pinned (stock) sjsonnet against go-jsonnet, the implementation Tanka runs.

Re-run this after bumping sjsonnet (shaded-sjsonnet/build.gradle.kts) or go-jsonnet, then update
docs/sjsonnet-gaps.md and the extension in src/main/kotlin/.../engine/extension/ to match.

  scripts/sjsonnet-conformance.py testdata --go-jsonnet ~/go/pkg/mod/github.com/google/go-jsonnet@v0.22.0
  scripts/sjsonnet-conformance.py params   --go-jsonnet ~/src/google/go-jsonnet

Needs `jsonnet` (go-jsonnet) on PATH and `./gradlew :shaded-sjsonnet:shadowJar` run once. It drives the
shaded jar's own CLI, i.e. *stock* sjsonnet — the plugin's extension is not in play here on purpose;
the extension is covered by TankaNativesTest / StdExtrasTest.

`testdata`  Runs every testdata/*.jsonnet through both and buckets the outcome. Only the buckets that
            indicate a gap are listed: go succeeds but sjsonnet fails / differs, or sjsonnet accepts
            what go rejects. Both failing is agreement (error *text* is not compared).
`params`    For every std function, calls it with each of go's parameter names as a named argument and
            reports the names sjsonnet rejects that the real `jsonnet` accepts (needs go-jsonnet's
            cpp-jsonnet submodule for stdlib/std.jsonnet).
"""
import argparse, glob, json, os, re, subprocess, sys, tempfile
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = "io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.SjsonnetMain"

# go's own test-suite feeds these to the extvar_* cases (main_test.go)
EXT_STR = {"stringVar": "2 + 2"}
EXT_CODE = {
    "codeVar": "3 + 3", "errorVar": "error 'xxx'", "staticErrorVar": ")", "UndeclaredX": "x",
    "selfRecursiveVar": '[42, std.extVar("selfRecursiveVar")[0] + 1]',
    "mutuallyRecursiveVar1": '[42, std.extVar("mutuallyRecursiveVar2")[0] + 1]',
    "mutuallyRecursiveVar2": '[42, std.extVar("mutuallyRecursiveVar1")[0] + 1]',
}


def run(cmd, cwd=None, timeout=60):
    try:
        p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired:
        return -99, "", "TIMEOUT"


def sjsonnet_cmd(jar):
    return ["java", "-XX:TieredStopAtLevel=1", "-Xss16m", "-cp", jar, MAIN]


def testdata_case(args, path):
    name = os.path.basename(path)[: -len(".jsonnet")]
    if os.path.isdir(os.path.join(args.go_jsonnet, "testdata", name + ".golden")):
        return name, "skip_multi_file", ""  # multi-output (-m) cases
    flags = ["-J", "."]
    if name.startswith("extvar_"):
        for k, v in EXT_STR.items(): flags += ["--ext-str", f"{k}={v}"]
        for k, v in EXT_CODE.items(): flags += ["--ext-code", f"{k}={v}"]
    string_out = name.endswith("_string_output")
    if string_out: flags += ["-S"]
    rel = f"testdata/{name}.jsonnet"
    g = run(["jsonnet"] + flags + [rel], args.go_jsonnet)
    s = run(sjsonnet_cmd(args.jar) + flags + [rel], args.go_jsonnet)
    if s[0] == -99: return name, "sj_timeout", ""
    if g[0] == 0 and s[0] == 0:
        try: same = g[1].strip() == s[1].strip() if string_out else json.loads(g[1]) == json.loads(s[1])
        except ValueError: same = g[1].strip() == s[1].strip()
        detail = "" if same else f"go: {g[1][:70]!r}  sjsonnet: {s[1][:70]!r}"
        return name, "both_ok_same" if same else "both_ok_DIFFERENT_OUTPUT", detail
    if g[0] == 0: return name, "go_ok_sjsonnet_FAILS", s[2].strip().splitlines()[-2:][0][:110] if s[2].strip() else ""
    if s[0] == 0: return name, "go_rejects_sjsonnet_accepts", g[2].strip().splitlines()[0][:110]
    return name, "both_fail", ""


def cmd_testdata(args):
    paths = sorted(glob.glob(os.path.join(args.go_jsonnet, "testdata", "*.jsonnet")))
    with ThreadPoolExecutor(args.jobs) as ex:
        results = list(ex.map(lambda p: testdata_case(args, p), paths))
    counts = Counter(b for _, b, _ in results)
    for bucket, n in counts.most_common(): print(f"{n:5d}  {bucket}")
    boring = {"both_ok_same", "both_fail", "skip_multi_file"}
    for name, bucket, detail in results:
        if bucket not in boring: print(f"  [{bucket}] {name}  {detail}")


def go_parameters(src):
    params = {}
    for m in re.finditer(r'name:\s*"(\w+)"[^\n]*?params:\s*ast\.Identifiers\{([^}]*)\}', open(f"{src}/builtins.go").read()):
        params[m.group(1)] = [p.strip().strip('"') for p in m.group(2).split(",") if p.strip()]
    std = open(f"{src}/cpp-jsonnet/stdlib/std.jsonnet").read()
    for pattern in (r"^\s{2}(\w+)\(([^)]*)\)::", r"^\s{2}(\w+)::\s*function\(([^)]*)\)"):
        for m in re.finditer(pattern, std, re.M):
            params.setdefault(m.group(1), [p.split("=")[0].strip() for p in m.group(2).split(",") if p.strip()])
    return params


def cmd_params(args):
    params = go_parameters(args.go_jsonnet)
    def rejects(interp_cmd, expr):
        with tempfile.NamedTemporaryFile("w", suffix=".jsonnet", delete=False) as f: f.write(expr)
        try:
            r = run(interp_cmd + [f.name] if interp_cmd[0] != "jsonnet" else ["jsonnet", "-e", expr])
            return "no parameter" in r[2].lower()
        finally: os.unlink(f.name)
    def probe(item):
        fn, ps = item
        bad = [p for p in ps if rejects(sjsonnet_cmd(args.jar), f"std.{fn}({p}=null)")
               and not rejects(["jsonnet"], f"std.{fn}({p}=null)")]
        return fn, bad
    with ThreadPoolExecutor(args.jobs) as ex:
        found = [(fn, bad) for fn, bad in ex.map(probe, [(f, p) for f, p in params.items() if f != "thisFile"]) if bad]
    for fn, bad in sorted(found): print(f"std.{fn}: sjsonnet rejects go's parameter name(s) {bad}")
    print(f"{len(found)} functions differ; mirror them in StdExtras.goParameterNames")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["testdata", "params"])
    ap.add_argument("--go-jsonnet", required=True, help="go-jsonnet checkout (params) or module dir containing testdata/")
    ap.add_argument("--jar", default=os.path.join(ROOT, "shaded-sjsonnet/build/libs/shaded-sjsonnet.jar"))
    ap.add_argument("--jobs", type=int, default=8)
    a = ap.parse_args()
    a.go_jsonnet = os.path.expanduser(a.go_jsonnet)
    if not os.path.exists(a.jar): sys.exit(f"missing {a.jar}; run ./gradlew :shaded-sjsonnet:shadowJar")
    {"testdata": cmd_testdata, "params": cmd_params}[a.mode](a)
