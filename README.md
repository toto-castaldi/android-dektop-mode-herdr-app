# Herdr Desktop for Android

App Android nativa che porta l'esperienza dell'app desktop [Herdr](https://herdr.dev/) su **Android Desktop Mode**: con il telefono collegato a monitor, tastiera e mouse si lavora con gli agenti di coding in spazi, tab e pane, con una sidebar che mostra chi è al lavoro e chi aspetta una risposta.

- Si collega via SSH a [herdr-core](https://github.com/herdrdev/herdr-core) su macchine remote e al core locale nel Linux Terminal di Android; può installare o aggiornare il core.
- È solo un client: terminali e agenti girano sul core e continuano anche ad app chiusa.
- Non ci sono account, server intermedi o telemetria. Le chiavi SSH restano nell'Android Keystore.
- Dispositivo di riferimento: Pixel 11 Pro.

Progetto indipendente e open source, rilasciato sotto licenza Apache-2.0. Non è un prodotto ufficiale di Herdr.

## Stato

In sviluppo verso la v0.1.0. Il codice non esiste ancora: la prima parte del lavoro prevede gli studi sull'API di herdr-core, sul core nel Linux Terminal e sul rendering del terminale, e lo scaffold del progetto. Lo stato aggiornato si vede con:

```bash
python3 .agents/tools/backlog.py status
```

## Prerequisiti

- Python 3.10+ per gli strumenti del backlog.
- JDK, Android SDK e Gradle wrapper, con le versioni che fisserà lo scaffold del progetto.
- Un dispositivo Android con Desktop Mode e Linux Terminal, collegato via `adb`, per i test sul dispositivo.

## Comandi

Dopo lo scaffold:

```bash
./gradlew ktlintCheck detekt compileDebugKotlin testDebugUnitTest assembleDebug   # quality gate
./gradlew connectedDebugAndroidTest                                               # test sul dispositivo
```

## Documentazione

- Requisiti: [`docs/requirements/`](docs/requirements/)
- Design: [`docs/design/`](docs/design/)
- Regole per gli agenti di coding: [`AGENTS.md`](AGENTS.md)
