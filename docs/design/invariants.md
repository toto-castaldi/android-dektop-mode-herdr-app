# Invariants and consistency

- L'app non si collega mai a un host la cui host key non è stata confermata o è cambiata rispetto a quella confermata. Il controllo è in `core-client`.
- Nessun comando di installazione o aggiornamento viene eseguito su una macchina senza la conferma esplicita dell'utente.
- Chiudere la finestra dell'app non termina terminali sul core; solo la chiusura esplicita di un pane o di una tab lo fa.
- Lo stato di spazi, tab, pane e agenti mostrato dall'app deriva dal core: l'app non lo modifica in locale senza inviare la richiesta al core.
