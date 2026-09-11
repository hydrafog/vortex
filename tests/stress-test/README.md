# Vortex Stress Test Infrastructure

Automated test suite that validates pairing, reconnect logic, and session persistence under real-world stress scenarios to uncover race conditions, memory leaks, and protocol edge cases.

## Directory Structure

- `run-all.sh`: Executes all stress test scenarios sequentially.
- `scenarios/`: Individual stress test scripts targeting specific subsystem behaviors.
- `reports/`: Automatically generated test execution logs and outcome summaries.

## Running the Suite

1. Ensure the Linux daemon and Android client are already paired.
2. Connect the Android phone via USB (with ADB enabled).
3. Execute the suite from the repository root:

```bash
./tests/stress-test/run-all.sh
```

Test results are automatically logged to `tests/stress-test/reports/summary.md`.
