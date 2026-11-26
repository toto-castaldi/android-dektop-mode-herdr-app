# Integrations

## herdr-core

### API

- Ogni core espone JSON-RPC 2.0 descritto in OpenRPC (`hc schema`, metodo `rpc.discover`), con una *protocol generation* (oggi `1`) negoziata da `hello`. L'API cresce senza rotture dentro una generation; gli errori portano un `data.kind` stabile, su cui l'app decide (mai sul testo del messaggio).
- Riferimento del protocollo: `schema/README.md` del repository herdr-core (modalità di connessione, frame, stream, eventi, documenti).
- Gli agenti riportano lo stato con il Program Status Protocol (OSC 7501); il core lo conserva com'è e l'app lo mostra senza dedurlo dallo schermo.

### Trasporto

- Una connessione SSH per macchina; su di essa un canale `exec` lancia `hc connect`, che parla il protocollo su stdin/stdout e avvia il core se non sta girando. Nessun port forwarding SSH né socket esposto.
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

### Agenti

- `AgentsManager` (`core-client/agents`) a ogni nuova sessione di una macchina chiama `events.subscribe` senza filtri: lo snapshot (`seq`, terminali, anche quelli delle macchine aggiunte a quel core, `macchina/t7`) e poi le notifiche del canale restituito, applicate in ordine (`AgentSnapshot`): `created`/`restarted` sostituiscono il record, `removed` lo toglie, `finished` lo rende non vivo, `status` sostituisce stato, messaggio e `reported`, `turn`, `title` e `labels` aggiornano i campi; gli eventi con `seq` non superiore a quello dello snapshot sono scartati e `missed` porta uno snapshot nuovo. Senza sessione, o se la sottoscrizione fallisce, la macchina non ha agenti finché la sessione successiva non si iscrive di nuovo.
- `CoreSession` mette in coda i frame `DATA` dei canali diversi da 0 finché qualcuno li legge (`CoreRpc.lines`), li divide in righe JSON e restituisce al core un `CREDIT` (u32 LE) per ogni frame letto; `CLOSE` dal core o la fine della sessione chiudono il canale.
- Agente: terminale vivo con `reported` vero o con `parent` (worker avviato con `hc run` da un altro terminale).
- Stato mostrato (`AgentState.of`, regole condivise dai client Herdr, `docs/labels.md` di herdr-app): `blocked` ed `error` → Blocked (con il messaggio dell'agente; nessun quinto stato); `working` → Working; ogni altra parola → Done se `turn` è maggiore di `herdr.seen`, altrimenti Idle; senza l'etichetta `herdr.seen` un `done` riportato è Done e il resto Idle; senza stato riportato → Idle.
- Visto: l'app comunica ad `AgentsManager.setLooking` i terminali della tab mostrata della macchina aperta, solo mentre l'activity è in primo piano (`RESUMED`); per ognuno che è un agente con `herdr.seen` assente o minore di `turn` scrive sul core `terminal.label {terminal, labels: {"herdr.seen": "<turn>"}}`, una volta per turno finché resta sullo schermo (un "segna come non letto" di un altro client non viene sovrascritto); guardarlo di nuovo più tardi lo riscrive. Lo stato passa a Idle solo quando arriva l'evento `labels` del core. Un errore di scrittura permette un nuovo tentativo all'aggiornamento successivo.
- Sidebar (`app/agents`): agenti di tutte le macchine collegate, nell'ordine delle macchine e poi del core; i worker sono mostrati rientrati. Selezionare un agente il cui terminale è in un pane del documento `herdr-android` (`Workspace.paneOf`) apre la sua macchina con quello spazio e quella tab in primo piano; un agente senza pane (per esempio avviato dall'app desktop) non ha azione finché non arriva il peek (US-007).

### Spazi e tab

