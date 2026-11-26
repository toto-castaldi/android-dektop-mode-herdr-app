# AGENTS.md

## 0. Process

This project follows the shared process in [`docs/PROCESS.md`](docs/PROCESS.md); it says which files of `docs/process/` to read for each topic. Claude Code already has it in context through `CLAUDE.md` and does not read it again; other agents read it before doing any work.

This file contains only project-specific rules. Where it conflicts with `docs/PROCESS.md`, the process wins unless the deviation is listed in [Process overrides](#8-process-overrides).

At the start of every session run `python3 .agents/tools/backlog.py status`, then `show` for the relevant items. Read and change the backlog only through that CLI (`docs/PROCESS.md` §3).

## 1. Project

- **Name:** Herdr Desktop for Android
- **Summary:** app Android nativa e open source (Apache-2.0) che porta l'esperienza dell'app desktop [Herdr](https://herdr.dev/) su Android Desktop Mode: spazi, tab, pane e sidebar degli agenti su herdr-core remoti via SSH o sul core locale nel Linux Terminal. Dispositivo di riferimento: Pixel 11 Pro.
- **Current version:** see `python3 .agents/tools/backlog.py status`.
- **Requirements:** `docs/requirements/` · **Design:** `docs/design/` (one file each, list them with `python3 .agents/tools/backlog.py docs`; read only those you need)

## 2. Language

- **Project language:** Italian
- Project content (`docs/PROCESS.md` §2) and user-facing messages are written in the project language.
- Code identifiers and code comments: English.

## 3. Scope

The scope of the current version is defined in `docs/requirements/README.md`. Do not implement anything listed there as out of scope without first updating requirements and backlog and agreeing the scope change with the user.

- L'app è solo client: non esegue terminali né agenti, e non incorpora herdr-core.
- È un'app nativa indipendente: non portare né copiare il codice Rust/GPUI dell'app desktop. Herdr è un riferimento di comportamento.
- Niente mod (QuickJS), pubblicazione negli store o UX touch da telefono in v0.1.0.

## 4. Stack and architecture

Unless a different decision is recorded in the backlog and in the technical design:

- Kotlin su Android; minSdk e targetSdk = API level del Pixel 11 Pro di riferimento (fissati dallo scaffold)
- Gradle (Kotlin DSL) solo tramite il wrapper, dipendenze nel version catalog
- Jetpack Compose; libreria SSH ed emulatore del terminale decisi dagli spike
- Nessun database: macchine in storage locale dell'app, chiavi nell'Android Keystore, stato di lavoro sul core
- JUnit (test unitari JVM), test strumentali Android sul dispositivo
- Runtime locale: Pixel 11 Pro via `adb`, herdr-core remoto o nel Linux Terminal

Architecture and module boundaries: moduli `app` (UI e Desktop Mode), `core-client` (SSH e API di herdr-core), `terminal` (emulazione e rendering); `terminal` e `core-client` non dipendono da `app`. Details in `docs/design/modules.md`.

## 5. Domain rules

- Mai collegarsi a un host con host key non confermata o cambiata.
- Mai eseguire comandi di installazione o aggiornamento del core senza conferma esplicita dell'utente.
- Chiudere l'app non termina terminali sul core; solo chiudere un pane o una tab lo fa.
- Lo stato di spazi, tab, pane e agenti viene dal core; l'app non lo inventa né lo cambia solo in locale.
- Nessun account, server intermedio o telemetria.

## 6. Quality gate

Closing gate (`docs/PROCESS.md` §6):

```bash
./gradlew ktlintCheck detekt
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Preparation: JDK e Android SDK delle versioni indicate nel README; Gradle scarica le dipendenze al primo avvio. I comandi sono creati dallo scaffold: fino ad allora il gate non può girare e gli item di sola documentazione non ne hanno bisogno.

Integration and end-to-end suites: `./gradlew connectedDebugAndroidTest` con il Pixel 11 Pro collegato via `adb`, negli item che toccano UI, Keystore o Desktop Mode; verifiche end-to-end con un herdr-core reale (remoto e nel Linux Terminal) per i criteri che coinvolgono il core. Non simulare il core per dichiarare verificata un'integrazione.

## 7. Definition of Done additions

In addition to `docs/PROCESS.md` §6:

- Il comportamento visibile in Desktop Mode è verificato sul Pixel 11 Pro e l'outcome dice come.
- Nessuna nuova dipendenza inclusa nell'app ha una licenza incompatibile con Apache-2.0.
- Il logcat prodotto dall'item non contiene chiavi, password, passphrase né contenuto dei terminali.

## 8. Process overrides

None.

## 9. Security and configuration

- Never commit secrets. L'app non richiede configurazione d'ambiente; chiavi di firma, `local.properties` e keystore restano fuori dal repository.
- Do not log tokens, credentials or sensitive personal data.
- Le chiavi private SSH stanno solo nell'Android Keystore; le host key sono sempre verificate (`docs/design/security.md`).
- Nessun SDK di analisi e nessuna connessione di rete oltre a quelle verso le macchine configurate dall'utente.

## 10. Parallel work

Settings of `docs/PROCESS-PARALLEL.md`, which explains them; read it before working on an item if `Shared claims` or `Parallel work` is `on`.

- **Shared claims:** `on`
- **Parallel work:** `on`
- **Workspace root:** `../android-dektop-mode-herdr-app-work`
- **Worker agent command:** `pi --approve`
