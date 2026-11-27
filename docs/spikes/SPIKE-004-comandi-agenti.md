# SPIKE-004 — Comandi degli agenti verso l'app attraverso il core

- **Date:** 2026-11-27
- **Outcome:** Positive — un agente può chiedere azioni all'app scrivendo una richiesta in un'etichetta del proprio terminale con `hc label`; l'app la riceve dagli eventi del core, la esegue una sola volta anche con più client grazie a un compare-and-set su un documento del core, e risponde con un'altra etichetta che l'agente aspetta con `hc events`.
- **Requirements:** REQ-WS-003, REQ-AGT-001, REQ-AGT-002

## Questions

1. Quali meccanismi del core (documenti, etichette, eventi, terminali o altro) possono portare una richiesta da un agente all'app e la risposta all'agente, con quale latenza, ordine e autenticazione, e cosa succede ad app chiusa o con più client collegati?
2. Confrontando almeno due meccanismi con una prova su un herdr-core reale dal Pixel 11 Pro: quale canale usare, quale forma dei comandi (sottoinsieme di `herdr-app do` per la v0.1.0) e con quale CLI o modo l'agente li invia dalla macchina?
3. Come restano rispettati gli invarianti (stato dal core, nessuna azione distruttiva senza le regole dell'app desktop, nessun server intermedio)? Quali requisiti e design vanno aggiornati e quale US implementa i comandi?

## Findings

### 1. Meccanismi del core

Fonti: `hc schema` di herdr-core 0.1.20 (metodi `terminal.label`, `doc.get`, `doc.set`, `events.subscribe`, `terminal.run`), `hc --help`, e la documentazione di `herdr-app do` (`docs/commands.md` e `defaults/AGENTS.md` del repository herdr-app). Nell'app desktop `herdr-app do <azione>` parla con la finestra aperta sulla stessa macchina; per un core remoto il telefono non è raggiungibile dall'agente, e il core non ha un metodo per inoltrare messaggi a un client: serve un canale costruito sui fatti che il core conserva e notifica.

Ogni processo dell'agente ha nel proprio ambiente `HC_TERMINAL_ID` (il proprio indirizzo, per esempio `t7`) e `HC_TERMINAL_SOCKET`, quindi `hc` e `hc connect` funzionano da dentro il terminale senza configurazione.

| Meccanismo | Andata (agente → app) | Ritorno (app → agente) | Osservazioni |
| --- | --- | --- | --- |
| A. Etichette sul terminale dell'agente | `hc label "$HC_TERMINAL_ID" herdr-desktop-android.do='<json>'` | l'app scrive `herdr-desktop-android.re='<json>'` sullo stesso terminale; l'agente la legge da `hc events --terminal "$HC_TERMINAL_ID"` (lo snapshot iniziale contiene già le etichette) | l'evento `labels` porta tutte le etichette, quindi l'app non fa letture in più; nessun limite pratico di dimensione (provata un'etichetta di 70 KB); CLI già presente sulla macchina |
| B. Documento opaco di richieste | `doc.get` + `doc.set` con compare-and-set su `herdr-desktop-android.do` (coda di richieste) | l'app sposta la richiesta tra le risposte con un altro compare-and-set; l'agente legge con `doc.get` dopo l'evento `doc` | l'evento `doc` porta solo nome e versione (serve un `doc.get`); `hc` non ha un comando per i documenti, l'agente deve parlare JSON-RPC con `hc connect` (script con un linguaggio a scelta, `jq` non è garantito); un filtro `terminal` su `events.subscribe` esclude gli eventi `doc` |
| C. I/O del terminale (sequenza di escape nell'output, come OSC 7501) | l'agente stampa una sequenza | `terminal.send` | l'app vede l'output solo dei terminali che ha in un pane visibile (`terminal.attach`); ad app chiusa o con il terminale fuori schermo la richiesta va persa; la risposta si mescola con l'input dell'agente. Scartato. |
| D. Adozione per etichette (già in REQ-WS-003) | `hc run` con `herdr-desktop-android.space`/`.tab` | il terminale compare nello spazio | copre già "apri un terminale in questo spazio/tab", anche ad app chiusa, ma non azioni sulla finestra (focus, selezione) né una risposta. Resta il modo dichiarativo, complementare. |

Proprietà dei meccanismi A e B, verificate sul core isolato (`HC_SESSION` dedicata) e poi dal Pixel (sezione 2):

- **Ordine.** Il core emette gli eventi in ordine di `seq`. Con A una sola etichetta per terminale: una richiesta scritta prima che l'app abbia letto la precedente viene vista come evento (se l'app è collegata) ma ad app chiusa resta solo l'ultima; quindi una richiesta alla volta per terminale, che è anche il modo d'uso di `herdr-app do` (comando sincrono). Con B la coda conserva tutte le richieste in ordine, anche ad app chiusa.
- **Persistenza.** Etichette e documenti sopravvivono a `hc server restart` (provato). Un'etichetta sparisce con `terminal.rm` del terminale.
- **Autenticazione.** Il core non dice chi ha scritto un'etichetta o un documento. Chi può scrivere è chi può usare il socket del core sulla macchina (l'utente e i suoi processi) o una sessione SSH autorizzata: lo stesso confine di fiducia che già permette a un agente `hc kill`, `hc run` e `doc.set`. I comandi non aggiungono poteri sul core: aggiungono solo azioni sulla finestra dell'app. Con A il richiedente è implicito nel terminale su cui sta l'etichetta (un processo può però scrivere etichette di altri terminali, come può già mandargli tasti con `hc send`).
- **App chiusa.** Nessun client riceve l'evento; la richiesta resta sul core (etichetta o documento) e un client che si collega dopo la trova nello snapshot di `events.subscribe` o con `doc.get`. Eseguire più tardi una richiesta vecchia è sbagliato per azioni come `agent.focus`; l'agente non ha risposta e scade.
- **Più client.** Ogni client iscritto riceve ogni evento: con A, senza coordinamento, due client eseguono la stessa richiesta (provato: ogni richiesta ha avuto due risposte). Con B il compare-and-set fa vincere un solo client. Con A si ottiene lo stesso risultato se il client, prima di eseguire, registra la richiesta in un documento con compare-and-set (`herdr-desktop-android.claims`, l'ultimo id gestito per terminale): chi perde il conflitto rilegge, vede l'id già preso e non esegue.

### 2. Prova dal Pixel 11 Pro e raccomandazione

Prova del 2026-11-27 su un herdr-core remoto (0.1.20) dal Pixel 11 Pro via Tailscale, con una chiave di test del Keystore autorizzata con un comando forzato verso una sessione del core isolata (`HC_SESSION` dedicata). Il codice di prova (vedi Sources) ha due parti:

- lato app, `AgentCommandsProbeE2ETest` sul Pixel: si collega con `ConnectionManager` come l'app, si iscrive con `events.subscribe` e risponde a ogni richiesta (meccanismo A: etichetta di risposta con `terminal.label`; meccanismo B: compare-and-set sul documento);
- lato agente, `spike004_agent.py`, scritto sulla macchina con `file.write` e lanciato in un terminale del core con `terminal.run`: manda le richieste e misura sul proprio orologio il tempo dall'invio alla risposta (quindi senza errori di sincronizzazione degli orologi). Ogni caso con il Pixel come unico client che risponde, salvo dove indicato.

| Caso | Richieste | Mediana | p90 | Max | Esecuzioni per richiesta |
| --- | --- | --- | --- | --- | --- |
| A, etichette (`hc connect` già aperto) | 30 | 33 ms | 42 ms | 592 ms | 1 |
| A, `hc label` come processo per richiesta | 30 | 34 ms | 50 ms | 1017 ms | 1 |
| A, ricetta shell completa (`hc label` + attesa con `hc events`) | 30 | 64 ms | 71 ms | 83 ms | 1 |
| B, documento di richieste | 30 | 74 ms | 89 ms | 124 ms | 1 |
| A con due client, senza coordinamento | 20 | 34 ms | 40 ms | 63 ms | **2** (sempre) |
| B con due client | 20 | 78 ms | 103 ms | 647 ms | 1 (8 a un client, 12 all'altro) |
| A con due client e claim su `herdr-desktop-android.claims` | 20 | 85 ms | 99 ms | 105 ms | 1 (7 e 13) |
| A, 20 richieste in raffica (stesso terminale) | 20 | 608 ms | 932 ms | 970 ms | 1, in ordine |
| B, 20 richieste in raffica | 20 | 233 ms | 282 ms | 795 ms | 1, in ordine |
| A, richiesta con nessun client che risponde; un client si collega 5 s dopo | 1 | 6,1 s | — | — | 1 (dallo snapshot) |
| B, stesso caso | 1 | 6,5 s | — | — | 1 (da `doc.get`) |

Osservazioni:

- La latenza è dominata dalla rete (un giro Pixel ↔ macchina remota) e dal numero di chiamate: A costa un evento e una scrittura, B un evento, una lettura e una scrittura lato app e tre chiamate lato agente. I massimi isolati (0,6–1 s) sono ritardi della rete, non del core.
- Il claim con compare-and-set aggiunge una lettura e una scrittura (circa 50 ms) e rende l'esecuzione unica anche con due client, come B.
- In raffica le richieste arrivano tutte e in ordine perché l'app è collegata e vede ogni evento; ad app chiusa con A resterebbe solo l'ultima. Un agente usa comunque un comando alla volta (aspetta la risposta), come con `herdr-app do`.
- La ricetta shell funziona così com'è con `hc` 0.1.20 e `bash`: `hc label …` e poi `grep -m1 -q -F '<risposta con il mio id>' <(timeout 10 hc events --terminal "$HC_TERMINAL_ID" --json)`. La sostituzione di processo serve perché con una pipe normale la shell aspetterebbe la fine di `hc events` (fino all'heartbeat successivo, 15 s). La risposta già scritta è nello snapshot iniziale di `hc events`, quindi non c'è corsa tra scrittura della risposta e inizio dell'attesa.
- Un core avviato da una sessione SSH non interattiva (come fa l'app con `hc connect`) passa ai propri terminali il `PATH` di quella sessione, che può non contenere `~/.local/bin`: nella prova `hc` non era nel `PATH` del terminale lanciato con `terminal.run` e lo script lo cerca anche in `~/.local/bin`. I terminali dell'app avviano una shell interattiva, che di solito carica il `PATH` dell'utente, ma la ricetta deve prevedere il ripiego.
- Logcat della prova: solo i nomi degli stati della connessione e i riepiloghi numerici; nessuna chiave, password o contenuto dei terminali.

**Raccomandazione: canale A (etichette sul terminale dell'agente) con claim sul documento `herdr-desktop-android.claims`.** È il più veloce, usa solo la CLI `hc` già presente dove gira l'agente (nessun programma da installare, nessun JSON-RPC a mano), lega la richiesta al terminale che la fa e, con il claim, viene eseguito una sola volta anche con più dispositivi. B resta l'alternativa se servisse una coda di richieste ad app chiusa, ma richiede all'agente uno script che parli JSON-RPC (`hc` non ha comandi per i documenti) e non dice da quale terminale arriva la richiesta.

Forma proposta:

- Richiesta: etichetta `herdr-desktop-android.do` sul terminale dell'agente, valore JSON `{"id": "<unico>", "action": "<azione>", "args": {…}}`, con nomi di azioni e argomenti uguali a `herdr-app do` (stessi nomi, stesso significato), così le istruzioni per gli agenti dell'app desktop valgono anche qui.
- Risposta: etichetta `herdr-desktop-android.re` sullo stesso terminale, `{"id": "<id>", "ok": true, "result": {…}}` (per esempio `{"space", "tab", "terminal"}` o `{"pane", "terminal"}` come `herdr-app do`) oppure `{"id": "<id>", "ok": false, "error": "<tipo>"}` con tipi stabili (`unsupported`, `invalid_args`, `not_found`, `no_window`, `conflict`).
- Esecuzione nell'app: `AgentsManager` riceve già gli eventi `labels` di tutti i terminali; per una richiesta nuova di un terminale vivo l'app scrive con compare-and-set `herdr-desktop-android.claims` (ultimo id gestito per terminale) e, solo se vince, esegue con le operazioni che l'app già usa (`WorkspaceManager`) e scrive la risposta. Un dispositivo che non può eseguire un'azione sulla finestra (nessuna finestra con quella macchina) non fa il claim, così la richiesta va a un dispositivo che può.
- Richieste trovate nello snapshot al collegamento: sono eseguite se il terminale è vivo e la richiesta non ha risposta; l'agente che rinuncia per timeout toglie la propria richiesta (`hc label "$HC_TERMINAL_ID" herdr-desktop-android.do=`), quindi una richiesta ancora presente è di un agente che aspetta.

Sottoinsieme di `herdr-app do` per la v0.1.0 (nessuna azione distruttiva):

| Azione | Argomenti | Effetto nell'app |
| --- | --- | --- |
| `space.new` | `name`, `folder` | nuovo spazio con una tab e la shell del core (come "Nuovo spazio") |
| `tab.new` | `name`, `space`, `folder`, `argv` | nuova tab; senza `space` lo spazio del terminale che chiede |
| `pane.split` | `direction` (`right`/`down`), `pane`, `argv` | divide il pane indicato o, senza `pane`, quello del terminale che chiede |
| `space.select`, `tab.select` | `id` o `index` | porta lo spazio o la tab in primo piano nella finestra della macchina |
| `agent.focus` | `terminal` | come selezionare l'agente nella sidebar (tab in primo piano o peek) |

Le altre azioni (`space.close`, `tab.close`, `pane.close`, `terminal.kill`, layout, mod, `app.*`, `window.*`) rispondono `unsupported`.

CLI sulla macchina: nessun nuovo programma; la ricetta con `hc` documentata nel README e nelle istruzioni per gli agenti, per esempio come funzione shell:

```bash
hda() {  # hda <azione> '<argomenti json>'
  local hc id; hc=$(command -v hc || echo "$HOME/.local/bin/hc")
  id="$HC_TERMINAL_ID-$(date +%s%N)"
  "$hc" label "$HC_TERMINAL_ID" "herdr-desktop-android.do={\"id\":\"$id\",\"action\":\"$1\",\"args\":${2:-{\}}}" >/dev/null || return 1
  if grep -m1 -q -F "\"herdr-desktop-android.re\":\"{\\\"id\\\":\\\"$id\\\"" \
       <(timeout 10 "$hc" events --terminal "$HC_TERMINAL_ID" --json); then
    "$hc" get "$HC_TERMINAL_ID" --json   # la risposta è nell'etichetta herdr-desktop-android.re
  else
    "$hc" label "$HC_TERMINAL_ID" "herdr-desktop-android.do=" >/dev/null; return 1
  fi
}
```

La forma esatta (nome della funzione, stampa della sola risposta, codice di uscita come `herdr-app do`) è lavoro della US.

### 3. Invarianti, documenti da aggiornare e US proposta

- **Stato dal core.** Richiesta, claim e risposta stanno sul core; l'app esegue con le stesse operazioni dei comandi dell'utente (compare-and-set su `herdr-desktop-android`, `terminal.run`) e risponde solo dopo la scrittura sul core; la finestra mostra, come sempre, il documento letto dal core. Le azioni sulla finestra (`*.select`, `agent.focus`) sono stato della schermata, come il focus dei pane oggi.
- **Nessuna azione distruttiva.** Il sottoinsieme non chiude tab, pane o spazi e non termina terminali. Se una versione successiva aggiungerà `tab.close`/`space.close`, varranno le regole dell'app desktop: senza `confirmed: true` l'app chiede conferma all'utente con un dialogo, e chiudere toglie dal documento e poi termina i terminali come oggi. Un agente che vuole terminare un terminale ha già `hc kill`, fuori dall'app.
- **Nessun server intermedio.** Si usano solo il core e la connessione SSH che l'app ha già; sulla macchina solo `hc`.
- **App desktop.** Le etichette `herdr-desktop-android.do`/`.re` e il documento `herdr-desktop-android.claims` sono dell'app; l'app desktop non li legge e il documento `herdr-app` non viene toccato. Le nuove etichette non sono di spazio o tab, quindi non fanno partire l'adozione (REQ-WS-003).
- **Sicurezza e log.** Nessun nuovo permesso sul core (stesso confine di fiducia di `hc`); argomenti e risposte possono contenere percorsi e comandi, quindi il log registra solo il nome dell'azione e l'esito, mai gli argomenti.

Da aggiornare con la US:

- `docs/requirements/`: nuovo requisito REQ-AGT-003 "Comandi degli agenti all'app" (azioni supportate, risposta, comportamento ad app chiusa e con più dispositivi, nessuna azione distruttiva); in `README.md` la riga di scope sugli agenti ("… e chiedono all'app di aprire spazi, tab e pane o di mostrare un agente").
- `docs/design/integrations.md`: sezione "Comandi degli agenti" (etichette, claim, esecuzione, errori, ricetta); `docs/design/security.md`: confine di fiducia e log; `docs/design/README.md`: decisione aperta chiusa (fatto con questo spike).
- `README.md`: la ricetta `hda` per gli agenti.

US proposta: **"Comandi degli agenti verso l'app"** (EPIC-004, MOD-002 e MOD-001, P1, v0.1.0), criteri:

1. Un agente in un terminale di un core collegato esegue `space.new`, `tab.new`, `pane.split`, `space.select`, `tab.select` e `agent.focus` con la ricetta del README e riceve la risposta in stile `herdr-app do`; le altre azioni rispondono `unsupported`.
2. Con due dispositivi collegati allo stesso core ogni richiesta è eseguita una sola volta (claim su `herdr-desktop-android.claims`); una richiesta fatta mentre nessun dispositivo è collegato viene eseguita al collegamento se l'agente aspetta ancora, altrimenti l'agente scade e la toglie.
3. Le azioni passano dal documento del core come i comandi dell'utente: nessun terminale terminato, documento `herdr-app` invariato; logcat senza argomenti delle richieste.
4. Test unitari del parsing, del claim e dell'esecuzione; verifica sul Pixel 11 Pro in Desktop Mode con un herdr-core reale, remoto e nel Linux Terminal.

## Limits, costs and risks

- Misure di una sola rete (Tailscale tra il Pixel e la macchina remota) e di un solo giorno; con reti più lente la latenza cresce con i giri di rete (A: circa uno per la risposta, più uno per il claim).
- Il core non autentica chi scrive etichette e documenti: qualunque processo dell'utente sulla macchina può chiedere azioni all'app a nome di un altro terminale. È accettabile perché lo stesso processo può già avviare, terminare e scrivere nei terminali con `hc`, e il sottoinsieme non è distruttivo.
- Le etichette sono visibili a chi legge il terminale (`hc get`): non vanno messi segreti negli argomenti.
- Il documento dei claim cresce di una voce per terminale: l'app toglie le voci dei terminali rimossi quando lo riscrive.
- Una richiesta lasciata da un agente terminato senza toglierla verrebbe eseguita alla connessione successiva solo se il terminale è ancora vivo; con un terminale finito viene ignorata.
- La ricetta richiede `bash` (sostituzione di processo) e `timeout` (coreutils) sulla macchina; su macOS `timeout` può mancare: la US deve prevedere un ripiego (per esempio un ciclo su `hc get`).
- `hc` non ha comandi per i documenti: se herdr-core ne aggiungerà, B diventerà più semplice da usare per gli agenti.

## Recommendations

- Canale: etichette `herdr-desktop-android.do`/`.re` sul terminale dell'agente, con claim a compare-and-set su `herdr-desktop-android.claims`; da registrare in `docs/design/integrations.md` con la US (la decisione aperta in `docs/design/README.md` è chiusa da questo spike).
- Sottoinsieme v0.1.0: `space.new`, `tab.new`, `pane.split`, `space.select`, `tab.select`, `agent.focus`; nessuna azione distruttiva.
- Agente: ricetta shell con `hc label` e `hc events`, nel README; nessun programma da installare sulla macchina.
- Backlog: aggiungere su `main` la US "Comandi degli agenti verso l'app" (sezione 3) e il requisito REQ-AGT-003; decidere con l'utente se entra in v0.1.0 (la prima versione è quasi completa) o nella successiva.

## Sources

- `hc schema` e `hc --help` di herdr-core 0.1.20 (letti il 2026-11-27).
- https://github.com/herdrdev/herdr-app/blob/master/docs/commands.md — elenco delle azioni di `herdr-app do`.
- https://github.com/herdrdev/herdr-app/blob/master/defaults/AGENTS.md — risposte e regole delle azioni distruttive.
- Codice della prova: commit `6c0ee62` del branch `spike/SPIKE-004-comandi-agenti` (`AgentCommandsProbeE2ETest`, `assets/spike004_agent.py`), poi tolto dal branch.
