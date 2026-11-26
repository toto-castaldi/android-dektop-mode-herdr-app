# SPIKE-003 — Emulazione e rendering del terminale su Android

- **Date:** 2026-11-26
- **Outcome:** Positive — libghostty-vt 1.0.2 (MIT) compilato per `aarch64-linux-android` e usato via JNI con un renderer proprio in Compose disegna vim, htop e Claude Code attaccati a herdr-core sul Pixel 11 Pro in Desktop Mode, con latenza tasto-frame mediana di 26–30 ms e 58–60 fps su un display a 60 Hz durante output continuo.
- **Requirements:** REQ-NFR-003

## Questions

1. Quali opzioni esistono per emulare e disegnare i terminali e come si confrontano su licenza compatibile con Apache-2.0, fedeltà dell'emulazione (colori, Unicode, sequenze usate dagli agenti come Claude Code), integrazione con Compose, input da tastiera fisica e mouse, costo di build (NDK)?
2. Un prototipo sul Pixel 11 Pro disegna l'output di un programma TUI reale? Quali latenza tasto-eco e frame rate si misurano, e con quale metodo?
3. Quali soglie numeriche proporre per REQ-NFR-003?
4. Quale opzione scegliere (ADR)?

## Ambiente

| Elemento | Valore |
| --- | --- |
| Dispositivo | Pixel 11 Pro (`grizzly`), Android 17 (API 37), Desktop Mode su monitor esterno LG HDR WFHD 2560×1080 a 60 Hz via dock (alimentato), tastiera e mouse Logitech su ricevitore USB |
| Rete | Pixel in Wi-Fi (`172.27.73.30/21`), host walle via cavo sulla stessa LAN (`172.27.72.225`); `ping` dal Pixel: RTT medio 6,3 ms (min 3,5, max 9,6) |
| Core | herdr-core 0.1.20 su walle (x86_64); i programmi girano in terminali del core (`hc run --bg`) e il prototipo li raggiunge con SSH + `hc attach <t>` in una PTY |
| Prototipo | APK esplorativo in `tmp/spike-003-prototype/` (ignorato da git, non mergiato): Compose, build `release` non debuggable, ConnectBot sshlib 2.2.23, host key fissata per impronta SHA-256, chiave Ed25519 usa-e-getta; due backend intercambiabili dietro la stessa interfaccia |
| Finestra | ~2025×854 px, da 184×36 a 307×56 celle (font monospace di sistema, ~15 px) |
| Toolchain | Zig 0.17.1, NDK 29.0.14206865, CMake 3.22.1, AGP 9.5.3, Kotlin 2.4.21 |

## Findings

### 1. Opzioni a confronto

