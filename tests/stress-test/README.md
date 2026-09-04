# Vortex Stress Test Infrastructure

## Purpose
Automated test suite designed to validate pairing, reconnect logic, and session persistence under real-world stress scenarios to uncover race conditions, memory leaks, and protocol edge cases.

## Directory Structure
- `run-all.sh`: Executes all stress test scenarios sequentially.
- `scenarios/`: Individual stress test scripts targeting specific subsystem behaviors:
  - `01-reconnect-l3.sh`: Tests daemon-level reconnect cycles.
  - `01-reconnect-lan.sh`: Tests local network data channel reconnects.
  - `01-reconnect-loop.sh`: Loops continuous reconnect sequences.
  - `02-bt-toggle.sh`: Tests Bluetooth radio toggling resilience.
  - `03-force-stop.sh`: Tests process crash recovery and restart behavior.
- `reports/`: Automatically generated test execution logs and outcome summaries.

## Running the Suite

1. Ensure the Linux daemon and Android client are already paired.
2. Connect the Android phone via USB (with ADB enabled).
3. Execute the suite from the repository root:

```bash
./tests/stress-test/run-all.sh
```

Test results and diagnostics are automatically logged to `tests/stress-test/reports/summary.md`.
