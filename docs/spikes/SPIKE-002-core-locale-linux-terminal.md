# SPIKE-002 — herdr-core locale nel Linux Terminal di Android

- **Date:** 2026-11-26
- **Outcome:** Partial — herdr-core 0.1.22 (linux-aarch64) si installa, si aggiorna e gira nella VM Debian del Linux Terminal del Pixel 11 Pro, e un'app lo raggiunge su `127.0.0.1:2222` tramite il port forwarding del Terminal; ma la VM vive solo finché l'app Terminal è aperta, anche in background, e su questa build il port forwarding non torna se la VM viene riavviata senza riavviare il processo del Terminal.
- **Requirements:** REQ-CONN-002, REQ-CONN-003, REQ-NFR-001

## Questions

1. herdr-core si installa e si avvia nella VM del Linux Terminal del Pixel 11 Pro (versioni di Android e di herdr-core)?
2. Quanto resta attivo con l'app Terminal chiusa o in background?
3. Da quale indirizzo e porta un'app Android lo raggiunge?
4. Come rileva l'app che il core manca o è incompatibile, e come lo installa o aggiorna, nella VM e su un host Linux remoto (comandi provati ed esito)?
5. Quali limiti e rischi (permessi, VM non avviata, batteria) e come l'app li segnala all'utente?

## Ambiente

| Elemento | Valore |
| --- | --- |
| Dispositivo | Pixel 11 Pro (`grizzly`), Desktop Mode su monitor esterno via dock, alimentato dal dock |
| Android | 17 (API 37), build `CD1A.261005.003`, patch di sicurezza 2026-10-05 |
| App Terminal | `com.android.virtualization.terminal`, versionName 17 |
| VM | Debian 13.7 arm64, kernel `6.12.93-android16`, 7 vCPU, 7,9 GB di RAM, disco 210 GB; utente `droid` con `sudo` senza password |
| herdr-core | 0.1.22 nella VM (già installato in `~/.local/bin`), generation 1 |
| Host remoti | crilin (Ubuntu 24.04, x86_64, herdr-core 0.1.22); walle, questo PC (x86_64, herdr-core 0.1.20) |
| Accesso alle prove | adb via Tailscale; ssh verso la VM con `adb forward tcp:22222 tcp:2222` (cioè passando da `127.0.0.1:2222` di Android) oppure con `ProxyCommand adb shell -T nc 10.211.207.222 2222`; host key della VM verificata contro quella già confermata (`HostKeyAlias pixel-vm`, ECDSA `SHA256:GTf1z+E7w2ikPyeWjYICOV+E/u6T8Bo7pKtYojg0W34`) |

## Findings

### 1. Installazione e avvio nella VM

- La VM ha `curl` e `tar`; l'installer ufficiale riconosce `aarch64` e scarica `herdr-core-linux-aarch64`. Prova in un HOME e in una sessione isolati (per non toccare il core reale della VM):

  ```sh
  d=$HOME/spk002; E="env -i HOME=$d XDG_RUNTIME_DIR=/run/user/1000 HC_SESSION=spk002 PATH=$d/.local/bin:/usr/bin:/bin"
  curl -fsSL https://releases.herdr.dev/core/install.sh | $E HC_VERSION=0.1.20 sh
  #   hc 0.1.20 installed into /home/droid/spk002/.local/bin/herdr-core.   (exit 0)
  $E herdr-core run --bg sleep 100000        # t1 live
  $E herdr-core update
  #   {"now":"hc 0.1.22","release":"0.1.22","restarted":true,"was":"hc 0.1.20"}   (exit 0)
  $E herdr-core ls                           # t1 ancora live dopo l'aggiornamento
  ```

- Sul core reale della VM `hc update` risponde `{"now":"hc 0.1.22","release":"0.1.22","updated":false}`.
- Il core **non parte al boot della VM**: lo avvia la prima chiamata (`hc …` o `hc connect`), come su Linux in generale. Gira come unità utente transitoria (`hc-serve-*.service`, `systemd-run`) e `loginctl` riporta `Linger=yes`; `hello` lo dichiara con `keeps_running_after_logout: true`, `logout_linger_user: "droid"`. Chiudere la sessione SSH quindi non lo ferma.
- `hello {generation: 1}` sulla riga remota di `integrations.md`, passando da `127.0.0.1:2222`, risponde con `version: "0.1.22"`, `generation: 1`, le stesse `features` dei core remoti.
- Il `PATH` delle sessioni SSH non interattive della VM è `/usr/local/bin:/usr/bin:/bin:/usr/games`, senza `~/.local/bin`: il ripiego su `$HOME/.local/bin/herdr-core` della riga remota serve anche qui.