| Criterio | **libghostty-vt via JNI** + renderer Compose proprio | **Termux** `terminal-emulator` + `terminal-view` | **JediTerm** `jediterm-core` + renderer Compose proprio | **Emulatore e renderer propri** in Kotlin |
| --- | --- | --- | --- | --- |
| Licenza | MIT (Ghostty); dipendenze linkate MIT, Apache-2.0/MIT, Apache-2.0/BSD-3, Unicode-3.0 (`deps/libghostty-vt-third-party.json`): compatibile, serve `NOTICE` | Il repository termux-app è GPLv3, ma `LICENSE.md` esclude esplicitamente `terminal-emulator` e `terminal-view`, derivati da Terminal Emulator for Android (Apache-2.0): compatibile. La descrizione dell'item (GPLv3) vale solo per l'app Termux | doppia LGPLv3 / Apache-2.0 a scelta: compatibile scegliendo Apache-2.0 | nostra (Apache-2.0) |
| Fedeltà | lo stesso emulatore dell'app desktop Herdr (herdr-core stesso riporta `identity: "ghostty"` per i terminali). Truecolor, palette, stili (sottolineature multiple, overline, faint), Unicode con larghezze e grapheme cluster, scrollback, synchronized output (2026, callback `RENDER_HOLD`), encoder di tasti con kitty keyboard protocol e modalità del terminale, encoder del mouse (X10, SGR, ecc.), focus, bracketed paste, OSC 8/52, **OSC 7501 (Program Status Protocol)**, kitty graphics; risposte automatiche (DA, DSR, DECRQM) via callback `WRITE_PTY` | truecolor, 256 colori, stili base, mouse SGR 1006, focus 1004, bracketed paste 2004, OSC 52; niente synchronized output (2026), niente kitty keyboard; accetta i sottoparametri della sottolineatura (`4:3`) ma la disegna sempre semplice; tabella di larghezze Unicode propria (`WcWidth`) | VT100/xterm, synchronized output (`SynchronizedOutput.kt`), passa la maggior parte di vttest; nessun kitty keyboard; non provato su Android | tutto da scrivere e mantenere: parser, schermo, scrollback, larghezze Unicode, encoder di tasti e mouse |
| Integrazione con Compose | nessuna UI inclusa: API "render state" (righe sporche, celle con grapheme, colori risolti, cursore, selezione) da disegnare noi; nel prototipo ~200 righe di Kotlin con `drawIntoCanvas` | `TerminalRenderer` disegna su `android.graphics.Canvas` e si usa da `drawIntoCanvas`; `TerminalView` invece è una `View` legata a `TerminalSession` (classe `final` che avvia un processo locale via JNI), quindi per un core remoto si usa solo `TerminalEmulator` + `TerminalRenderer` | nessuna UI per Android (`ui/` è Swing): renderer da scrivere come per libghostty | da scrivere |
| Tastiera fisica e mouse | encoder di Ghostty dato un tasto fisico, modificatori e testo (mappatura `KeyEvent` → `GhosttyKey` nostra, ~40 righe); mouse con lo stesso schema | `KeyHandler.getCode` per i tasti speciali, testo con `getUnicodeChar`; mouse SGR | encoder propri di JediTerm, non provati | da scrivere |
| Costo di build | Zig 0.17.x (le altre minor rifiutate) + NDK r29 per `aarch64-linux-android`; build a freddo 4 min 9 s su 12 core (565 s di CPU, 1,3 GB di RAM), libreria statica 22 MB, `.so` JNI finale 2,0 MB nell'APK; più un `.c` JNI compilato da CMake nel Gradle. Android è piattaforma di tier 1 (compilata e testata nella CI di Ghostty) | nessuna toolchain extra: AAR su JitPack (`com.github.termux.termux-app:terminal-emulator:v0.118.3`), con un piccolo `libtermux.so` precompilato (10 KB, solo per i processi locali) | Java puro, ma pubblicato solo sul repository Maven di JetBrains (3.76), non su Maven Central; `TtyConnector` referenzia `java.awt.Dimension` (assente su Android) in metodi default | nessuna |
| Prestazioni misurate (§3) | parsing 106 ms/s di CPU a 4,5 MB/s di output colorato | parsing 741 ms/s di CPU a 3,8 MB/s: vicino al limite di un core | non misurato | non misurato |
| Maturità | C ABI stabile dalla 1.0 (`vt-1.0.0`, `vt-1.0.1`, `vt-1.0.2`, SONAME `libghostty-vt.so.1`); esiste già un componente Compose Multiplatform di terzi (composetty, Apache-2.0, alpha) che lo usa su Android | Termux è maturo, ma l'upstream dell'emulatore è inattivo e Termux pubblica solo su JitPack (ultima stabile v0.118.3, 22 maggio 2025) | maturo nell'IDE di JetBrains | — |

L'emulatore proprio è stato scartato senza prototipo: costa più di tutte le altre opzioni e darebbe la fedeltà più bassa, mentre il requisito del progetto è comportarsi come l'app desktop. JediTerm è stato valutato solo sui sorgenti: richiede comunque un renderer proprio come libghostty, ma con fedeltà inferiore e un emulatore diverso da quello di Herdr.

Fedeltà osservata nel prototipo (stessa schermata, schermate in `tmp/shots/`):

