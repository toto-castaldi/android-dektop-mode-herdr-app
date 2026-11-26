# Technical design — `<project-name>`

**Target version:** v0.1.0

The *how* of the project, one file per topic (`docs/process/docs.md` §2). Requirements are in `docs/requirements/`. Items reference a topic as `design/<topic>` (the file `docs/design/<topic>.md`). Topics grow with the items that introduce or change design choices; relevant decisions with meaningful alternatives go into `docs/adr/`. List the topics with `python3 .agents/tools/backlog.py docs`; read only the files you need.

Add a topic by copying `_TEMPLATE.md` to `<topic>.md` (lowercase kebab-case); split a topic that covers more than one subject.

## Initial architectural decisions

| Decision | Choice | Rationale |
| -------- | ------ | --------- |
| `<topic>` | `<choice>` | `<why>` |

## Open decisions

Decisions still to be closed and the backlog items that will close them:
- Protocollo e trasporto tra l'app e herdr-core (API OpenRPC di `hc schema`, flussi ed eventi, I/O dei terminali, tunnel SSH) e libreria SSH per Android — SPIKE-001
- Esecuzione di herdr-core nel Linux Terminal di Android e come l'app lo raggiunge e lo installa — SPIKE-002
- Emulatore e renderer del terminale (libghostty via JNI, terminal-view di Termux, renderer Compose proprio) e soglie di REQ-NFR-003 — SPIKE-003
