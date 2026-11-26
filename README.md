# Herdr Desktop for Android

App Android nativa che porta l'esperienza dell'app desktop [Herdr](https://herdr.dev/) su **Android Desktop Mode**: con il telefono collegato a monitor, tastiera e mouse si lavora con gli agenti di coding in spazi, tab e pane, con una sidebar che mostra chi è al lavoro e chi aspetta una risposta.

- Si collega via SSH a [herdr-core](https://github.com/herdrdev/herdr-core) su macchine remote e al core locale nel Linux Terminal di Android; può installare o aggiornare il core.
- È solo un client: terminali e agenti girano sul core e continuano anche ad app chiusa.
- Non ci sono account, server intermedi o telemetria. Le chiavi SSH restano nell'Android Keystore.
- Dispositivo di riferimento: Pixel 11 Pro.

Progetto indipendente e open source, rilasciato sotto licenza Apache-2.0. Non è un prodotto ufficiale di Herdr.

## Stato

In sviluppo verso la v0.1.0. L'app si apre come finestra ridimensionabile in Desktop Mode e gestisce le macchine con herdr-core: aggiunta, modifica e rimozione, chiave SSH Ed25519 generata nell'Android Keystore (con copia della chiave pubblica) o password, conferma dell'impronta della host key, stato del collegamento e riconnessione automatica. La macchina predefinita "Questo dispositivo" usa il herdr-core nel Linux Terminal del telefono: l'app mostra il comando da incollare nel Terminal (sshd sulla porta 2222 e chiave dell'app), lo apre e spiega cosa fare se non è attivo o non è configurato. Il Linux Terminal va lasciato aperto, anche minimizzato: chiuderlo spegne la sua VM. Lo stato aggiornato si vede con:

```bash
python3 .agents/tools/backlog.py status
```

## Prerequisiti

- JDK 21 (per esempio OpenJDK 21 della distribuzione).
- Android SDK con le licenze accettate (`sdkmanager --licenses`) e `ANDROID_HOME` impostato, oppure `sdk.dir=<percorso dell'SDK>` in `local.properties` (file locale, fuori dal repository). Basta `cmdline-tools`: platform e build-tools necessari li installa il build.
- Gradle non va installato: si usa il wrapper `./gradlew`, che scarica la versione fissata. Le versioni di plugin e dipendenze sono in `gradle/libs.versions.toml`.
- Per il terminale (libreria nativa libghostty-vt): `tar` con supporto xz e accesso di rete al primo build. NDK e CMake li installa il build (oppure `sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"`); Zig non va installato: il build scarica la versione fissata e verifica i checksum di Zig e dei sorgenti di libghostty-vt (`gradle/libs.versions.toml`). Host di build: Linux o macOS. La prima compilazione nativa richiede alcuni minuti; l'APK contiene solo `arm64-v8a`.
- Python 3.10+ per gli strumenti del backlog.
- Per i test sul dispositivo: `adb` (Android SDK platform-tools) e un dispositivo Android con Desktop Mode e Linux Terminal, visibile in `adb devices`. Riferimento: Pixel 11 Pro.

## Comandi

```bash
./gradlew ktlintCheck detekt compileDebugKotlin testDebugUnitTest assembleDebug   # quality gate
./gradlew connectedDebugAndroidTest                                               # test sul dispositivo
./gradlew :core-client:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.e2eHost=<host> \
  -Pandroid.testInstrumentationRunnerArguments.e2eUser=<utente>                  # SSH reale dal dispositivo verso un host
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.lanHost=<host della LAN>        # permesso di rete locale verso un host SSH reale della LAN
./gradlew :core-client:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.e2eLocal=running                   # core locale: Terminal aperto (oppure =stopped, Terminal chiuso)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.coreHost=<host> \
  -Pandroid.testInstrumentationRunnerArguments.coreUser=<utente> \
  -Pandroid.testInstrumentationRunnerArguments.coreCase=install                   # installazione reale del core (oppure =update, =fail; corePort, coreDisplay)
./gradlew installDebug                                                            # installa l'APK di debug sul dispositivo
./gradlew ktlintFormat                                                            # corregge lo stile Kotlin
```

L'APK di debug è in `app/build/outputs/apk/debug/app-debug.apk`.

## Struttura

- `app/` — applicazione Android: UI Compose e Desktop Mode.
- `core-client/` — libreria: SSH e API di herdr-core.
- `terminal/` — libreria: emulazione e rendering del terminale (libghostty-vt via JNI in `src/main/cpp/`).
- `buildSrc/` — logica di build condivisa (download verificati, build nativa).

## Licenze

Le licenze del codice di terze parti incluso nell'app sono in [`NOTICE`](NOTICE), copiato anche nell'APK.

## Documentazione

- Requisiti: [`docs/requirements/`](docs/requirements/)
- Design: [`docs/design/`](docs/design/)
- Regole per gli agenti di coding: [`AGENTS.md`](AGENTS.md)