- **Claude Code** (v2.1.332, schermata iniziale e dialogo di conferma) e **vim**, **htop**: resi correttamente da entrambi i backend.
- Test di stili e Unicode (`tmp/uni.sh`): entrambi rendono grassetto, corsivo, sottolineato, barrato, faint, inverse, 16 colori, gradiente truecolor, CJK e hangul a doppia larghezza, box drawing, blocchi, braille, accenti combinanti. La sottolineatura ondulata (`4:3`) è disegnata come semplice da entrambi: il renderer del prototipo non distingue gli stili (libghostty li espone), quello di Termux non li disegna.
- Emoji composte (👍🏽, famiglia ZWJ, bandiera): nessuno dei due backend le disegna tutte bene. Con libghostty il prototipo le mostra spezzate nei singoli codepoint, probabilmente perché la modalità 2027 (grapheme cluster) non era attiva (si imposta con `GHOSTTY_TERMINAL_OPT_MODE`; non riprovato); Termux unisce alcune sequenze ma non la bandiera.
- Il glifo `⏵` usato da Claude Code manca nel font monospace di sistema con entrambi i backend: serve un font del terminale incluso nell'app o una catena di fallback (questione del renderer, non dell'emulatore).

### 2. Prototipo e metodo di misura

Il prototipo apre una sessione SSH verso walle con PTY `xterm-256color` e lancia `hc attach <t>`; i byte passano dunque da herdr-core come nell'app reale (che però userà `terminal.attach` con il protocollo framed). Un thread legge lo stream e lo passa al backend sotto un lock; il disegno avviene in `Modifier.drawBehind` su un `Canvas` Android (`drawIntoCanvas`) e viene richiesto **una volta per vsync** da un ciclo `withFrameNanos` solo se sono arrivati byte.

Latenza tasto-eco (clock `CLOCK_MONOTONIC`, lo stesso di `KeyEvent.getEventTime()` e `System.nanoTime()`):

- `t0` = `KeyEvent.getEventTime()` del tasto (risoluzione 1 ms; per una tastiera fisica è il timestamp del kernel);
- `key→rx` = primo blocco di byte ricevuto dopo l'invio del tasto (rete, sshd, `hc attach`, core, programma);
- `key→draw` = fine del disegno del frame che contiene quei byte;
- `key→frame` = `INTENDED_VSYNC_TIMESTAMP + TOTAL_DURATION` del `FrameMetrics` di quel frame, cioè il frame completato dalla GPU e pronto per il compositor (esclusi la composizione e la scansione del monitor, ≤ 1 refresh = 16,7 ms a 60 Hz);
- programma: vim in modalità insert, tasti alternati `x` e Backspace ogni 250 ms (300 per backend), più una serie con tasti iniettati da `adb shell input -d 4 keyevent` ogni 150 ms (120 per backend), che passano dall'InputDispatcher di Android come una tastiera.

Frame rate durante output continuo: conteggio dei frame disegnati in 15 s mentre il programma scrive senza sosta, più durata dei frame da `FrameMetrics` e CPU spesa nel parsing e nel disegno.

- Output colorato: `cat` in ciclo di un file di 6,4 MB con 20 000 righe, ognuna con 10 parole in truecolor e stili casuali, CJK, emoji, box drawing e blocchi (`tmp/flood.txt`).
- Output ASCII: `seq`.

Il prototipo registra in logcat (tag `SPK003`) solo tempi e contatori, mai il contenuto dei terminali né chiavi.

### 3. Risultati

Latenza tasto-eco, ms (p50 / p95 / p99 / max):

