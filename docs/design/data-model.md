# Data model

Modello concettuale. La fonte di verità di spazi, tab, pane, terminali e agenti è il core; l'app ne tiene una copia in memoria e salva in locale solo la configurazione delle macchine.

- **Macchina** (salvata nell'app): nome, host, porta, utente, metodo di autenticazione (riferimento a una chiave del Keystore o password), impronta della host key confermata. "Questo dispositivo" è una macchina predefinita.
- **Chiave SSH** (Keystore): alias, tipo, chiave pubblica esportabile.
- **Spazio** (dal core): nome opzionale, cartella, tab ordinate.
- **Tab** (dal core): nome opzionale, albero di pane.
- **Pane** (dal core): divisione orizzontale o verticale con proporzioni, oppure foglia collegata a un terminale.
- **Terminale** (dal core): indirizzo (ad esempio `t7`, o `vps/t7` su un'altra macchina), cartella, dimensioni, programma.
- **Agente** (dal core): terminale, stato (Working, Blocked, Done, Idle), terminale che lo ha avviato se è un worker.

## Rappresentazione fisica

- **Macchine:** file JSON privato dell'app `files/machines.json` (`FileMachineStore`, sostituito in modo atomico a ogni modifica), formato `{"version": 1, "machines": [...]}`; ogni macchina ha `id` (UUID), `name`, `host`, `port`, `user`, `key_alias` oppure `sealed_password` (Base64 di `iv || ciphertext` AES-GCM, chiave nel Keystore), `host_key_type` e `host_key` (Base64 del blob SSH confermato). Una modifica di host o porta dimentica la host key confermata.
- **Chiave SSH:** una chiave dell'app nel Keystore con alias `herdr-ssh-default`, condivisa dalle macchine che usano l'autenticazione a chiave; la chiave pubblica si esporta come riga `authorized_keys` con commento `herdr-desktop-android`.
- **Stato di collegamento:** solo in memoria (`ConnectionManager.statuses`): non collegata, collegamento in corso, connessa (nome e versione del core), non raggiungibile (motivo e prossimo tentativo), autenticazione fallita, host key da confermare, host key cambiata, core assente, core incompatibile.
- Il formato dei dati scambiati con il core è quello del protocollo di herdr-core (`integrations.md`).
