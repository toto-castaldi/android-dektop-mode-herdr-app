# Integrations

## herdr-core

- Ogni core espone un'API descritta in OpenRPC (`hc schema`), con flussi ed eventi per gli aggiornamenti in tempo reale; gli agenti riportano lo stato con il Program Status Protocol (OSC 7501).
- L'app raggiunge ogni core via SSH, sia remoto sia nel Linux Terminal. Trasporto esatto, metodi usati, gestione degli eventi e controllo della compatibilità di versione sono decisioni aperte (vedi `README.md`, Open decisions).
- L'installazione e l'aggiornamento del core usano gli installer ufficiali di Herdr, eseguiti sulla macchina via SSH solo dopo la conferma dell'utente.

## Gestione dei guasti

- Una connessione caduta porta la macchina nello stato "non raggiungibile" e l'app ritenta il collegamento; terminali e agenti continuano a girare sul core.
- Un core assente o con versione incompatibile non viene usato: l'app lo segnala e propone installazione o aggiornamento.
