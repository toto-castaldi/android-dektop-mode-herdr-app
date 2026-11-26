# Release acceptance — v0.1.0

La versione si può chiudere solo quando, sul dispositivo di riferimento (Pixel 11 Pro) in Desktop Mode con monitor esterno, tastiera e mouse:

- l'utente aggiunge una macchina Linux remota, genera una chiave nell'app, la autorizza sull'host, conferma l'impronta della host key e si collega al suo herdr-core;
- su una macchina remota senza herdr-core, o con una versione incompatibile, l'app lo installa o lo aggiorna dopo conferma e si collega;
- l'app si collega al herdr-core del Linux Terminal come "Questo dispositivo";
- l'utente crea uno spazio con due tab, divide una tab in due pane e in un pane avvia Claude Code, con colori, Unicode e scrollback corretti;
- un agente che avvia un worker con `hc run` mostra entrambi nella sidebar con stati che si aggiornano; il worker in Blocked si apre in peek, l'utente risponde e lo stato cambia;
- chiusa e riaperta l'app, spazi, tab e terminali sono ripristinati e gli agenti hanno continuato a lavorare;
- l'utente usa l'app solo con tastiera e mouse: scorciatoie per spazi, tab e pane, combinazioni Ctrl e Alt nel terminale, selezione, copia e incolla, rotella, menu contestuale;
- le soglie di reattività di REQ-NFR-003 sono rispettate;
- il quality gate passa da un clone pulito e i log non contengono chiavi, password o passphrase.