### 2. Durata con il Terminal in background o chiuso

- La VM (`crosvm_debian`) è tenuta in vita da `VmService`, foreground service dell'app Terminal con notifica permanente (`vm_service_channel`, `NO_CLEAR`).
- **In background** (tasto Home sul telefono; poi finestra minimizzata in Desktop Mode): la VM e un terminale di prova che scrive l'ora ogni 10 s restano vivi; sessione SSH e core rispondono. Verificato per 3 minuti e poi per 10 minuti consecutivi (61 righe su 61 attese, nessun buco), con il telefono in carica sul dock e schermo acceso (`deviceidle` `ACTIVE`, vedi la sezione sui rischi per la batteria).
- **Chiusa** (task tolto dai recenti, oppure X della finestra in Desktop Mode): la VM si spegne entro pochi secondi (`crosvm_debian` zombie, poi sparisce), `127.0.0.1:2222` rifiuta la connessione. Riaprendo il Terminal la VM riparte da zero (~30 s): i terminali del core risultano `finished` con `reason: "stopped_by_restart"`; un terminale *kept* torna con una shell nuova. Quindi, per il core locale, **chiudere l'app Terminal equivale a spegnere la macchina**.
- Nessun componente esportato permette a un'altra app di avviare la VM senza interfaccia: le sole entrate sono le activity del launcher (`LauncherActivity`). L'app può solo aprire il Terminal con il suo intent di avvio.

### 3. Indirizzo e porta

- La VM ha l'IP `10.211.207.222` sulla rete `avf_tap_fixed` (`10.211.207.0/24`, Android `10.211.207.130`). Dalla shell adb sshd risponde lì, ma con l'UID di un'app (`run-as` sull'APK di debug dell'app) la connessione a `10.211.207.222:2222` non risponde: le regole di routing per UID (`ip rule`, per esempio `uidrange 0-0 lookup` su `avf_tap_fixed`) non la rendono raggiungibile alle app comuni. **L'IP della VM non è un indirizzo utilizzabile.**
- L'app Terminal inoltra su Android le porte in ascolto nella VM che l'utente accetta (notifica "Nuova porta aperta… Accetta / Nega", elenco in *Impostazioni → Controllo porte*). La porta accettata ascolta su `127.0.0.1` e `::1` di Android. Con l'UID dell'app `127.0.0.1:2222` risponde con il banner di sshd. **L'indirizzo è `127.0.0.1:2222`** (porta dell'sshd della VM).
- Il passaggio dalla rete `avf_tap_fixed` al loopback è fatto dall'agente guest `linux_vm_manager` (`forwarder_guest_launcher`, porte rilevate con `tcpstates-libbpf`) e dall'app Terminal lato Android; `adb forward` verso quella porta funziona come per ogni altro servizio su loopback.
- sshd non è parte del core né del Terminal: nella VM era già configurato a mano (`openssh-server` 10.0, `/etc/ssh/sshd_config.d/port.conf` con `Port 2222`, `PasswordAuthentication no`, chiave pubblica in `~/.ssh/authorized_keys`). Su Android `ip_unprivileged_port_start` vale 1024, quindi una porta sotto 1024 (22) non si può inoltrare sul loopback: serve una porta alta (non provato con la 22).
- Le host key di sshd restano le stesse tra un riavvio e l'altro della VM; cambiano solo con la reimpostazione della VM dall'app Terminal, che cancella tutto (pacchetti, configurazione di sshd, `authorized_keys`, core e terminali).
- **Port forwarding perso dopo un riavvio della VM nello stesso processo.** Chiudendo il Terminal (dai recenti o con la X) e riaprendolo, la VM riparte e l'agente guest segnala di nuovo la porta (`Port 2222 is already opened by "sshd"` nel journal di `linux_vm_manager`), la porta risulta abilitata in *Controllo porte*, ma su Android `127.0.0.1:2222` resta chiusa (anche dopo più di un minuto; riprodotto due volte). Dopo `am force-stop com.android.virtualization.terminal` e un nuovo avvio la porta torna dopo 5 s. Il processo dell'app Terminal sopravvive alla chiusura della finestra, perciò il caso è quello normale, non un'eccezione.
- Limite del test: `run-as` non è la sandbox completa di un'app (l'APK provato non dichiara `INTERNET` e `run-as` ha comunque il gruppo `inet`). Il comportamento del loopback va confermato con l'app vera in US-002; quello dell'IP della VM (rifiutato per UID) non dipende da questo.

