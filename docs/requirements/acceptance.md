# Release acceptance — v0.1.0

La versione si può chiudere solo quando, sul dispositivo di riferimento (Pixel 11 Pro) in Desktop Mode con monitor esterno, tastiera e mouse:

- l'utente aggiunge una macchina Linux remota, genera una chiave nell'app, la autorizza sull'host, conferma l'impronta della host key e si collega al suo herdr-core;
- su una macchina remota senza herdr-core, o con una versione incompatibile, l'app lo installa o lo aggiorna dopo conferma e si collega;
- l'app si collega al herdr-core del Linux Terminal come "Questo dispositivo";
- l'utente crea uno spazio con due tab, divide una tab in due pane e in un pane avvia Claude Code, con colori, Unicode e scrollback corretti;
- con l'app desktop Herdr in uso sullo stesso core, spazi, tab e pane creati, cambiati o chiusi in un'app non compaiono né cambiano nell'altra, e i terminali dell'app desktop non vengono ridimensionati dall'app;
- un agente che avvia un worker con `hc run` mostra entrambi nella sidebar con stati che si aggiornano; il worker in Blocked si apre in peek, l'utente risponde e lo stato cambia;
- un agente in un pane avvia con `hc run --label herdr-desktop-android.space=<id>` un worker che compare in una nuova tab del suo spazio, gli manda un messaggio con `hc send` e ne aspetta la fine con `hc wait`;
- un agente in un pane chiede all'app con la ricetta del README di creare una tab e di dividere un pane, riceve la risposta e la finestra mostra il risultato; con due dispositivi collegati la richiesta è eseguita una sola volta;
- chiusa e riaperta l'app, spazi, tab e terminali sono ripristinati e gli agenti hanno continuato a lavorare;
- l'utente usa l'app solo con tastiera e mouse: scorciatoie per spazi, tab e pane, combinazioni Ctrl e Alt nel terminale, selezione, copia e incolla, rotella, menu contestuale;
- le soglie di reattività di REQ-NFR-003 sono rispettate;
- il quality gate passa da un clone pulito e i log non contengono chiavi, password o passphrase.