- Spazi e tab non sono un concetto del core: l'app li salva nel documento opaco `herdr-android` di ogni core (`doc.get`/`doc.set`), con la stessa forma del documento dell'app desktop ma separato da esso. L'app non legge né scrive mai `herdr-app`, quindi i due client non condividono spazi e tab. Formato in `data-model.md`.
- Ogni modifica (creare, rinominare, spostare, chiudere) rilegge il documento e lo scrive con compare-and-set sulla `version` letta; su errore `conflict` rilegge e riapplica la modifica, fino a 5 tentativi. Un documento invariato non viene scritto. Lo stato mostrato è sempre la risposta del core.
- Creare uno spazio o una tab avvia la shell del core con `terminal.run` (`cwd` della nuova tab, etichetta `app=herdr-android`) e poi aggiunge al documento una tab con un solo pane; se la scrittura fallisce, il terminale appena avviato viene terminato.
- Chiudere una tab (o uno spazio, con tutte le sue tab) la toglie dal documento e poi termina e rimuove i suoi terminali con `terminal.kill` e `terminal.rm`; un terminale già finito o assente (`finished`, `not_found`) non è un errore. Nient'altro termina terminali: chiudere l'app o perdere la connessione li lascia in esecuzione.
- `WorkspaceManager` (`core-client`) tiene lo stato per macchina: a ogni nuova sessione (`ConnectionManager.sessions`, anche dopo una riconnessione o un riavvio dell'app) rilegge documento e terminali dal core; senza sessione lo stato è "non disponibile". Le azioni di una macchina girano una alla volta; un errore lascia lo stato mostrato e riporta il motivo.
- La cartella corrente di un terminale è `foreground.cwd` di `terminal.list` (oppure `cwd` se manca). Non esiste un evento per i cambi di cartella: con la schermata aperta l'app rilegge documento e terminali ogni 5 s.

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

- Stesso flusso per host remoti e per la VM del Linux Terminal. Core assente: exit status 127 della riga remota; core incompatibile: risposta a `hello` (vedi Compatibilità). `Compatibility.CoreUpdateNeeded` porta la versione trovata, la minima richiesta (`CoreProtocol.MIN_CORE_VERSION`) e cosa manca oltre alla versione (generation, metodi, modalità framed, errore di `hello`); l'app mostra "trovata X, richiesta Y o successiva".
- I comandi one-shot passano da `CommandRunner` (`core-client/install`), implementato da `SshlibTransport` con la stessa verifica della host key e la stessa autenticazione del flusso verso il core, su un canale `exec` senza PTY: stdout e stderr uniti, decodificati in UTF-8 e limitati agli ultimi 64 KiB (`CommandOutput`), timeout di 10 minuti. `CoreInstaller` contiene i comandi.
- Dopo l'exit 127 `ConnectionManager` controlla con un comando via SSH i prerequisiti dell'installer: `uname -s` Linux o Darwin, `uname -m` tra `x86_64`, `amd64`, `arm64`, `aarch64`, `curl` e `tar` presenti. Lo stato è `CoreMissing(missingPrerequisites)`: se manca qualcosa l'app lo elenca e non propone l'installer; se il controllo non riesce a girare l'installer viene proposto comunque (sarà lui a riportare l'errore).
- Installazione: l'installer ufficiale `https://releases.herdr.dev/core/install.sh` scaricato con `curl -fsSL` in un file temporaneo ed eseguito con `sh`, nella home dell'utente (`~/.local/bin`), senza root. Non si usa `curl … | sh`: con una pipe un download fallito darebbe uno script vuoto con exit 0. L'installer non avvia il core: lo avvia il primo `hc connect`.
- Aggiornamento: `herdr-core update`, con `herdr-core` cercato come nella riga remota (`PATH`, poi `$HOME/.local/bin`); risponde in JSON con `was`, `now`, `updated`; i terminali in corso sopravvivono.
- Entrambi partono solo da `installCore`/`updateCore`, che l'app chiama dopo che l'utente ha confermato il dialogo che mostra il comando esatto; sono ammessi solo da `CoreMissing` senza prerequisiti mancanti, da `CoreIncompatible` con `CoreUpdateNeeded` o dopo un tentativo fallito della stessa operazione. Durante il comando la macchina è in `CoreInstalling(operation, output)` con l'output che cresce; con exit 0 l'app ripete il collegamento e `hello`; altrimenti `CoreInstallFailed(operation, exitStatus, output)` mostra l'output (o il motivo per cui il comando non è partito) con "Riprova". Il log riporta solo il nome dello stato, mai l'output.
- Verificato sul Pixel 11 Pro (`CoreInstallE2ETest`) su walle e nella VM del Linux Terminal, in ambienti isolati (chiave di test con comando forzato che imposta `HOME` e `HC_SESSION` dedicati): installazione, aggiornamento da 0.1.10 e installazione fallita con l'output dell'installer.
- Aggiornamento disponibile per un core compatibile (`CoreUpdateCheck`, `core-client/install`): dopo `hello` l'app esegue sulla macchina, via `CommandRunner`, `curl -fsSL --max-time 20 https://releases.herdr.dev/core/latest.json` e confronta `version` del manifest con `version` di `hello` (`CoreProtocol.compareVersions`). Il manifest vale solo se è un oggetto JSON con `version` stringa numerica puntata (eventuale suffisso `-rc1`) e `generation` assente o uguale a quella dell'app; altrimenti è illeggibile. Il controllo gira in parallelo alla sessione, senza ritardare il collegamento, solo al primo collegamento riuscito di ogni `connect` (anche quello dopo un aggiornamento), non alle riconnessioni automatiche. Se la versione pubblicata è più recente lo stato diventa `Connected(…, availableUpdate)`: la macchina mostra "Connessa · herdr-core X · aggiornamento disponibile: Y" e "Aggiorna herdr-core", che apre lo stesso dialogo di conferma con `herdr-core update`; `updateCore` è ammesso anche da questo stato. Nel frattempo il core installato resta in uso. Un errore del comando (curl assente, rete, HTTP) o un manifest illeggibile non mostra avvisi. L'app non contatta mai `releases.herdr.dev` direttamente. Verificato sul Pixel 11 Pro (`CoreInstallE2ETest`, casi `available` e `nomanifest`) su walle e nella VM del Linux Terminal, in ambienti isolati con core 0.1.20.

