#!/usr/bin/env python3
"""Static guard for the shared 'latest-debug' release (STEP 2).

Rules, checked on the workflow YAML *text* (stdlib only, no PyYAML):
  R1  release/tag mutating commands appear ONLY in ci.yml, ONLY inside the `publish:` job.
  R2  that job is gated on this repository AND the canonical Android branch ref.
  R3  no other job in any workflow requests `contents: write` (no token able to touch releases).
  R4  no workflow uses `pull_request_target`.
  R5  the workflow-level PUBLISH_REF equals the canonical branch.
  R6  the publish job needs the build job (never publishes untested output).
  R7  Beta publishing (`publish-beta`, ci.yml only) is the ONLY other job allowed to mutate releases /
      hold `contents: write`; it must be gated on this repo + `push` event + `claude/beta*` refs,
      need build AND isolated-apk, never mention `latest-debug`, never delete a release/tag,
      always create prereleases with `--latest=false`, and may only touch `beta-*` tags.
Exit 0 = protected. Exit 1 = violations printed.
"""
import re, sys, pathlib

CANON = "refs/heads/claude/jarvis-mobile-automazioni-dashboard-b4xa7e"
MUTATING = re.compile(
    r"gh\s+release\s+(create|delete|edit|upload)|gh\s+api\b[^\n]*releases|git\s+push|git\s+tag\b|"
    r"--cleanup-tag|action-gh-release|softprops/|actions/create-release|ncipollo/release|"
    r"marvinpinto/action-automatic-releases")

def split_jobs(text):
    """-> {job_name: (start_line_idx, [lines])} for the top-level `jobs:` mapping."""
    lines = text.splitlines()
    try:
        jstart = next(i for i, l in enumerate(lines) if re.match(r"^jobs:\s*$", l))
    except StopIteration:
        return lines, {}
    jobs, name, buf, start = {}, None, [], 0
    for i in range(jstart + 1, len(lines)):
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", lines[i])
        if m:
            if name: jobs[name] = (start, buf)
            name, buf, start = m.group(1), [], i
        elif name is not None:
            buf.append(lines[i])
    if name: jobs[name] = (start, buf)
    return lines, jobs

def code_only(line):
    return re.sub(r"(^|\s)#.*$", "", line)

def check_beta(body):
    errs = []
    code = "\n".join(code_only(l) for l in body.splitlines())
    m = re.search(r"\n    if:(.*?)(?=\n    [A-Za-z_-]+:|\Z)", "\n" + body, re.S)
    cond = m.group(1) if m else ""
    if "github.repository == 'umikasaiii/Jarvis-'" not in cond:
        errs.append("ci.yml:publish-beta: R7 not gated on github.repository")
    if "github.event_name == 'push'" not in cond:
        errs.append("ci.yml:publish-beta: R7 must run on push events only")
    if "startsWith(github.ref, 'refs/heads/claude/beta')" not in cond:
        errs.append("ci.yml:publish-beta: R7 must be gated on claude/beta* refs")
    if "needs.build.outputs.apk_impacting == 'true'" not in cond:
        errs.append("ci.yml:publish-beta: R7 must skip documentation-only/non-APK pushes (apk_impacting)")
    if re.search(r"pull_request|workflow_dispatch|github\.ref\s*!=", cond):
        errs.append("ci.yml:publish-beta: R7 forbidden event/ref condition")
    needs = re.search(r"\n    needs:\s*(.*)", "\n" + body)
    nd = needs.group(1) if needs else ""
    if not ("build" in nd and "isolated-apk" in nd):
        errs.append("ci.yml:publish-beta: R7 must need build and isolated-apk")
    if "latest-debug" in code:
        errs.append("ci.yml:publish-beta: R7 must never mention latest-debug")
    if re.search(r"gh\s+release\s+delete|--cleanup-tag|git\s+push|gh\s+release\s+create[^\n]*\n(?:(?!\n\s*(?:fi|else)\b).)*?--latest(?!=false)", code):
        errs.append("ci.yml:publish-beta: R7 delete/push/Latest forbidden")
    for mm in re.finditer(r"gh\s+release\s+(create|edit|upload)\s+(\S+)", code):
        if not re.match(r"[\"']?(beta-|\$\{?)", mm.group(2)) :
            errs.append(f"ci.yml:publish-beta: R7 release target {mm.group(2)} is not a beta-* tag")
    if "--prerelease" not in code or "--latest=false" not in code:
        errs.append("ci.yml:publish-beta: R7 releases must be --prerelease --latest=false")
    if "gh release create" in code and code.count("--latest=false") < code.count("gh release create"):
        errs.append("ci.yml:publish-beta: R7 every release create needs --latest=false")
    return errs

def check(wf_dir):
    errs = []
    files = sorted(pathlib.Path(wf_dir).glob("*.y*ml"))
    if not any(f.name == "ci.yml" for f in files):
        return ["ci.yml not found"]
    for f in files:
        text = f.read_text()
        lines, jobs = split_jobs(text)
        if "pull_request_target" in "\n".join(code_only(l) for l in lines):
            errs.append(f"{f.name}: R4 pull_request_target is forbidden")
        for jn, (_, body) in jobs.items():
            body_code = "\n".join(code_only(l) for l in body)
            is_publish = f.name == "ci.yml" and jn in ("publish", "publish-beta")
            if MUTATING.search(body_code) and not is_publish:
                errs.append(f"{f.name}:{jn}: R1 release/tag mutation outside ci.yml publish job")
            if re.search(r"contents:\s*write", body_code) and not is_publish:
                errs.append(f"{f.name}:{jn}: R3 'contents: write' outside the publish job")
        # workflow-level (non-job) text must not mutate releases or grant write
        head = "\n".join(code_only(l) for l in lines[: (min((s for s, _ in jobs.values()), default=len(lines)))])
        if MUTATING.search(head) or re.search(r"contents:\s*write", head):
            errs.append(f"{f.name}: R1/R3 workflow-level release mutation or write permission")
        if f.name == "ci.yml":
            if "publish" not in jobs:
                errs.append("ci.yml: no `publish` job found")
            else:
                pub = "\n".join(jobs["publish"][1])
                # the `if:` condition (may be a folded block)
                m = re.search(r"\n    if:(.*?)(?=\n    [A-Za-z_-]+:|\Z)", "\n" + pub, re.S)
                cond = m.group(1) if m else ""
                if "github.repository == 'umikasaiii/Jarvis-'" not in cond:
                    errs.append("ci.yml:publish: R2 not gated on github.repository")
                if f"github.ref == '{CANON}'" not in cond:
                    errs.append("ci.yml:publish: R2 not gated on the canonical branch ref")
                if re.search(r"\bgithub\.ref\s*!=|startsWith\(github\.ref", cond):
                    errs.append("ci.yml:publish: R2 ref gate must be an exact match")
                if not re.search(r"\n    needs:\s*build\b", "\n" + pub):
                    errs.append("ci.yml:publish: R6 must need the build job")
            if "publish-beta" in jobs:
                errs += check_beta("\n".join(jobs["publish-beta"][1]))
            m = re.search(r"^  PUBLISH_REF:\s*(\S+)\s*$", text, re.M)
            if not m or m.group(1) != CANON:
                errs.append("ci.yml: R5 PUBLISH_REF missing or not the canonical branch")
    return errs

if __name__ == "__main__":
    errs = check(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows")
    for e in errs: print("VIOLATION:", e)
    print("publish guard:", "FAILED" if errs else "OK")
    sys.exit(1 if errs else 0)
