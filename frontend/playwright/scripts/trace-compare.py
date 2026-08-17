import json, sys, statistics as st


def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(round((p / 100.0) * (len(v) - 1))))]


WANT = {"FireAnimationFrame", "GPUTask", "PageAnimator::serviceScriptedAnimations"}


def load(path):
    threads, ev, marks, dropped = {}, [], [], []
    with open(path) as fh:
        for line in fh:
            if '"cat"' not in line:
                continue
            s = line.strip().rstrip(",")
            if not s.startswith("{"):
                continue
            if '"thread_name"' in s:
                e = json.loads(s)
                threads[(e["pid"], e["tid"])] = e["args"]["name"]
            elif '"name":"DroppedFrame"' in s:
                try:
                    dropped.append(json.loads(s)["ts"])
                except Exception:
                    pass
            elif '"ph":"X"' in s and '"dur"' in s:
                if not any(w in s for w in
                           ('"FireAnimationFrame"', '"GPUTask"',
                            '"serviceScriptedAnimations"')):
                    continue
                e = json.loads(s)
                if e["name"] in WANT:
                    ev.append((e["ts"], e["dur"], e["name"]))
            elif '"name":"set-view-box"' in s and '"ph":"b"' in s:
                marks.append(json.loads(s)["ts"])
    return ev, marks, dropped


def report(tag, path):
    ev, marks, dropped = load(path)
    marks.sort()
    lo, hi = marks[0], marks[-1]
    span = (hi - lo) / 1e6
    nd = sum(1 for t in dropped if lo <= t <= hi)
    print(f"\n{'=' * 60}\n{tag}\n{'=' * 60}")
    print(f"window {span:.2f}s  zooms {len(marks)} ({len(marks)/span:.1f}/s)"
          f"  dropped frames {nd} ({nd/span:.1f}/s)")
    print(f"\n{'event':<38}{'n':>6}{'mean':>8}{'p50':>7}"
          f"{'p95':>8}{'p99':>8}{'max':>9}")
    for name in sorted(WANT):
        d = [dur for (ts, dur, nm) in ev if nm == name and lo <= ts <= hi]
        if not d:
            continue
        print(f"{name:<38}{len(d):>6}{st.mean(d)/1000:>8.2f}"
              f"{pct(d,50)/1000:>7.2f}{pct(d,95)/1000:>8.2f}"
              f"{pct(d,99)/1000:>8.2f}{max(d)/1000:>9.2f}")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        report(arg.split("/")[-1].replace(".json", ""), arg)
