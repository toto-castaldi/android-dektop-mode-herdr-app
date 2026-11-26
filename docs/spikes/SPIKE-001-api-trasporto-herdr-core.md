# SPIKE-001 — API e trasporto di herdr-core per un client Android

- **Date:** 2026-11-26
- **Outcome:** Positive — un client JVM raggiunge un herdr-core remoto con `ssh <host> hc connect`, elenca i terminali, riceve eventi di stato e legge e scrive l'I/O di un terminale; libreria SSH raccomandata: ConnectBot sshlib, con sshj come alternativa provata.
- **Requirements:** REQ-CONN-001, REQ-NFR-001, REQ-NFR-002

## Questions

1. Quale API e quale versione espone herdr-core?
2. Come ci si arriva da una macchina remota via SSH?
3. Come si ricevono flussi ed eventi?
4. Come si legge e si scrive l'I/O di un terminale?
5. Come si rileva una versione di core incompatibile?
6. Quale libreria SSH per Android (licenza, chiavi ed25519 nel Keystore, verifica della host key, manutenzione)?
7. Un prototipo elenca i terminali di un core remoto e riceve almeno un evento di stato?

## Findings

### 1. API e versione

- herdr-core espone **JSON-RPC 2.0** descritto in **OpenRPC 1.3.2**: `hc schema` o il metodo `rpc.discover` restituiscono il documento (`info.version` = versione del core, licenza Apache-2.0). Versioni osservate: 0.1.20 (walle, locale) e 0.1.22 (crilin, remoto).
- Il protocollo ha una **generation** (oggi `1`) negoziata con `hello`. L'API "only grows": il core ha un test che fallisce su ogni modifica incompatibile rispetto a `schema/released.openrpc.json`; una rottura vera richiede una nuova generation. Stato dichiarato: *preview* (generation 1, versione 0.x).
- Gli errori sono errori JSON-RPC con codice `-32000` e un `data.kind` stabile (`not_found`, `finished`, `unavailable`, `needs_framed`, `unsupported_generation`, `conflict`, …) più un `hint`.
- Metodi utili all'app (31 in tutto): `hello`, `server.status`, `terminal.list|get|run|kill|rm|keep|label|read|attach|send|keys|interrupt|wait|put`, `events.subscribe`, `doc.get|set`, `machine.exec`, `file.read|write|edit`, `templates.list`, `server.restart`, `machine.*`.
- Spazi, tab e split dell'app desktop non sono un concetto del core: l'app desktop li salva nel documento opaco `herdr-app` (`doc.get`/`doc.set` con versione e compare-and-set, evento `doc` alle modifiche). Su crilin il documento ha la forma `{spaces: [{id, name, folder, tabs: [...]}]}` (versione 347). Un client che vuole condividere spazi e tab con l'app desktop deve leggere e scrivere lo stesso documento.

### 2. Arrivare a un core remoto via SSH

- Sul core il trasporto locale è un socket Unix; `hc connect` parla lo stesso protocollo su stdin/stdout. Quindi un core remoto è **un canale SSH `exec` che lancia `hc connect`**: nessun port forwarding, nessun socket esposto, nessun server intermedio.
- Su Linux `hc connect` avvia il core se non sta girando (`connect_or_start` in `crates/herdr-core/src/platform/mod.rs`).
- In una sessione SSH non interattiva `~/.local/bin` spesso non è nel `PATH` (osservato su crilin: `command -v hc` vuoto). Va usata la stessa riga che herdr-core usa per `machine add` (`crates/herdr-core/src/machines.rs`):

  ```sh
  sh -c 'p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; [ -x "$p" ] || { echo "herdr-core not found" >&2; exit 127; }; exec "$p" connect'
  ```

  Exit status 127 con quel messaggio su stderr = core non installato. `HC_SESSION=<nome>` davanti a `exec` raggiunge una sessione separata.
- Una connessione SSH per macchina basta: si aprono più canali `exec` se servono connessioni separate, ma una sola connessione framed porta in parallelo RPC, eventi e più terminali.
- Come l'app desktop: l'app desktop parla col core locale tramite `hc connect` e raggiunge gli altri core attraverso il core locale (`machine.add`, indirizzi `macchina/t7`). L'app mobile ufficiale ("Herdr for iPhone, iPad and Android", https://herdr.dev/mobile/) invece "talks to herdr-core on each machine over SSH", senza account né server: è il modello che segue anche questa app, che non ha un core locale.
- Nota: un core può avere a sua volta macchine aggiunte; `terminal.list` ed `events.subscribe` includono anche i loro terminali (`walle/t7k` visto da crilin). L'app deve decidere se mostrarli o filtrarli per indirizzo senza `/`.