| Percorso | Backend | Input | n | key→rx | key→draw | key→frame |
| --- | --- | --- | --- | --- | --- | --- |
| SSH + `hc attach` | libghostty | sintetico | 300 | 13,1 / 17,1 / 38,4 / 106 | 24,3 / 33,1 / 56,0 / 118 | 26,5 / 35,3 / 57,9 / 120 |
| SSH + `hc attach` | Termux | sintetico | 298 | 13,3 / 19,1 / 37,6 / 639 | 24,7 / 33,3 / 55,1 / 651 | 26,8 / 35,5 / 57,0 / 654 |
| SSH + `hc attach` | libghostty | iniettato | 120 | 16,8 / 20,6 / 25,7 / 34 | 27,7 / 36,7 / 40,9 / 46 | 30,4 / 39,0 / 43,4 / 49 |
| SSH + `hc attach` | Termux | iniettato | 120 | 17,6 / 21,0 / 25,4 / 34 | 29,1 / 37,5 / 40,8 / 48 | 31,2 / 40,0 / 43,4 / 50 |
| SSH diretto (vim senza core) | libghostty | sintetico | 200 | 11,9 / 21,2 / 30,9 / 34 | 23,1 / 33,2 / 45,1 / 51 | 25,2 / 35,5 / 47,8 / 53 |

- La latenza è dominata da rete e attesa del vsync: tra ricezione e frame completato passano in media ~13 ms (mezzo refresh più ~3–5 ms di frame); i due emulatori sono equivalenti. Il core aggiunge ~1 ms rispetto a SSH diretto. I picchi isolati (120 ms, 654 ms) cadono sulla rete Wi-Fi (`key→rx`), non sul rendering.
- Tastiera fisica: non misurata a mano in questo spike (le prove sono automatiche). Il metodo vale anche per essa senza modifiche, perché `getEventTime()` porta il timestamp del kernel; il ricevitore USB aggiunge al massimo il suo intervallo di polling.

Output continuo, display a 60 Hz:

| Percorso e output | Backend | fps | MB/s | frame p50 / p95 / max (ms) | CPU parsing / disegno (ms per s) |
| --- | --- | --- | --- | --- | --- |
| SSH diretto, colorato + Unicode | libghostty | 59,7 | 4,48 | 19,8 / 26,6 / 33,4 | 106 / 128 |
| SSH diretto, colorato + Unicode | Termux | 56,7 | 3,80 | 37,6 / 53,0 / 61,6 | 741 / 203 (+167 di attesa sul lock) |
| SSH + `hc attach`, colorato + Unicode | libghostty | 58,5 | 4,23 | 41,3 / 49,6 / 60,7 | 145 / 143 |
| SSH diretto, ASCII (`seq`) | libghostty | 59,6 | 4,50 | 6,0 / 9,5 / 12,4 | 154 / 120 |
| SSH + `hc attach`, ASCII (`seq`) | libghostty | 39,7 | 2,83 | 5,9 / 8,8 / 13,5 | 95 / 80 |

- Con libghostty il frame rate resta al refresh del display anche con output colorato: il disegno è pipelinato (durata del frame fino a ~40 ms, ma uno ogni vsync). Il collo di bottiglia del prototipo è il disegno del testo (una chiamata `drawText` per gruppo di celle con lo stesso stile), non l'emulatore; un renderer che ridisegna solo le righe sporche lo riduce.
- Con Termux il parsing in Java occupa ~74% di un core a 3,8 MB/s e blocca il disegno sul lock: il margine sull'output dei programmi è molto più basso.
- Con `hc attach` e output ASCII il core consegna i byte a ~40 aggiornamenti al secondo: il limite è del core (o di `hc attach`), non dell'app. Da riverificare con `terminal.attach` nell'app reale.
- Una prima versione del prototipo chiedeva un ridisegno a ogni blocco letto da un thread in background (scrittura di uno stato Compose fuori dal thread UI) e scendeva a 4–8 fps: il ridisegno va legato al vsync (`withFrameNanos` o `Choreographer`).

### 4. Scelta

libghostty-vt via JNI con un renderer proprio in Compose: stesso emulatore dell'app desktop e di herdr-core, la fedeltà più alta (kitty keyboard, synchronized output, OSC 7501, grapheme), ABI C stabile, licenza MIT e il costo di CPU più basso. Il prezzo è una toolchain di build in più (Zig e NDK) e un renderer da scrivere. Decisione in [ADR-001](../adr/ADR-001-emulatore-terminale.md).

## Limits, costs and risks

