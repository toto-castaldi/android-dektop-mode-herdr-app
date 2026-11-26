# REQ-CONN-001 — Core remoti via SSH

**State:** Active

L'utente può aggiungere una macchina indicando nome, host, porta e utente SSH, e autenticarsi con una chiave generata nell'app o importata, oppure con password. L'app elenca le macchine con il loro stato (connessa, non raggiungibile, accesso alla rete locale non consentito, autenticazione fallita, core assente o incompatibile), permette di modificarle e rimuoverle, si collega al herdr-core di una macchina e si ricollega quando la connessione cade. L'utente può copiare la chiave pubblica di una chiave generata. Per una macchina della rete locale l'app chiede all'utente il permesso di accedere alla rete locale; se il permesso è negato, la macchina lo indica e l'app spiega come concederlo, invece di restare in attesa.