### Collegamento e riconnessione

- `core-client` espone `CoreTransport` (apre un flusso di byte verso `herdr-core connect`) implementato da `SshlibTransport`, e `ConnectionManager`, che tiene una connessione per macchina e il suo stato. Nessuna parte fuori da `core-client/ssh` vede i tipi di sshlib.
- Sequenza: SSH (timeout 20 s, keepalive SSH 15 s) → verifica della host key → autenticazione (chiave del Keystore tramite `AuthHandler`, oppure password decifrata solo al momento dell'autenticazione) → canale `exec` con la riga remota → `hello` in plain → modalità framed.
- Esiti che fermano i tentativi fino a un'azione dell'utente: host key da confermare o cambiata, autenticazione fallita, core assente (exit 127), core incompatibile. Gli altri errori (rete, timeout, handshake interrotto) e la caduta di una connessione stabilita portano a "non raggiungibile" con nuovo tentativo dopo 1 s, raddoppiato fino a 30 s.
- Una connessione stabilita è considerata caduta quando il flusso termina o quando `server.status` non risponde entro 10 s; il controllo parte dopo 30 s senza fine del flusso.
- Rete locale (Android 17, targetSdk 37): senza il permesso runtime `ACCESS_LOCAL_NETWORK` una connessione TCP verso un indirizzo della rete locale resta appesa in silenzio (nessun SYN). Prima di ogni tentativo, se il permesso non è concesso, `ConnectionManager` chiede a `LocalNetworkAccess` se l'host ne ha bisogno (`LocalNetwork.isLocalHost`): sono locali gli IPv4 privati (10/8, 172.16/12, 192.168/16), i link-local (169.254/16, fe80::/10), gli IPv6 unique local (fc00::/7), i nomi mDNS `.local` e i nomi che si risolvono in uno di questi indirizzi; non lo sono loopback (127.0.0.1, il Linux Terminal), 100.64/10 (Tailscale) e gli indirizzi pubblici. Un host locale senza permesso non apre connessioni e passa a `LocalNetworkPermissionNeeded`; l'activity chiede il permesso con la finestra di sistema e passa la risposta a `localNetworkPermissionResult`: concesso, le macchine in attesa si collegano; negato, passano a `LocalNetworkDenied` ("accesso alla rete locale non consentito") con le istruzioni e il pulsante per le impostazioni dell'app, senza nuovi tentativi finché l'utente non preme Collega, che chiede di nuovo il permesso. Verificato sul Pixel 11 Pro: con il permesso l'app si collega al core di un host della LAN; senza, un host Tailscale si collega senza richiesta.
- Al primo collegamento la host key presentata viene rifiutata, mostrata all'utente e, se confermata, salvata con la macchina; poi l'app si collega di nuovo. All'avvio l'app si collega da sola alle macchine con host key già confermata.

## Core locale nel Linux Terminal

- La macchina "Questo dispositivo" (`LocalMachine`, id `local`) è `droid@127.0.0.1:2222` con la chiave dell'app `herdr-ssh-default`: il core gira nella VM Debian dell'app Terminal di Android (build `linux-aarch64`) e l'app lo raggiunge con lo stesso trasporto, la stessa verifica della host key e le stesse chiavi del Keystore delle macchine remote.
- Raggiungibilità: nella VM un sshd ascolta su `2222` (porta alta: Android non inoltra porte sotto 1024) e l'app Terminal la inoltra sul loopback di Android dopo che l'utente l'ha accettata (notifica "Nuova porta aperta", *Impostazioni → Controllo porte*). L'IP della VM sulla rete `avf_tap_fixed` non è raggiungibile dalle app e non si usa.
- La VM vive solo finché l'app Terminal è aperta, anche in background o minimizzata; chiuderla spegne la VM e termina i terminali del core. L'app non può avviare la VM da sola: può solo aprire il Terminal con il suo intent di avvio.
- La macchina è sempre nell'elenco, in cima: l'app la aggiunge all'avvio se manca (`MachineRepository.ensureLocal`), non si modifica né si rimuove. Si collega da sola all'avvio come le altre, se la host key è già confermata.
- Configurazione iniziale ("Configura"): l'app mostra, con "Copia comando", un comando da incollare nel Terminal che esegue `apt-get update`, installa `openssh-server`, imposta `Port 2222` in `/etc/ssh/sshd_config.d/port.conf`, riavvia sshd e aggiunge la chiave pubblica dell'app ad `~/.ssh/authorized_keys` solo se non c'è già (`LocalMachine.setupCommand`); poi chiede di accettare la porta. Il comando contiene solo la chiave pubblica.
- Stati e messaggi della macchina locale:

  | Condizione | Rilevamento | Messaggio e azione |
  | --- | --- | --- |
  | Linux Terminal assente o disattivato (`LocalTerminalMissing`) | nessuna activity di avvio per `com.android.virtualization.terminal` (`<queries>` nel manifest); controllato prima di ogni tentativo, nessun nuovo tentativo | come attivarlo nelle Opzioni sviluppatore, con il pulsante per aprirle |
  | Linux Terminal non attivo (`LocalTerminalNotRunning`) | `127.0.0.1:2222` rifiuta la connessione | "Apri Linux Terminal" (intent di avvio), poi nuovi tentativi con il backoff consueto |
  | VM in avvio (`LocalTerminalStarting`) | connessione rifiutata entro 60 s da quando l'utente ha aperto il Terminal dall'app; il primo tentativo parte subito | attendere e accettare la porta 2222 se il Terminal lo chiede |
  | Non configurato (`LocalTerminalNotConfigured`) | connessione ancora rifiutata dopo quei 60 s, con nuovi tentativi; oppure chiave rifiutata, senza nuovi tentativi | comando di configurazione e accettazione della porta; se la porta era già accettata, "Impostazioni del Terminal" per l'arresto forzato e nuova apertura (il port forwarding può non tornare dopo un riavvio della VM nello stesso processo) |
  | VM reimpostata | host key cambiata | rifiuto come per ogni host key cambiata (`security.md`); "Reimposta host key", dopo conferma, dimentica l'impronta e ricollega, così l'utente conferma la nuova |
  | Connessa | — | avviso che chiudere il Terminal spegne la VM e termina i terminali del core |
  | Core assente o incompatibile | exit 127 o `hello` | installazione o aggiornamento come sopra |

- L'app non sa se il Terminal è aperto: lo deduce solo da quando l'utente lo apre con il suo pulsante. Una connessione rifiutata dopo una caduta (Terminal chiuso) torna a "non attivo".
- Connessione rifiutata: `OpenResult.Unreachable(refused = true)`. sshlib 0.6.x non conserva la causa (`ConnectException`) e la riporta come `TransportException` con il messaggio "… Errors: Connection refused", quindi `SshlibTransport` la riconosce dalla causa o dal messaggio (verificato sul Pixel 11 Pro con il Terminal chiuso).
- Raggiungibilità dall'app vera verificata su Android 17: con l'UID dell'app e il permesso `INTERNET`, `127.0.0.1:2222` risponde a Terminal aperto e rifiuta a Terminal chiuso (`LocalMachineE2ETest`).

## Gestione dei guasti

- Una connessione caduta porta la macchina nello stato "non raggiungibile" e l'app ritenta il collegamento; terminali e agenti continuano a girare sul core.
- Un core assente o con versione incompatibile non viene usato: l'app lo segnala e propone installazione o aggiornamento.