- **Permesso di rete locale (Android 17, targetSdk 37).** Senza il permesso runtime `android.permission.ACCESS_LOCAL_NETWORK` le connessioni TCP dell'app verso indirizzi della LAN restano appese senza errore (nessun pacchetto parte); con il permesso concesso la sessione SSH si apre subito. Riguarda ogni collegamento a un core sulla rete locale (US-001, US-002), non solo il terminale. Nel prototipo il permesso è stato concesso con `pm grant`; l'app deve dichiararlo e chiederlo all'utente. Non verificato per indirizzi Tailscale (`100.64.0.0/10`) né per il loopback del Linux Terminal.
- **Toolchain:** libghostty-vt 1.0.x accetta solo Zig 0.17.x; ogni aggiornamento della libreria può cambiare la versione di Zig richiesta. La build va resa riproducibile (versioni e checksum fissati) e cambia la preparazione del quality gate (`assembleDebug` richiederà Zig e NDK).
- **Renderer da scrivere:** font del terminale con copertura dei simboli usati dagli agenti, grapheme cluster (modalità 2027), stili di sottolineatura, selezione, ridisegno delle sole righe sporche, cursore: lavoro dell'item che implementa il terminale.
- **JNI:** errori nel codice nativo fanno cadere l'app; il codice JNI va tenuto piccolo (poche chiamate grosse: scrittura, snapshot, codifica di un tasto) e testato con test strumentali.
- **Misure:** una sola rete (Wi-Fi domestico con RTT ~6 ms), un solo monitor a 60 Hz, tasti automatici; `key→frame` esclude composizione e scansione del monitor. La latenza della tastiera fisica e quella sullo schermo integrato a 120 Hz non sono state misurate.
- **Termux su JitPack:** se servisse il ripiego, le build dipendono da JitPack (ultima stabile del 22 maggio 2025).

## Recommendations

- Registrare la scelta in ADR-001 e in `design/README.md`; descrivere in `design/modules.md` che `terminal` contiene il binding JNI di libghostty-vt.
- Scrivere in REQ-NFR-003 le soglie (vedi sotto) e il metodo di misura del §2.
- Soglie proposte per REQ-NFR-003, con il core sulla stessa rete locale e almeno 200 tasti:
  - latenza tasto-eco, dal timestamp dell'evento di tasto al frame completato che mostra l'eco: p50 ≤ 40 ms, p95 ≤ 60 ms, p99 ≤ 100 ms (misurato: p50 26–31, p95 35–40, p99 43–58);
  - output continuo per 15 s di testo truecolor con Unicode, in una finestra a tutta larghezza del monitor: frame rate medio ≥ 50 fps su un display a 60 Hz (o ≥ 83% della frequenza del display) e nessun frame oltre 100 ms (misurato: 58,5–59,7 fps, frame massimo 61 ms).
- Item di follow-up da aggiungere al backlog:
  - un Task per la build di libghostty-vt nel Gradle del modulo `terminal` (versione di Zig, sorgenti di libghostty-vt e NDK fissati con checksum, JNI in CMake, `NOTICE` con le licenze delle dipendenze linkate) e per aggiornare la preparazione del quality gate;
  - la gestione di `ACCESS_LOCAL_NETWORK` nel collegamento a una macchina (dichiarazione, richiesta, messaggio quando è negato), da aggiungere a US-001 o a un item proprio, e al design di sicurezza.

## Sources

- termux-app `LICENSE.md` (eccezioni per `terminal-emulator` e `terminal-view`): https://github.com/termux/termux-app/blob/master/LICENSE.md
- Termux su JitPack: https://jitpack.io/p/termux/termux-app
- Ghostty e libghostty-vt (README, `PACKAGING-LIBGHOSTTY.md`, `include/ghostty/vt.h`, `deps/libghostty-vt-third-party.json`), tag `vt-1.0.2`: https://github.com/ghostty-org/ghostty
- "Libghostty is coming" (22 settembre 2025): https://mitchellh.com/writing/libghostty-is-coming
- composetty: https://github.com/befrvnk/composetty
- JediTerm: https://github.com/JetBrains/jediterm
- `FrameMetrics`: https://developer.android.com/reference/android/view/FrameMetrics
