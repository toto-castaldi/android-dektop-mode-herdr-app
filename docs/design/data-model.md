# Data model

Modello concettuale. La fonte di verità di spazi, tab, pane, terminali e agenti è il core; l'app ne tiene una copia in memoria e salva in locale solo la configurazione delle macchine.

- **Macchina** (salvata nell'app): nome, host, porta, utente, metodo di autenticazione (riferimento a una chiave del Keystore o password), impronta della host key confermata. "Questo dispositivo" è una macchina predefinita.
- **Chiave SSH** (Keystore): alias, tipo, chiave pubblica esportabile.
- **Spazio** (dal core): nome opzionale, cartella, tab ordinate.
- **Tab** (dal core): nome opzionale, albero di pane.
- **Pane** (dal core): divisione orizzontale o verticale con proporzioni, oppure foglia collegata a un terminale.
- **Terminale** (dal core): indirizzo (ad esempio `t7`, o `vps/t7` su un'altra macchina), cartella, dimensioni, programma.
- **Agente** (dal core): terminale, stato (Working, Blocked, Done, Idle), terminale che lo ha avviato se è un worker.

La rappresentazione fisica e il formato dei dati scambiati con il core sono definiti dopo lo studio dell'API (vedi `README.md`, Open decisions).
