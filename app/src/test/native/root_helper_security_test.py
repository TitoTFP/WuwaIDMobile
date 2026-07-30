#!/usr/bin/env python3
import hashlib
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import tempfile
import threading

MAGIC = 0x57554944
OPS = {"ping": 0, "copy": 1, "replace": 2, "delete": 3, "exists": 4, "mkdirs": 5,
       "list": 6, "read": 7, "write": 8, "sha1": 9, "sha256": 10}


def request(helper, root, op, fields=(), fallback=False):
    encoded = [x if isinstance(x, bytes) else os.fsencode(x) for x in fields]
    body = struct.pack(">H", len(encoded)) + b"".join(struct.pack(">I", len(x)) + x for x in encoded)
    frame = struct.pack(">IHHI", MAGIC, 1, OPS[op], len(body)) + body
    env = dict(os.environ, WUWA_TEST_ROOT=str(root))
    if fallback:
        env["WUWA_FORCE_OPENAT_FALLBACK"] = "1"
    result = subprocess.run([helper, "--stdio"], input=frame, capture_output=True,
                            env=env, timeout=5, check=False)
    assert len(result.stdout) >= 16, (result.returncode, result.stdout, result.stderr)
    magic, version, status, error, size = struct.unpack(">IHHII", result.stdout[:16])
    assert magic == MAGIC and version == 1 and len(result.stdout) == 16 + size
    return status, error, result.stdout[16:]


def ok(result):
    assert result[0] == 0, result
    return result[2]


def run_mode(helper, fallback):
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td).resolve()
        source = root / "source"
        source.write_bytes(b"source-data")
        outside = root.parent / (root.name + "-outside")
        outside.mkdir()
        try:
            secret = outside / "secret"
            secret.write_bytes(b"secret")
            os.symlink(outside, root / "escape")
            assert request(helper, root, "read", [root / "escape" / "secret"], fallback)[0] == 1

            fifo = root / "fifo"
            os.mkfifo(fifo)
            assert request(helper, root, "exists", [fifo], fallback)[0] == 1
            assert request(helper, root, "read", [fifo], fallback)[0] == 1
            assert request(helper, root, "delete", [fifo], fallback)[0] == 1

            ok(request(helper, root, "mkdirs", [root / "a" / "b"], fallback))
            assert ok(request(helper, root, "exists", [root / "a" / "b"], fallback)) == b"\x01"
            ok(request(helper, root, "copy", [source, root / "a" / "copy"], fallback))
            assert (root / "a" / "copy").read_bytes() == b"source-data"

            staged = root / "staged"
            staged.write_bytes(b"replacement")
            ok(request(helper, root, "replace", [staged, root / "a" / "copy"], fallback))
            assert (root / "a" / "copy").read_bytes() == b"replacement"
            # Conservative source cleanup avoids a second pathname race.
            assert staged.read_bytes() == b"replacement"

            ok(request(helper, root, "write", [root / "atomic", b"atomic-data"], fallback))
            assert ok(request(helper, root, "read", [root / "atomic"], fallback)) == b"atomic-data"
            # Legacy SHA-1 is part of the game manifest protocol; compare a fixed fixture digest.
            assert ok(request(helper, root, "sha1", [root / "atomic"], fallback)) == b"f583f3245dffdec363eef50cdba2641b9f792d2c"
            assert ok(request(helper, root, "sha256", [root / "atomic"], fallback)) == hashlib.sha256(b"atomic-data").hexdigest().encode()
            entries = ok(request(helper, root, "list", [root], fallback))
            assert struct.unpack(">I", entries[:4])[0] >= 5
            ok(request(helper, root, "delete", [root / "atomic"], fallback))
            assert not (root / "atomic").exists()

            # Destination failure must leave no helper temporary sibling.
            before = set(root.iterdir())
            assert request(helper, root, "write", [root / "missing" / "x", b"x"], fallback)[0] == 1
            assert set(root.iterdir()) == before

            # Concurrent source swaps may fail or copy the validated regular inode,
            # but must never follow the outside symlink.
            victim = root / "victim"
            victim.write_bytes(b"safe")
            stop = threading.Event()
            def swap():
                while not stop.is_set():
                    try:
                        victim.unlink(missing_ok=True)
                        os.symlink(secret, victim)
                        victim.unlink(missing_ok=True)
                        victim.write_bytes(b"safe")
                    except FileExistsError:
                        pass
            thread = threading.Thread(target=swap)
            thread.start()
            try:
                for index in range(25):
                    target = root / f"race-{index}"
                    result = request(helper, root, "copy", [victim, target], fallback)
                    if result[0] == 0:
                        data = target.read_bytes()
                        assert data != b"secret", data
            finally:
                stop.set()
                thread.join(timeout=2)
                assert not thread.is_alive()
        finally:
            shutil.rmtree(outside)


def protocol_boundaries(helper):
    ping = struct.pack(">IHHIH", MAGIC, 1, 0, 2, 0)
    malformed = subprocess.run([helper, "--stdio"], input=ping + b"x", stdout=subprocess.PIPE, check=True)
    assert struct.unpack(">H", malformed.stdout[6:8])[0] == 1
    oversized = struct.pack(">IHHIH", MAGIC, 1, 7, 8 * 1024 * 1024 + 1, 0)
    rejected = subprocess.run([helper, "--stdio"], input=oversized, stdout=subprocess.PIPE, check=True)
    assert struct.unpack(">H", rejected.stdout[6:8])[0] == 1


def main():
    helper = sys.argv[1]
    protocol_boundaries(helper)
    run_mode(helper, False)
    run_mode(helper, True)
    print("native root helper security tests: PASS (openat2 + forced openat fallback)")


if __name__ == "__main__":
    main()
