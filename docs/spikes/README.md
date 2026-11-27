# Spikes

A spike is a backlog item of type `Spike` (`SPIKE-NNN`) that answers explicit questions before committing to a design (`docs/PROCESS.md` §4.1).

- Report file: `SPIKE-NNN-short-slug.md`, copied from `_TEMPLATE.md`, written in the project language.
- The spike's acceptance criteria are the questions to answer; the report must answer each of them or state why it could not.
- Exploratory code is not merged unless the acceptance criteria say so. Disposable material goes in an ignored folder (e.g. `tmp/`).
- Decisions resulting from the spike are recorded in `docs/design/` or in an ADR, and follow-up work is added to the backlog.

| Spike | Title | Outcome |
| ----- | ----- | ------- |
| [SPIKE-001](SPIKE-001-api-trasporto-herdr-core.md) | API e trasporto di herdr-core per un client Android | Positive |
| [SPIKE-002](SPIKE-002-core-locale-linux-terminal.md) | herdr-core locale nel Linux Terminal di Android | Partial |
| [SPIKE-003](SPIKE-003-rendering-terminale.md) | Emulazione e rendering del terminale su Android | Positive |
| [SPIKE-004](SPIKE-004-comandi-agenti.md) | Comandi degli agenti verso l'app attraverso il core | Positive |
