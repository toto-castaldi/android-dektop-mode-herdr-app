# Requirements — Herdr Desktop for Android

**Target version:** v0.1.0

**State:** baseline

Il *cosa* del progetto, un file per requisito (`docs/process/docs.md` §1). Il *come* è in `docs/design/`. Ogni cambio di scope o di comportamento si registra qui prima di aggiornare backlog e implementazione (`docs/PROCESS.md` §3, `docs/process/docs.md`). L'elenco dei requisiti si ottiene con `python3 .agents/tools/backlog.py docs`; leggere solo i file necessari.

| File | Contenuto |
| --- | --- |
| `README.md` | obiettivo, attori, scope della versione aperta, aree dei requisiti |
| `glossary.md` | termini usati in requisiti, backlog e codice |
| `acceptance.md` | accettazione di rilascio della versione aperta |
| `REQ-<AREA>-NNN.md` | un requisito; copiare `_TEMPLATE.md` |

## Goal

[Herdr](https://herdr.dev/) è un'app open source per far lavorare agenti di coding in terminali che restano in esecuzione su `herdr-core`, un piccolo server sulla macchina dell'utente. Su desktop l'app Herdr offre spazi, tab, pane e una sidebar degli agenti; su Android esiste solo un client mobile pensato per il telefono.

Herdr Desktop for Android porta l'esperienza dell'app desktop su Android in Desktop Mode: un telefono collegato a monitor, tastiera e mouse diventa una postazione completa per lavorare con gli agenti, su core remoti via SSH o sul core locale nel Linux Terminal di Android. È un'app nativa indipendente, open source (Apache-2.0).

La v0.1.0 ha successo quando, su un Pixel 11 Pro in Desktop Mode con monitor esterno, tastiera e mouse, si può lavorare con gli agenti di un core remoto e del core locale come con l'app desktop: collegarsi, installare il core se serve, organizzare spazi, tab e pane, seguire lo stato degli agenti e rispondere a chi è bloccato.

## Actors

- **Utente:** sviluppatore che usa agenti di coding con Herdr. Configura le macchine e le chiavi, lavora nei terminali e risponde agli agenti dall'app.
- **herdr-core:** il runtime Herdr su una macchina remota o nel Linux Terminal del dispositivo. Esegue terminali e agenti ed espone l'API usata dall'app.
- **Agente:** programma in un terminale del core (ad esempio Claude Code) che riporta il proprio stato e può avviare altri agenti (worker).

## Scope

### In scope for v0.1.0

- Macchine remote con herdr-core raggiunte via SSH, con chiavi nell'Android Keystore e verifica della host key.
- Collegamento al herdr-core locale nel Linux Terminal di Android.
- Installazione e aggiornamento di herdr-core quando manca o è incompatibile, previa conferma.
- Segnalazione di una versione più recente di un herdr-core compatibile, con aggiornamento previa conferma.
- Spazi, tab e pane con terminali interattivi, separati da quelli dell'app desktop Herdr sullo stesso core.
- Agenti che aprono terminali negli spazi e nelle tab dell'app e comunicano tra loro tramite il core.
- Sidebar degli agenti con stati Working, Blocked, Done e Idle, e peek.
- Esperienza Desktop Mode: finestre ridimensionabili e multiple su display esterno, tastiera fisica con scorciatoie, mouse.

### Out of scope for v0.1.0

Do not introduce these without first updating the requirements and the backlog:

- Mod e pannelli delle mod dell'app desktop (QuickJS).
- Pubblicazione: Play Store, F-Droid, firma di release.
- Uso touch sullo schermo del telefono senza Desktop Mode.
- Esecuzione di herdr-core dentro l'app stessa, al di fuori del Linux Terminal.
- Account, sincronizzazione o server intermedi.

## Areas

Functional requirement IDs: `REQ-<AREA>-NNN`; non-functional: `REQ-NFR-NNN`. Areas used in this project:

| Area | Meaning |
| ---- | ------- |
| `CONN` | macchine, collegamento ai core, installazione e aggiornamento del core |
| `WS` | spazi, tab, pane e terminali |
| `AGT` | agenti: sidebar, stati, peek |
| `DM` | Desktop Mode: finestre, display, tastiera, mouse |
| `NFR` | non-functional requirements |
