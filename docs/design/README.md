# Technical design — Herdr Desktop for Android

**Target version:** v0.1.0

The *how* of the project, one file per topic (`docs/process/docs.md` §2). Requirements are in `docs/requirements/`. Items reference a topic as `design/<topic>` (the file `docs/design/<topic>.md`). Topics grow with the items that introduce or change design choices; relevant decisions with meaningful alternatives go into `docs/adr/`. List the topics with `python3 .agents/tools/backlog.py docs`; read only the files you need.

Add a topic by copying `_TEMPLATE.md` to `<topic>.md` (lowercase kebab-case); split a topic that covers more than one subject.

## Initial architectural decisions

| Decision | Choice | Rationale |
| -------- | ------ | --------- |
| Tipo di app | App Android nativa indipendente, non un porting del codice Rust/GPUI dell'app desktop | GPUI non ha un backend Android; un'app nativa usa direttamente finestre, input e ciclo di vita di Android Desktop Mode |
| Linguaggio e UI | Kotlin, Jetpack Compose | Toolkit nativo e attuale di Android, con supporto a finestre ridimensionabili, tastiera e mouse |
| Build | Gradle con Kotlin DSL, wrapper e version catalog | Build standard Android, riproducibile con versioni bloccate (REQ-NFR-004) |
| Ruolo dell'app | Solo client: terminali e agenti girano su herdr-core | Stesso modello dell'app desktop: chiudere l'app non ferma il lavoro |
| Trasporto | SSH diretto verso ogni core, remoto o nel Linux Terminal | Nessun server intermedio (REQ-NFR-002), come l'app mobile ufficiale |
| Core locale | herdr-core Linux arm64 nella VM del Linux Terminal di Android | Build ufficiale del core, senza ricompilarlo per Android |
| Terminale | libghostty-vt via JNI nel modulo `terminal`, renderer proprio in Compose ridisegnato al vsync | Stesso emulatore dell'app desktop e di herdr-core, sequenze degli agenti comprese; dettagli e alternative in ADR-001 |
| Segreti | Chiavi SSH nell'Android Keystore | Le chiavi private non lasciano il dispositivo (REQ-NFR-001) |
| Quality gate | ktlint, detekt, compilazione Kotlin, test JUnit su JVM, build dell'APK | Controlli rapidi senza dispositivo; i test sul dispositivo sono separati |
| Licenza | Apache-2.0 | Stessa licenza di Herdr; esclude dipendenze GPL incluse nell'app |

## Open decisions

Decisions still to be closed and the backlog items that will close them:

- Canale e forma dei comandi degli agenti verso l'app (equivalente di `herdr-app do` per agenti su core remoti): SPIKE-004 raccomanda etichette `herdr-desktop-android.do`/`.re` sul terminale dell'agente con claim a compare-and-set su un documento del core (`docs/spikes/SPIKE-004-comandi-agenti.md`); il design viene registrato con la US che implementa i comandi.
