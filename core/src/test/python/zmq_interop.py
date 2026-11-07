"""Optional real libzmq/unchanged SatDecode client qualification.

Run with a Python containing pyzmq and cbor2; Java dependencies must be resolved
and test classes compiled by Maven verify first. No hardware or GNU Radio needed.
"""
from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

PROJECT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT.parent / "src"))

import zmq
from satdecode.frame_client import FrameClient
from satdecode.monitor_client import MonitorClient


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    jars = sorted((PROJECT / "target/test-dependencies").glob("*.jar"))
    if not jars:
        raise RuntimeError("Run Maven verify to resolve test-dependencies first")
    import os
    classpath = os.pathsep.join(map(str, [PROJECT / "target/classes", PROJECT / "target/test-classes", *jars]))
    process = subprocess.Popen(
        [args.java, "--limit-modules", "java.base", "-cp", classpath,
         "eu.dariolucia.jsatdecode.distribution.zmq.ZmqInteropPublisher"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8",
    )
    try:
        # Bound the startup read too, so a broken fixture cannot hang qualification.
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(process.stdout.readline)
            try:
                ready = future.result(timeout=10).split()
            except BaseException:
                process.kill()
                raise
        assert ready[0] == "READY", ready
        frame_endpoint, monitor_endpoint = ready[1:]

        def send(command: str) -> None:
            process.stdin.write(command + "\n")
            process.stdin.flush()

        previous_sequence = -1
        previous_event = -1
        # Recreate both native subscribers while the publishers remain bound.
        for _ in range(2):
            with FrameClient(frame_endpoint) as frames, MonitorClient(monitor_endpoint) as monitor:
                frames.socket.setsockopt(zmq.RCVTIMEO, 100)
                monitor.socket.setsockopt(zmq.RCVTIMEO, 100)
                frame_ready = event_ready = False
                deadline = time.monotonic() + 10
                while not (frame_ready and event_ready) and time.monotonic() < deadline:
                    send("PROBE")
                    try:
                        frames.recv()
                        frame_ready = True
                    except zmq.Again:
                        pass
                    try:
                        message = monitor.recv()
                        event_ready |= message.kind == "event" and message.envelope["event"] == "probe"
                    except zmq.Again:
                        pass
                assert frame_ready and event_ready, "native subscription did not establish"
                send("SEND")
                count = 0
                status_seen = event_seen = False
                deadline = time.monotonic() + 10
                while (count < 20 or not status_seen or not event_seen) and time.monotonic() < deadline:
                    if count < 20:
                        try:
                            frame = frames.recv()
                            if frame.payload[0] != 99:
                                assert frame.payload == bytes((count, 255 - count))
                                assert frame.metadata["receiver_id"] == "test-rx"
                                assert frame.metadata["decoder"] == "test-decoder"
                                assert frame.metadata["rx_time_ns"] == 123456789
                                assert frame.metadata["rx_time_source"] == "recording"
                                assert frame.metadata["metadata"] == {"vcid": 3, "opaque": b"\x00\x01\xff", "absent": None}
                                assert frame.metadata["sequence"] > previous_sequence
                                previous_sequence = frame.metadata["sequence"]
                                count += 1
                        except zmq.Again:
                            pass
                    try:
                        message = monitor.recv()
                        env = message.envelope
                        assert env["receiver_id"] == "test-rx" and env["decoder"] == "test-decoder"
                        if message.kind == "status" and "source.active" in env["metrics"] and "signal.snr_db" in env["metrics"]:
                            assert env["metrics"]["source.active"] is True
                            assert env["metrics"]["signal.snr_db"] == 9.25
                            assert set(env["metrics"]) == set(env["metric_timestamps_ns"])
                            status_seen = True
                        elif message.kind == "event" and env["event"] == "FRAME_LOCK_LOSS":
                            assert env["severity"] == "WARNING"
                            assert env["details"] == {"metric": "frame.lock", "previous": "LOCKED", "value": "UNLOCKED"}
                            assert env["sequence"] > previous_event
                            previous_event = env["sequence"]
                            event_seen = True
                    except zmq.Again:
                        pass
                assert count == 20 and status_seen and event_seen, (count, status_seen, event_seen)
        send("STOP")
        output, errors = process.communicate(timeout=10)
        assert process.returncode == 0 and "STOPPED" in output, (output, errors)
        print("Native libzmq interoperability passed: original Python clients, 40 ordered frames, full statuses/events, both subscribers reconnected.")
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate(timeout=5)


if __name__ == "__main__":
    main()
