# Test strategy

- **Unit test JVM** (JUnit, `./gradlew testDebugUnitTest`, nel quality gate): logica pura come regole di spazi e tab, layout dei pane, stati degli agenti, codifica di tasti e mouse, parsing del terminale, verifica delle host key, compatibilità di versione. Nessun processo, rete o filesystem creato dai test.
- **Test strumentali** (`./gradlew connectedDebugAndroidTest`, fuori dal gate): UI Compose, Keystore e finestre, sul Pixel 11 Pro collegato via adb. Si eseguono negli item che toccano UI, Keystore o Desktop Mode.
- **End-to-end manuali o semiautomatici** (fuori dal gate): sul Pixel 11 Pro in Desktop Mode con un herdr-core reale, remoto e nel Linux Terminal; servono a verificare l'accettazione di rilascio e le soglie di REQ-NFR-003. Non si simula il core per dichiarare verificata l'integrazione.
