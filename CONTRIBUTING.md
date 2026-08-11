# Contributing

Keep changes focused and verify the public behavior they affect.

```bash
./gradlew :protocol:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Do not commit signing keys, credentials, pairing URLs, local Android SDK paths, build outputs, or user data. Changes to the wire protocol should be checked against the matching upstream T3 server contract.
