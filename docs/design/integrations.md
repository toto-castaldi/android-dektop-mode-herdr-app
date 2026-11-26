# Integrations

## herdr-core

### API

- Ogni core espone JSON-RPC 2.0 descritto in OpenRPC (`hc schema`, metodo `rpc.discover`), con una *protocol generation* (oggi `1`) negoziata da `hello`. L'API cresce senza rotture dentro una generation; gli errori portano un `data.kind` stabile, su cui l'app decide (mai sul testo del messaggio).
- Riferimento del protocollo: `schema/README.md` del repository herdr-core (modalità di connessione, frame, stream, eventi, documenti).
- Gli agenti riportano lo stato con il Program Status Protocol (OSC 7501); il core lo conserva com'è e l'app lo mostra senza dedurlo dallo schermo.

### Trasporto

- Una connessione SSH per macchina; su di essa un canale `exec` lancia `hc connect`, che parla il protocollo su stdin/stdout e avvia il core se non sta girando. Nessun port forwarding né socket esposto.
- Riga remota, la stessa che herdr-core usa per `machine add`, perché `~/.local/bin` spesso non è nel `PATH` delle sessioni non interattive:

  ```sh
  sh -c 'p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; [ -x "$p" ] || { echo "herdr-core not found" >&2; exit 127; }; exec "$p" connect'
  ```

  Exit status 127 con quel messaggio significa core non installato.
- L'app passa subito alla modalità framed (`hello {generation: 1, framed: true}`): frame `[canale u32 LE][kind u8][len u32 LE][payload]`, canale 0 per JSON-RPC (chiamate concorrenti), un canale per ogni stream di terminale e per la sottoscrizione agli eventi.
- Libreria SSH: ConnectBot sshlib (`org.connectbot.sshlib:sshlib`, Apache-2.0), usata solo dentro `core-client` dietro un'interfaccia propria, con versione fissata nel version catalog. Alternativa compatibile: sshj.
- Autenticazione a chiave: la libreria chiede la firma all'app (`AuthHandler.onSignatureRequest`) e l'app firma con la chiave dell'Android Keystore via JCA; la chiave privata non passa mai alla libreria. Algoritmo preferito Ed25519, ripiego ECDSA P-256 dove il Keystore non supporta Ed25519.
- Host key: un `HostKeyVerifier` dell'app confronta il blob della chiave con l'impronta confermata della macchina e rifiuta ogni chiave sconosciuta o cambiata (`security.md`).

### Stato ed eventi

- Al collegamento: `events.subscribe` (snapshot con `seq` più canale di notifiche), poi le letture che servono. Le notifiche `event` (`created`, `lost`, `restarted`, `keep`, `finished`, `removed`, `status`, `turn`, `title`, `labels`, `size`, `foreground`, `doc`) aggiornano lo snapshot; `heartbeat` arriva ogni 15 s; `missed` porta uno snapshot nuovo e scarta gli eventi con `seq` non superiore.
- `seq` riparte a ogni avvio del core: dopo una riconnessione l'app prende uno snapshot nuovo invece di riprendere da un `seq` vecchio.
- I terminali di macchine aggiunte a quel core arrivano nello stesso flusso con indirizzo `macchina/t7`.

### I/O dei terminali

- `terminal.attach {terminal, cols, rows, history?, display?}` apre uno stream: primo paint (schermo, history, stato, titolo) e poi i byte grezzi del programma, che l'app passa al modulo `terminal`.
- Input: frame `DATA` con i byte già codificati dall'emulatore; le risposte automatiche dell'emulatore usano `AUTOMATIC_INPUT` solo se il core lo annuncia in `hello.features` e la risposta di attach lo conferma.
- Ridimensionamento con frame `RESIZE` (cols, rows); `FOCUS` e `FACTS` (colori, dimensione cella) quando cambiano. Vale la dimensione dell'ultimo viewer attivo.
- Controllo di flusso: l'app restituisce `CREDIT` per i byte consumati (credito iniziale 256 KiB); oltre 4 MiB di ritardo il core manda un repaint.
- Fine stream: `CLOSE` dal core con `reason` ed `exit_code`; per un terminale *kept* perso l'app riattacca dopo l'evento `restarted`.

### Compatibilità

Dalla risposta a `hello` l'app decide:

- `generation` più alta di quella dell'app: il core non viene usato e l'app chiede di aggiornare l'app;
- `generation` più bassa, `version` sotto il minimo dell'app o `methods` privo di un metodo usato: il core non viene usato e l'app propone l'aggiornamento del core;
- `hello` fallito con `unsupported_generation`: come generation incompatibile.

### Installazione e aggiornamento

- L'installazione e l'aggiornamento del core usano gli installer ufficiali di Herdr (`curl -fsSL https://releases.herdr.dev/core/install.sh | sh`, `hc update`), eseguiti sulla macchina via SSH solo dopo la conferma dell'utente. I terminali sopravvivono a `hc update`.

## Gestione dei guasti

- Una connessione caduta porta la macchina nello stato "non raggiungibile" e l'app ritenta il collegamento; terminali e agenti continuano a girare sul core.
- Un core assente o con versione incompatibile non viene usato: l'app lo segnala e propone installazione o aggiornamento.