### 3. Flussi ed eventi

- **Modalità plain**: una riga JSON per messaggio. **Modalità framed**: dopo la risposta a `hello {framed: true}`, ogni frame è `[canale u32 LE][kind u8][len u32 LE][payload]` (max 16 MiB). Kind: `0 DATA`, `1 CREDIT`, `2 CLOSE`, `3 RESIZE`, `4 FOCUS`, `5 FACTS`, `6 AUTOMATIC_INPUT`. Il canale 0 porta JSON-RPC in entrambe le direzioni, con chiamate concorrenti. L'app usa la modalità framed (obbligatoria per `terminal.attach`).
- `events.subscribe {labels?, terminal?, children_of?, docs?}` risponde con uno snapshot (`seq`, terminali) e, in framed, un canale su cui arrivano notifiche JSON a righe (con credito): `event` (tipi `created`, `lost`, `restarted`, `keep`, `finished`, `removed`, `status`, `turn`, `title`, `labels`, `size`, `foreground`, `doc`), `heartbeat` ogni 15 s, `missed` + nuovo snapshot per chi resta indietro. `seq` cresce di uno per evento e riparte a ogni processo del core: dopo una riconnessione si riprende uno snapshot (e si rilegge il documento `herdr-app`).
- Lo stato degli agenti è solo quello riportato via OSC 7501 (`idle`, `working`, `done`, `blocked`, `error`, con `kind`, `progress`, `msg`); il core non lo deduce dallo schermo.

### 4. I/O di un terminale

- `terminal.attach {terminal, cols, rows, history?, observe?, display?, resize_notifications?}` restituisce un `channel`. Sul canale il core manda il primo paint (schermo corrente, history opzionale, ultimo stato e titolo), poi i byte grezzi del programma.
- Input: frame `DATA` sullo stesso canale (byte già codificati dal client: il core non codifica tasti); le risposte automatiche dell'emulatore vanno in `AUTOMATIC_INPUT` solo se `automatic_input` è confermato in `hello.features` e nella risposta di attach.
- Ridimensionamento: frame `RESIZE` (`u16 cols, u16 rows`); vince l'ultimo viewer attivo; l'evento `size` lo conferma. `FOCUS` e `FACTS` (colori e dimensione cella) completano il quadro.
- Controllo di flusso: credito iniziale 256 KiB per stream; il client manda `CREDIT` per i byte consumati; oltre 4 MiB di ritardo riceve un repaint invece del backlog.
- Fine: il core manda `CLOSE` con `{reason, exit_code, signal}`; il viewer si stacca mandando `CLOSE`. Su perdita di un terminale *kept* lo stream si chiude: si riattacca dopo l'evento `restarted`.
- Alternative senza stream per casi semplici: `terminal.read` (testo dello schermo e history), `terminal.send` (incolla + Invio), `terminal.keys`.

### 5. Versione di core incompatibile

Con la risposta a `hello` il client confronta tre cose, come fa l'app desktop (`src/hc.rs`, `check`):

