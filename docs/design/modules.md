# Modules

Moduli logici dell'app, usati per classificare gli item del backlog. Non sono sottomoduli Git.

| ID | Nome | Responsabilità | Posizione |
| --- | --- | --- | --- |
| `MOD-001` | `app` | UI Compose, finestre e input in Desktop Mode, spazi, tab, layout dei pane, sidebar agenti, schermate delle macchine | da definire con la struttura Gradle (vedi `README.md`, Open decisions) |
| `MOD-002` | `core-client` | connessione SSH ai core, client dell'API di herdr-core (richieste, flussi ed eventi), gestione di macchine e chiavi, installazione e aggiornamento del core | da definire con la struttura Gradle (vedi `README.md`, Open decisions) |
| `MOD-003` | `terminal` | emulazione del terminale (parsing delle sequenze, buffer, scrollback), rendering, selezione e input verso il terminale | da definire con la struttura Gradle (vedi `README.md`, Open decisions) |

## Dipendenze ammesse

- `app` usa `core-client` e `terminal`.
- `terminal` non dipende da `app` né da `core-client`: riceve e restituisce byte e dimensioni tramite un'interfaccia.
- `core-client` non dipende da `app` né da `terminal` e non contiene codice UI.