### 4. Core mancante o incompatibile: rilevamento, installazione, aggiornamento

Il rilevamento è lo stesso per VM e host remoti e usa quanto deciso in SPIKE-001:

- **Core mancante:** la riga remota di `integrations.md` termina con exit status 127 e `herdr-core not found` su stderr. Provato su crilin e nella VM forzando `HOME=/nonexistent` (exit 127 in entrambi).
- **Core incompatibile:** risposta a `hello` (`version`, `generation`, `methods`) confrontata con i minimi dell'app; `hello` con generation sconosciuta fallisce con `kind: unsupported_generation`.
- **Prerequisiti dell'installer**, da controllare prima di proporlo, con un solo comando via SSH: `uname -s` (Linux o Darwin), `uname -m` (`x86_64`/`amd64`, `arm64`/`aarch64`), `command -v curl tar`. L'installer si ferma con `hc: needs curl`, `hc runs on Linux and macOS for now`, `no build for …`; installa nella home dell'utente (`~/.local/bin`), senza root.
- **Installazione** (dopo conferma): `curl -fsSL https://releases.herdr.dev/core/install.sh | sh` sul canale `exec`. Esito "installed" su stdout; un'installazione nuova non avvia il core (lo avvia il primo `hc connect`).
- **Aggiornamento** (dopo conferma): `~/.local/bin/herdr-core update` (o `hc update`), output JSON con `was`/`now`/`updated`/`restarted`; in alternativa lo stesso installer, che su un core esistente fa `update --from`. In entrambi i casi i terminali in corso sopravvivono.

Comandi provati:

| Macchina | Comando | Esito |
| --- | --- | --- |
| VM (isolata) | installer con `HC_VERSION=0.1.20` | installato 0.1.20, exit 0 |
| VM (isolata) | `herdr-core update` da 0.1.20 con un terminale attivo | 0.1.22, `restarted: true`, terminale ancora `live` |
| VM (core reale) | `hc update` | `updated: false` (già 0.1.22) |
| crilin, via SSH non interattivo | `curl … install.sh \| HOME=$d HC_INSTALL_DIR=$d/.local/bin HC_VERSION=0.1.21 sh` in una cartella temporanea | installato 0.1.21, exit 0 |
| crilin | riga remota con `HOME=/nonexistent` | exit 127, `herdr-core not found` |
| walle (isolato con `HOME` e `HC_SESSION`) | installer con `HC_VERSION=0.1.20`, poi installer senza versione con un terminale attivo | `hc updated to 0.1.22.`, terminale ancora `live` |
| walle (isolato) | installazione 0.1.20, poi `herdr-core update` con un terminale attivo | `was 0.1.20 → now 0.1.22`, `restarted: true`, terminale ancora `live` |

Nota: le prove isolate richiedono una sessione `HC_SESSION` dedicata; con `XDG_RUNTIME_DIR` in una cartella profonda il socket supera il limite di 107 byte (`the socket path … is too long`).

### 5. Limiti, rischi e segnalazioni all'utente

Riportati nella sezione seguente, con la segnalazione proposta per ogni caso.

## Limits, costs and risks

