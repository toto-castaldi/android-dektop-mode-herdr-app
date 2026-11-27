# Herdr Desktop for Android

App Android nativa che porta l'esperienza dell'app desktop [Herdr](https://herdr.dev/) su **Android Desktop Mode**: con il telefono collegato a monitor, tastiera e mouse si lavora con gli agenti di coding in spazi, tab e pane, con una sidebar che mostra chi è al lavoro e chi aspetta una risposta.

- Si collega via SSH a [herdr-core](https://github.com/herdrdev/herdr-core) su macchine remote e al core locale nel Linux Terminal di Android; può installare o aggiornare il core.
- È solo un client: terminali e agenti girano sul core e continuano anche ad app chiusa.
- Non ci sono account, server intermedi o telemetria. Le chiavi SSH restano nell'Android Keystore.
- Dispositivo di riferimento: Pixel 11 Pro.

Progetto indipendente e open source, rilasciato sotto licenza Apache-2.0 (vedi [`LICENSE`](LICENSE)). Non è un prodotto ufficiale di Herdr.

## Stato

In sviluppo verso la v0.1.0. L'app si apre come finestra ridimensionabile in Desktop Mode e gestisce le macchine con herdr-core: aggiunta, modifica e rimozione, chiave SSH Ed25519 generata nell'Android Keystore (con copia della chiave pubblica) o password, conferma dell'impronta della host key, stato del collegamento e riconnessione automatica. La macchina predefinita "Questo dispositivo" usa il herdr-core nel Linux Terminal del telefono: l'app mostra il comando da incollare nel Terminal (sshd sulla porta 2222 e chiave dell'app), lo apre e spiega cosa fare se non è attivo o non è configurato. Il Linux Terminal va lasciato aperto, anche minimizzato: chiuderlo spegne la sua VM. Con la tastiera fisica tutti i tasti vanno al terminale; le scorciatoie dell'app sono Ctrl+Shift con una lettera e Ctrl+Shift+/ le elenca. Lo stato aggiornato si vede con:

```bash
python3 .agents/tools/backlog.py status
```

## Comandi degli agenti all'app

Un agente in un terminale di un core collegato può chiedere all'app di aprire spazi, tab e pane o di mostrare un agente, come con `herdr-app do` sull'app desktop. La richiesta passa dal core (un'etichetta sul terminale dell'agente), quindi funziona anche con core remoti; con più dispositivi collegati la esegue uno solo. Azioni: `space.new` (`name`, `folder`, `argv`), `tab.new` (`name`, `space`, `folder`, `argv`), `pane.split` (`direction` `right`/`down`, `pane`, `argv`), `space.select` e `tab.select` (`id` oppure `index` da 1), `agent.focus` (`terminal`); le altre rispondono `unsupported`. Dettagli in [`docs/design/integrations.md`](docs/design/integrations.md) (Comandi degli agenti).

Ricetta per bash, da mettere per esempio nelle istruzioni o nel profilo della shell dell'agente; serve solo `hc`, già presente dove gira il core:

```bash
# hda <azione> ['<argomenti JSON>']: comando di un agente all'app Herdr Desktop for Android
hda() {
  local hc id args want re i
  hc=$(command -v hc || echo "$HOME/.local/bin/hc")
  id="$HC_TERMINAL_ID-$(date +%s)-$$-$RANDOM"
  args=${2:-'{}'}
  want="\"herdr-desktop-android.re\":\"{\\\"id\\\":\\\"$id\\\""
  "$hc" label "$HC_TERMINAL_ID" "herdr-desktop-android.do={\"id\":\"$id\",\"action\":\"$1\",\"args\":$args}" >/dev/null || return 1
  if command -v timeout >/dev/null; then
    grep -m1 -q -F "$want" <(timeout 10 "$hc" events --terminal "$HC_TERMINAL_ID" --json)
  else # senza timeout (per esempio macOS): controlla ogni 0,2 s per 10 s
    for i in $(seq 50); do
      "$hc" get "$HC_TERMINAL_ID" --json | grep -q -F "$want" && break
      sleep 0.2
    done
  fi
  re=$("$hc" get "$HC_TERMINAL_ID" --json |
    sed -nE 's/.*"herdr-desktop-android\.re":"((\\.|[^"\\])*)".*/\1/p' | sed -e 's/\\"/"/g' -e 's/\\\\/\\/g')
  case $re in
    "{\"id\":\"$id\",\"ok\":true"*) echo "$re" ;;
    "{\"id\":\"$id\""*) echo "$re" >&2; return 1 ;;
    *) "$hc" label "$HC_TERMINAL_ID" "herdr-desktop-android.do=" >/dev/null
       echo '{"ok":false,"error":"timeout","message":"nessuna risposta dall'"'"'app"}' >&2; return 1 ;;
  esac
}
```

Esempi:

```bash
hda space.new '{"name":"Web","folder":"/srv/web"}'   # {"id":"…","ok":true,"result":{"space":"sp…","tab":"tb…","terminal":"t9"}}
hda pane.split '{"direction":"right","argv":["htop"]}' # divide il pane dell'agente
hda agent.focus "{\"terminal\":\"$HC_TERMINAL_ID\"}"   # mostra questo agente nell'app
```

La funzione stampa la risposta ed esce con 0 se `ok` è vero, altrimenti stampa l'errore (`unsupported`, `invalid_args`, `not_found`, `no_window`, `conflict`, `failed`) ed esce con 1. Se in 10 secondi nessun dispositivo risponde (app chiusa, nessuna finestra sulla macchina per selezioni e focus) toglie la richiesta, stampa l'errore `timeout` ed esce con 1. Dove manca `timeout` di coreutils (per esempio macOS) controlla la risposta ogni 0,2 secondi. Le etichette sono leggibili da chi legge il terminale: non mettere segreti negli argomenti.

## Prerequisiti

- JDK 21 (per esempio OpenJDK 21 della distribuzione).
- Android SDK con le licenze accettate (`sdkmanager --licenses`) e `ANDROID_HOME` impostato, oppure `sdk.dir=<percorso dell'SDK>` in `local.properties` (file locale, fuori dal repository). Basta `cmdline-tools`: platform e build-tools necessari li installa il build.
- Gradle non va installato: si usa il wrapper `./gradlew`, che scarica la versione fissata. Le versioni di plugin e dipendenze sono in `gradle/libs.versions.toml`.
- Per il terminale (libreria nativa libghostty-vt): `tar` con supporto xz e accesso di rete al primo build. NDK e CMake li installa il build (oppure `sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"`); Zig non va installato: il build scarica la versione fissata e verifica i checksum di Zig, dei sorgenti di libghostty-vt e del font del terminale JetBrains Mono (`gradle/libs.versions.toml`). Host di build: Linux o macOS. La prima compilazione nativa richiede alcuni minuti; l'APK contiene solo `arm64-v8a`.
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
adb shell pm revoke io.github.totocastaldi.herdrdesktop android.permission.ACCESS_LOCAL_NETWORK
adb shell pm clear-permission-flags io.github.totocastaldi.herdrdesktop \
  android.permission.ACCESS_LOCAL_NETWORK user-set user-fixed                      # permesso non concesso: senza, il test del dialogo viene saltato
adb shell am instrument -w -e class io.github.totocastaldi.herdrdesktop.LocalNetworkPermissionTest [-e lanHost <host della LAN>] \
  io.github.totocastaldi.herdrdesktop.test/androidx.test.runner.AndroidJUnitRunner   # dialogo del permesso di rete locale, senza disinstallare
./gradlew :core-client:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.e2eLocal=running                   # core locale: Terminal aperto (oppure =stopped, Terminal chiuso)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.coreHost=<host> \
  -Pandroid.testInstrumentationRunnerArguments.coreUser=<utente> \
  -Pandroid.testInstrumentationRunnerArguments.coreCase=install                   # installazione reale del core (oppure =update, =fail; corePort, coreDisplay)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.wsHost=<host> \
  -Pandroid.testInstrumentationRunnerArguments.wsUser=<utente>                    # spazi e tab su un core reale (wsPort, wsDisplay, wsRestart=setup|verify, wsDeleteKey)
adb shell am instrument -w -e class io.github.totocastaldi.herdrdesktop.workspace.PaneE2ETest \
  -e wsHost <host> -e wsUser <utente> [-e wsDisplay <id>] [-e wsHostKey SHA256:…] \
  io.github.totocastaldi.herdrdesktop.test/androidx.test.runner.AndroidJUnitRunner   # pane e terminali su un core reale
adb shell am instrument -w -e class io.github.totocastaldi.herdrdesktop.workspace.TerminalPerformanceE2ETest \
  -e wsPerf true -e wsHost <host della LAN> -e wsUser <utente> [-e wsDisplay <id>] \
  io.github.totocastaldi.herdrdesktop.test/androidx.test.runner.AndroidJUnitRunner   # latenza e frame rate (REQ-NFR-003), risultati in logcat con tag PaneE2E
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.agHost=<host> \
  -Pandroid.testInstrumentationRunnerArguments.agUser=<utente>                    # sidebar degli agenti e peek su un core reale (agPort, agHostKey, agDisplay, agDeleteKey)
adb shell am instrument -w -e class io.github.totocastaldi.herdrdesktop.agents.AdoptionE2ETest \
  -e adHost <host> -e adUser <utente> \
  io.github.totocastaldi.herdrdesktop.test/androidx.test.runner.AndroidJUnitRunner   # terminali avviati dagli agenti negli spazi (adPort, adHostKey, adDisplay; adClaude=true con Claude Code nel pane)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.totocastaldi.herdrdesktop.WindowsE2ETest   # finestre su monitor esterno in Desktop Mode (winDisplay; con wsHost, wsUser e wsHostKey anche due finestre su un core reale; wsKey=write crea solo la chiave). Con am instrument -w, senza disinstallare
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

Il progetto è rilasciato sotto la Apache License 2.0, il cui testo integrale è in [`LICENSE`](LICENSE). Le licenze del codice di terze parti incluso nell'app sono in [`NOTICE`](NOTICE). Entrambi i file sono copiati anche nell'APK, come `assets/LICENSE` e `assets/NOTICE`.

## Documentazione

- Requisiti: [`docs/requirements/`](docs/requirements/)
- Design: [`docs/design/`](docs/design/)
- Regole per gli agenti di coding: [`AGENTS.md`](AGENTS.md)
