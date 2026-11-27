# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows [Semantic Versioning](https://semver.org/). Entries are written in the project language and reference the backlog item ID. Each section is generated from the backlog when the version closes (`backlog changelog`); `[Unreleased]` stays empty, and `backlog changelog` shows the open version.

## [Unreleased]

## [0.1.0] - 2026-11-27

### Added

- macchine con herdr-core via SSH: chiave nel Keystore o password, conferma della host key, stato e riconnessione automatica (US-001)
- macchina 'Questo dispositivo' per il herdr-core nel Linux Terminal, con guida alla configurazione e agli stati del Terminal (US-002)
- l'app propone di installare o aggiornare herdr-core quando manca o è incompatibile e lo fa dopo conferma, mostrando l'output (US-003)
- spazi e tab per macchina collegata (crea, rinomina, riordina, chiudi), ripristinati dal core alla riapertura (US-004)
- pane con terminali interattivi del core: dividi, ridimensiona, chiudi, focus, tastiera fisica, colori, Unicode e scrollback (US-005)
- sidebar degli agenti di tutte le macchine collegate con stati Working, Blocked, Done e Idle aggiornati in tempo reale (US-006)
- peek degli agenti senza pane sopra la tab corrente, con risposta interattiva e azione "Tieni come tab" (US-007)
- più finestre dell'app in Desktop Mode, ognuna sul proprio spazio, ridimensionabili e spostabili tra display senza perdere lo stato (US-008)
- uso completo da tastiera fisica, con scorciatoie Ctrl+Shift per spazi, tab, pane, sidebar e un pannello di aiuto (US-009)
- mouse nel terminale: selezione, copia e incolla, menu contestuale con le azioni del pane e inoltro del mouse ai programmi (US-010)
- l'app segnala quando per un herdr-core compatibile esiste una versione più recente e lo aggiorna dopo conferma (US-011)
- i terminali avviati dagli agenti con le etichette di uno spazio dell'app compaiono in quello spazio, in un pane o in una nuova tab (US-013)
- gli agenti sul core possono chiedere all'app di aprire spazi, tab e pane o di mostrare un agente, come herdr-app do (US-015)
- documentazione di base del progetto (requisiti, design, backlog della v0.1.0) (TEC-001)
- prima app minima che si apre come finestra ridimensionabile in Android Desktop Mode (TEC-002)
- il testo della licenza Apache-2.0 è nel repository e nell'APK, accanto a NOTICE (TEC-005)
- build dogfood firmata e ottimizzata, installabile accanto alla build di sviluppo con nome e icona propri (TEC-008)

### Changed

- spazi, tab e pane sono condivisi con l'app desktop Herdr collegata allo stesso core; gli spazi salvati prima passano lì (US-012)
- spazi, tab e pane dell'app sono separati da quelli dell'app desktop Herdr sullo stesso core; gli spazi dell'app tornano nell'app (US-014)

### Fixed

- le macchine della rete locale chiedono il permesso di accesso alla rete locale invece di restare appese (BUG-001)
- con più finestre sulla stessa macchina un agente mostrato in una qualsiasi finestra in primo piano viene segnato come visto (BUG-002)
- nel terminale prompt ed eco già visti compaiono subito invece di restare vuoti fino all'output successivo (BUG-003)
