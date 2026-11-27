# Requirements — Herdr Desktop for Android

**Target version:** none

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

## Actors

- **Utente:** sviluppatore che usa agenti di coding con Herdr. Configura le macchine e le chiavi, lavora nei terminali e risponde agli agenti dall'app.
- **herdr-core:** il runtime Herdr su una macchina remota o nel Linux Terminal del dispositivo. Esegue terminali e agenti ed espone l'API usata dall'app.
- **Agente:** programma in un terminale del core (ad esempio Claude Code) che riporta il proprio stato e può avviare altri agenti (worker).

## Scope

Nessuna versione aperta. Scope dentro e fuori della prossima versione si scrivono qui quando viene aperta; le capacità già rilasciate sono descritte dai requisiti.

## Areas

Functional requirement IDs: `REQ-<AREA>-NNN`; non-functional: `REQ-NFR-NNN`. Areas used in this project:

| Area | Meaning |
| ---- | ------- |
| `CONN` | macchine, collegamento ai core, installazione e aggiornamento del core |
| `WS` | spazi, tab, pane e terminali |
| `AGT` | agenti: sidebar, stati, peek, comandi all'app |
| `DM` | Desktop Mode: finestre, display, tastiera, mouse |
| `NFR` | non-functional requirements |
