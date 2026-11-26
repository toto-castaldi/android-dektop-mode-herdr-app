# Modules

Moduli logici dell'app, usati per classificare gli item del backlog. Non sono sottomoduli Git.

| ID | Nome | Responsabilità | Posizione |
| --- | --- | --- | --- |
| `MOD-001` | `app` | UI Compose, finestre e input in Desktop Mode, spazi, tab, layout dei pane, sidebar agenti, schermate delle macchine | modulo Gradle `:app` (applicazione Android), cartella `app/` |
| `MOD-002` | `core-client` | connessione SSH ai core, client dell'API di herdr-core (richieste, flussi ed eventi), gestione di macchine e chiavi, installazione e aggiornamento del core | modulo Gradle `:core-client` (libreria Android), cartella `core-client/` |
| `MOD-003` | `terminal` | emulazione del terminale con libghostty-vt tramite un binding JNI (parsing delle sequenze, buffer, scrollback, codifica di tasti e mouse), rendering in Compose, selezione e input verso il terminale | modulo Gradle `:terminal` (libreria Android), cartella `terminal/` |

## Dipendenze ammesse

Le dipendenze tra moduli sono dichiarate solo nei file `build.gradle.kts` dei moduli; oggi solo `:app` dipende da `:core-client` e `:terminal`.


- `app` usa `core-client` e `terminal`.
- `terminal` non dipende da `app` né da `core-client`: riceve e restituisce byte e dimensioni tramite un'interfaccia.
- `core-client` non dipende da `app` né da `terminal` e non contiene codice UI.
