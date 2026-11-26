# ADR-001 — Emulatore e renderer del terminale

- **State:** Accepted
- **Date:** 2026-11-26
- **Item:** SPIKE-003
- **Requirements:** REQ-NFR-003

## Context

L'app deve mostrare i terminali di herdr-core comportandosi come l'app desktop Herdr, che usa l'emulatore di Ghostty, e deve reggere gli agenti di codice (Claude Code e simili) che usano truecolor, Unicode, kitty keyboard protocol, synchronized output e il Program Status Protocol (OSC 7501). Il modulo `terminal` riceve byte e dimensioni da `core-client` e disegna in Compose, con input da tastiera fisica e mouse in Desktop Mode. Ogni dipendenza inclusa deve avere una licenza compatibile con Apache-2.0. Le misure sono nel report [SPIKE-003](../spikes/SPIKE-003-rendering-terminale.md).

## Options considered

1. **libghostty-vt via JNI, renderer proprio in Compose** — stesso emulatore dell'app desktop, la fedeltà più alta (kitty keyboard, synchronized output, OSC 7501, grapheme, encoder di tasti e mouse), ABI C stabile dalla 1.0, MIT; parsing ~7 volte meno costoso di Termux nel prototipo. Contro: richiede Zig 0.17.x e NDK nella build, codice JNI e un renderer da scrivere.
2. **Termux `terminal-emulator` + `TerminalRenderer`** — Apache-2.0 (eccezione esplicita nella licenza di termux-app), Java puro, renderer su Canvas già pronto, nessuna toolchain extra. Contro: niente kitty keyboard né synchronized output, emulatore diverso da quello di Herdr, parsing vicino al limite di un core con output colorato, `TerminalView` legata ai processi locali, pubblicato solo su JitPack con upstream inattivo.
3. **JediTerm (`jediterm-core`) + renderer proprio** — LGPLv3 o Apache-2.0 a scelta, Java puro con synchronized output. Contro: renderer da scrivere come per libghostty ma con fedeltà inferiore, niente kitty keyboard, pubblicato solo sul repository Maven di JetBrains, riferimenti ad AWT nelle interfacce; non provato su Android.
4. **Emulatore e renderer propri in Kotlin** — nessuna dipendenza. Contro: il costo più alto e la fedeltà più bassa.

## Decision

Opzione 1: libghostty-vt (serie 1.x, versione fissata) compilato per `arm64-v8a` e collegato staticamente in una libreria JNI del modulo `terminal`, con un'API JNI a grana grossa (scrittura dei byte con le risposte automatiche, snapshot dello schermo per frame, codifica di tasti e mouse con le modalità correnti). Il renderer è nostro: disegna lo snapshot su un `Canvas` in Compose, ridisegna al più una volta per vsync e solo quando ci sono byte nuovi, e include un font del terminale con la copertura dei simboli usati dagli agenti. Ripiego, se la build nativa diventasse un ostacolo: opzione 2 dietro la stessa interfaccia del modulo `terminal`.

## Consequences

- La fedeltà del terminale è quella dell'app desktop, comprese le sequenze degli agenti e l'OSC 7501 che il core già interpreta.
- La build dell'APK richiede Zig 0.17.x e l'NDK, con versioni e checksum fissati, e la preparazione del quality gate cambia di conseguenza; serve un `NOTICE` con le licenze di libghostty-vt e delle dipendenze linkate (MIT, Apache-2.0/MIT, Apache-2.0/BSD-3, Unicode-3.0).
- Aggiornare libghostty-vt è una scelta esplicita (nuova versione e verifica della versione di Zig richiesta).
- Il codice nativo può far cadere l'app: va tenuto piccolo e coperto da test strumentali.
- Follow-up: un Task per la build di libghostty-vt nel Gradle e il binding JNI; il renderer, la selezione e l'input nell'item che implementa il terminale.
