# SPIKE-004 probe, agent side: runs in a terminal of the core and sends requests to the app through the core.
# Usage: python3 spike004_agent.py <mode> <count> <timeout_s> <out_file>
# Modes: labels, doc (one request at a time, round trip measured here), labels-cli (same as labels, but
# with one `hc label` process per request), shell (the recipe an agent would use: `hc label`, then bash waiting
# for the reply in `hc events`, timed around the whole command), burst-labels, burst-doc (count requests without waiting, then
# wait for all replies). One JSON line per request in out_file, then a summary line.
import json
import os
import queue
import subprocess
import sys
import threading
import time

DO = "herdr-desktop-android.do"
RE = "herdr-desktop-android.re"
DOC = "herdr-desktop-android.do"

mode, count, timeout, out = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
me = os.environ["HC_TERMINAL_ID"]


class Connection:
    """One `hc connect` in plain mode; after events.subscribe it only carries events, so calls use another."""

    def __init__(self):
        self.p = subprocess.Popen(["hc", "connect"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
                                  bufsize=1)
        self.next_id = 0
        self.call("hello", {"generation": 1})

    def call(self, method, params):
        self.next_id += 1
        rid = self.next_id
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}) + "\n")
        self.p.stdin.flush()
        while True:
            msg = json.loads(self.p.stdout.readline())
            if msg.get("id") == rid:
                if "error" in msg:
                    raise RuntimeError(json.dumps(msg["error"]))
                return msg["result"]


class Core:
    def __init__(self):
        self.rpc = Connection()
        self.stream = Connection()
        # Unfiltered: a terminal filter would drop doc events.
        self.stream.call("events.subscribe", {})
        self.queue = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()

    def call(self, method, params):
        return self.rpc.call(method, params)

    def event(self, deadline):
        """Next event notification before deadline, or None."""
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            try:
                msg = self.queue.get(timeout=left)
            except queue.Empty:
                return None
            if msg.get("method") == "event":
                return msg

    def _read(self):
        for line in self.stream.p.stdout:
            self.queue.put(json.loads(line))


core = Core()
results = []


def label_request(rid, action):
    value = json.dumps({"id": rid, "action": action, "args": {}})
    if mode == "labels-cli":
        subprocess.run(["hc", "label", me, f"{DO}={value}"], check=True, capture_output=True)
    else:
        core.call("terminal.label", {"terminal": me, "labels": {DO: value}})


def doc_request(rid, action):
    for _ in range(10):
        got = core.call("doc.get", {"name": DOC})
        value = got["value"] or {"requests": [], "replies": []}
        value["requests"].append({"id": rid, "from": me, "action": action, "args": {}})
        try:
            core.call("doc.set", {"name": DOC, "value": value, "version": got["version"]})
            return
        except RuntimeError as e:
            if "conflict" not in str(e):
                raise
    raise RuntimeError("doc request: too many conflicts")


def replies_of(msg):
    """Replies (id, by) carried by an event, reading the document for doc events."""
    params = msg["params"]
    if params["type"] == "labels" and params.get("terminal") == me:
        raw = params["data"]["labels"].get(RE)
        if raw:
            r = json.loads(raw)
            return [(r["id"], r.get("by"))]
    if params["type"] == "doc" and params["data"]["name"] == DOC:
        value = core.call("doc.get", {"name": DOC})["value"] or {}
        return [(r["id"], r.get("by")) for r in value.get("replies", []) if r.get("from") == me]
    return []


def wait_for(ids, sent):
    """Waits until every id has a reply (or timeout); keeps listening 1 s more to count duplicates."""
    seen = {}
    deadline = time.monotonic() + timeout
    while True:
        msg = core.event(deadline)
        if msg is None:
            break
        now = time.monotonic()
        for rid, by in replies_of(msg):
            if rid in ids:
                entry = seen.setdefault(
                    rid, {"first_ms": round((now - sent[rid]) * 1000, 1), "rank": len(seen), "by": []}
                )
                if by not in entry["by"]:
                    entry["by"].append(by)
        if len(seen) == len(ids):
            deadline = min(deadline, now + 1.0)
    return seen


SHELL = (
    'hc label "$HC_TERMINAL_ID" "$1" >/dev/null && '
    'grep -m1 -q -F "$2" <(timeout "$3" hc events --terminal "$HC_TERMINAL_ID" --json)'
)


def shell_round(rid):
    value = json.dumps({"id": rid, "action": "probe", "args": {}}, separators=(",", ":"))
    needle = '"%s":"{\\"id\\":\\"%s\\"' % (RE, rid)
    t0 = time.monotonic()
    done = subprocess.run(["bash", "-c", SHELL, "sh", f"{DO}={value}", needle, str(int(timeout))]).returncode == 0
    return {"id": rid, "first_ms": round((time.monotonic() - t0) * 1000, 1) if done else None, "by": []}


labels = mode in ("labels", "labels-cli", "burst-labels")
send = label_request if labels else doc_request
tag = f"{int(time.time())}"
if mode == "shell":
    results = [shell_round(f"{tag}-{i}") for i in range(count)]
elif mode.startswith("burst"):
    ids = [f"{tag}-{i}" for i in range(count)]
    sent = {}
    for rid in ids:
        sent[rid] = time.monotonic()
        send(rid, "probe")
    seen = wait_for(ids, sent)
    for rid in ids:
        results.append({"id": rid, **seen.get(rid, {"first_ms": None, "by": []})})
else:
    for i in range(count):
        rid = f"{tag}-{i}"
        sent = {rid: time.monotonic()}
        send(rid, "probe")
        seen = wait_for([rid], sent)
        results.append({"id": rid, **seen.get(rid, {"first_ms": None, "by": []})})

with open(out, "w") as f:
    for r in results:
        f.write(json.dumps(r) + "\n")
    times = sorted(r["first_ms"] for r in results if r["first_ms"] is not None)
    summary = {
        "mode": mode,
        "count": count,
        "answered": len(times),
        "min_ms": times[0] if times else None,
        "median_ms": times[len(times) // 2] if times else None,
        "max_ms": times[-1] if times else None,
        "duplicates": sum(1 for r in results if len(r["by"]) > 1),
        "in_order": [r.get("rank") for r in results] == sorted(r.get("rank") for r in results if "rank" in r),
    }
    f.write(json.dumps({"summary": summary}) + "\n")
print("SPIKE004-DONE")