1. `generation` del core > quella del client → aggiornare l'app;
2. `generation` minore, o `version` sotto il minimo supportato dal client → aggiornare il core (l'app desktop oggi richiede ≥ 0.1.11, file `distribution/core-version`);
3. `methods` privo di un metodo usato dal client → aggiornare il core.

Se il client chiede una generation che il core non conosce, `hello` fallisce con `kind: unsupported_generation` (verificato, vedi §7). Core assente: exit status 127 della riga remota.

### 6. Librerie SSH

| | ConnectBot sshlib 0.6.9 | sshj 0.41.2 | Apache MINA SSHD 2.20.1 | JSch (fork mwiede) 2.28.8 |
| --- | --- | --- | --- | --- |
| Licenza | Apache-2.0; dipendenze Apache-2.0, ISC, BSL-1.0, BSD (protobuf) | Apache-2.0; BouncyCastle (MIT) | Apache-2.0 | BSD-3-Clause |
| Linguaggio / API | Kotlin, coroutine (+ wrapper bloccante) | Java, bloccante con thread | Java, NIO2 | Java, bloccante |
| Android | scritta per ConnectBot; gestisce i provider `AndroidKeyStore` (`RawKeyFactory`) | usata su Android, ma porta BouncyCastle da registrare al posto del provider "BC" ridotto di Android | "not a stated goal", hook non testati (`docs/android.md`) | Ed25519 su Android solo con BouncyCastle |
| Chiave nel Keystore | punto di estensione `AuthHandler.onSignatureRequest`: l'app firma con `Signature.getInstance("Ed25519")` e la libreria non vede la chiave privata | possibile sostituendo la `KeyAlgorithms.Factory` di `ssh-ed25519` con una firma JCA propria (fatto nel prototipo) | `KeyPair`/`SignatureFactory` personalizzati | `Identity` personalizzata |
| Host key | `HostKeyVerifier` obbligatorio, riceve il blob SSH della chiave | `HostKeyVerifier`; `OpenSSHKnownHosts` rifiuta chiavi sconosciute o cambiate | `ServerKeyVerifier` | `HostKeyRepository` |
| Manutenzione (a 2026-11-26) | release 0.6.9 del 2026-11-12, push del 2026-11-25; API 0.x; un maintainer principale (Kenny Root) | release del 2026-10-12, 2,7k stelle, progetto maturo | release del 2026-10-28, progetto Apache | release del 2026-10-10 |
| Peso (jar, prima di R8) | ~10 MB con Tink, protobuf, Ktor, kstatemachine | ~0,6 MB + ~10,8 MB di BouncyCastle | non misurato | non misurato |

MINA SSHD e JSch sono stati scartati senza prototipo: il primo non supporta Android, il secondo non porta vantaggi rispetto a sshj e richiede anch'esso BouncyCastle.

**Raccomandazione: ConnectBot sshlib**, dietro un'interfaccia di `core-client` che isoli la libreria. Motivi: API Kotlin a coroutine coerente con Compose; firma esterna prevista dalla libreria (stesso punto usato per agent SSH e FIDO2), quindi la chiave del Keystore non va mai adattata ai tipi interni della libreria; nessun BouncyCastle; verifica della host key obbligatoria per costruzione; algoritmi moderni (anche post-quantum) e sviluppo attivo per Android. Rischi: API 0.x che può cambiare (fissare la versione nel version catalog), comunità piccola, compilata con metadata Kotlin 2.4 (richiede un compilatore Kotlin ≥ 2.3: vincolo per TEC-002). **Alternativa: sshj**, provata nello stesso prototipo, se la prima si rivelasse instabile.

Non verificato in questo spike (serve il dispositivo): generazione e uso di una chiave **Ed25519 nell'Android Keystore** sul Pixel 11 Pro. Android supporta Ed25519 nel Keystore solo su dispositivi con KeyMint recente; se non disponibile, il ripiego è ECDSA P-256 (`ecdsa-sha2-nistp256`), supportata dal Keystore e da entrambe le librerie. Anche l'import di una chiave ed25519 esistente nel Keystore va verificato. Il prototipo dimostra la parte lato libreria: la firma passa da JCA con una chiave opaca (`sun.security.ec.ed.EdDSAPrivateKeyImpl`, provider SunEC), mai dai tipi interni della libreria.

### 7. Prototipo

Codice esplorativo in `tmp/spike-001-prototype/` (ignorato da git, non mergiato): Kotlin/JVM, un client framed di ~250 righe condiviso, due trasporti (sshj e ConnectBot sshlib). JDK 21 e Gradle 8.14.3 dall'immagine Docker `gradle:8.14.3-jdk21`; core remoto: herdr-core 0.1.22 su crilin (laptop raggiunto via Tailscale), chiave ed25519 dell'utente.

```sh
docker run --rm --network host -u 1000:1000 -e GRADLE_USER_HOME=/gh -v "$PWD":/p -v "$PWD/../gradle-home":/gh \
  -w /p gradle:8.14.3-jdk21 gradle -q --no-daemon installDist
# sshj: host key da known_hosts
docker run --rm --network host -u 1000:1000 -v "$PWD":/p -v ~/.ssh:/home/ssh:ro -w /p gradle:8.14.3-jdk21 \
  java -cp 'build/install/spike-001-prototype/lib/*' spike.MainKt <host> <user> <chiave> /home/ssh/known_hosts full|unknown-host|old-generation
# ConnectBot sshlib: host key fissata da una riga di known_hosts
... spike.CbsshKt <host> <user> <chiave> <riga-known_hosts> full|unknown-host
```

Sequenza `full`: SSH con host key verificata → firma JCA → `hello {generation:1, framed:true}` → controllo di compatibilità → `terminal.list` → `events.subscribe {labels:{spike:SPIKE-001}}` → `terminal.run` di uno script che riporta `working` e poi `done` via OSC 7501 e poi esegue `cat` → `terminal.attach` 100×30 → input `DATA` → `RESIZE` 120×40 → `terminal.kill` → `terminal.rm`.

Esito (identico con le due librerie; estratto dell'esecuzione con ConnectBot sshlib):

```text
host key ssh-ed25519 matches pinned key: true
signing with sun.security.ec.ed.EdDSAPrivateKeyImpl via provider SunEC
hello: core={"name":"toto-inspiron-5406-2n1",...} version=0.1.22 generation=1 framed=true
       features=["framed","attach","events","status","shell","resize_notifications","automatic_input","restart_environment","put_chunks"]
compatibility: ok
terminal.list: 9 live terminals            (1 locale + 8 di walle, macchina aggiunta su crilin)
events.subscribe: seq=672 channel=1 snapshot=0
event seq=673 type=created   terminal=t63
event seq=674 type=status    terminal=t63 data={"records":{},"reported":true,"status":{"state":"working"}}
event seq=676 type=status    terminal=t63 data={"records":{},"reported":true,"status":{"state":"done"}}
event seq=677 type=turn      terminal=t63 data={"state":"done","turn":1}
terminal.attach -> channel=2 automatic_input=true
stream DATA 288 bytes                      (primo paint)
input echoed back on stream: true
event seq=679 type=size data={"rows":30,"cols":100}
event seq=680 type=size data={"rows":40,"cols":120}
stream CLOSE {"exit_code":null,"reason":"killed","signal":1}
event seq=682 type=finished data={"reason":"killed",...}
event seq=683 type=removed
```

Prove negative:

- host key assente da `known_hosts` (sshj): `TransportException: Could not verify ssh-ed25519 host key ...`, connessione rifiutata;
- host key diversa da quella fissata (ConnectBot sshlib): `SshException: Host key rejected for key: ssh-ed25519`;
- `hello {generation: 99}`: `{"code":-32000,"data":{"hint":"this core speaks generation 1","kind":"unsupported_generation"},"message":"protocol generation 99 not supported"}`.

Il terminale di prova è stato rimosso dal core (`terminal.rm`); `hc ls` su crilin non lo mostra più.

## Limits, costs and risks

- Protocollo in *preview* (generation 1, core 0.x): l'API cresce senza rotture, ma una nuova generation richiederà un aggiornamento dell'app.
- La chiave Ed25519 nel Keystore del Pixel 11 Pro non è verificata (solo la firma via JCA con chiave opaca sulla JVM).
- Prototipo eseguito su JVM desktop, non su Android: il comportamento delle librerie col runtime Android (provider di sicurezza, R8) va provato nel primo item che le integra.
- ConnectBot sshlib è 0.x (API ancora mobile) e richiede Kotlin ≥ 2.3 (dati del 2026-11-26).
- Il formato del documento `herdr-app` (spazi, tab, split) non è documentato come API: è un dato dell'app desktop che il core non interpreta.

## Recommendations

- Registrato in `docs/design/integrations.md`: trasporto `ssh exec` con la riga di `hc connect`, modalità framed, eventi, I/O dei terminali, controllo di compatibilità, libreria SSH ConnectBot sshlib dietro un'interfaccia di `core-client`.
- TEC-002: scegliere Kotlin ≥ 2.3 (compatibile con i metadata 2.4 di ConnectBot sshlib).
- Nel primo item che implementa le chiavi: provare Ed25519 nell'Android Keystore sul Pixel 11 Pro, con ripiego ECDSA P-256.
- Proposta di nuovo item: studiare il formato del documento `herdr-app` per condividere spazi, tab e split con l'app desktop (o decidere di non farlo).

## Sources

- `hc schema` (herdr-core 0.1.20 e 0.1.22) e https://github.com/herdrdev/herdr-core/blob/main/schema/README.md
- https://github.com/herdrdev/herdr-core (`crates/herdr-core/src/machines.rs`, `client.rs`, `platform/mod.rs`, `status.rs`, `docs/machines.md`)
- https://github.com/herdrdev/herdr-app (`src/hc.rs`, `src/setup.rs`, `src/mirror_sync.rs`, `distribution/core-version`)
- https://herdr.dev/mobile/
- https://github.com/connectbot/sshlib, https://github.com/hierynomus/sshj, https://github.com/apache/mina-sshd/blob/master/docs/android.md, https://github.com/mwiede/jsch