| Caso | Cosa vede l'app | Come lo segnala (proposta) |
| --- | --- | --- |
| Linux Terminal non installato o mai configurato (VM non creata) | pacchetto `com.android.virtualization.terminal` assente o disattivato; nessuna porta | "Linux Terminal non disponibile": istruzioni per attivarlo nelle Opzioni sviluppatore e completare il primo avvio |
| VM non avviata (Terminal chiuso) | `127.0.0.1:2222` rifiuta la connessione | stato "Linux Terminal non attivo" con pulsante "Apri Linux Terminal" (intent di avvio del pacchetto), poi nuovo tentativo; avviso che chiudere il Terminal spegne la VM e chiude i terminali |
| sshd non configurato nella VM o porta non accettata | connessione rifiutata con il Terminal attivo | "Linux Terminal non configurato": un solo comando da incollare nel Terminal (installa e configura sshd sulla 2222, aggiunge la chiave pubblica dell'app) e l'invito ad accettare la porta nella notifica del Terminal |
| Port forwarding perso dopo un riavvio della VM | come sopra, con il Terminal aperto | nello stesso messaggio: "chiudi il Terminal da Impostazioni → App → Terminale → Arresto forzato e riaprilo" |
| Chiave dell'app non autorizzata | autenticazione rifiutata | come "non configurato", con il comando che aggiunge la chiave |
| VM reimpostata | host key cambiata | rifiuto del collegamento come per ogni host key cambiata (`security.md`); l'utente conferma la nuova impronta solo dopo aver reimpostato la VM di proposito |
| Core assente o incompatibile | exit 127, oppure `hello` | stesso flusso delle macchine remote (REQ-CONN-003) |
| Batteria e risorse | a riposo la VM usa ~1,0–1,1 GB di RAM residente e circa il 9% di un core (`top`; 92 s di CPU in ~10 minuti, boot compreso); il Terminal tiene un foreground service | non segnalato come errore; nota nella schermata della macchina locale |

- **Batteria:** non misurata in modo affidabile. Tutte le prove sono state fatte con il telefono alimentato dal dock, quindi senza Doze; non è verificato se Android ferma la VM in background a batteria o dopo molte ore. Da osservare in US-002 e nell'uso reale.
- **Durata:** la VM in background è stata verificata per 10 minuti consecutivi, non per ore.
- **Permessi:** all'app basta `INTERNET` per il loopback (da confermare con l'app vera, vedi §3); nessun permesso speciale è disponibile o necessario per controllare la VM (`MANAGE_VIRTUAL_MACHINE` e `USE_CUSTOM_VIRTUAL_MACHINE` sono permessi di sistema).
- **Configurazione iniziale fuori dal controllo dell'app:** senza sshd nella VM l'app non ha alcun canale per entrarci; il primo passo resta un comando incollato dall'utente nel Terminal. Il comando proposto (non rieseguito in questo spike perché la VM era già configurata; equivale ai passi manuali che hanno prodotto la configurazione trovata):

  ```sh
  sudo apt-get install -y openssh-server && echo 'Port 2222' | sudo tee /etc/ssh/sshd_config.d/port.conf && sudo systemctl restart ssh && mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '<chiave pubblica dell'"'"'app>' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
  ```

- **Build del Terminal:** il bug del port forwarding è stato osservato su Android 17 `CD1A.261005.003`; può cambiare con gli aggiornamenti di sistema.

## Recommendations

- Registrare in `design/integrations.md` il core locale: macchina "Questo dispositivo" = `droid@127.0.0.1:2222`, stesso trasporto, verifica della host key e gestione delle chiavi delle macchine remote; rilevamento dello stato (VM non attiva / non configurata / core assente) e messaggi della tabella precedente.
- Installazione e aggiornamento: stesso flusso per VM e host remoti, con controllo dei prerequisiti prima di proporre l'installer.
- US-002 deve confermare con l'app vera la raggiungibilità di `127.0.0.1:2222` e osservare la VM a batteria; nessun nuovo item necessario.

## Sources

- Installer ufficiale: <https://releases.herdr.dev/core/install.sh> e manifest <https://releases.herdr.dev/core/latest.json> (0.1.22, letti il 2026-11-26).
- `docs/spikes/SPIKE-001-api-trasporto-herdr-core.md` per `hello`, riga remota e compatibilità.
- Osservazioni sul Pixel 11 Pro via adb (`dumpsys`, `ip rule`, `am stack list`, journal di `linux_vm_manager` nella VM) del 2026-11-26.
