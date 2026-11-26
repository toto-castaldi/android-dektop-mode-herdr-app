# REQ-CONN-004 — Segnalazione di aggiornamenti di herdr-core

**State:** Active

Quando una macchina, remota o locale, ha un herdr-core compatibile ma esiste una versione più recente, l'app lo segnala sulla macchina con la versione installata e quella disponibile e propone l'aggiornamento. L'aggiornamento parte solo dopo la conferma dell'utente, come in REQ-CONN-003; finché l'utente non conferma, l'app continua a usare il core installato. La disponibilità di una nuova versione è letta dalla macchina stessa: l'app non apre connessioni verso servizi diversi dalle macchine configurate. Se la macchina non riesce a leggerla, l'app non mostra alcun avviso e il collegamento procede normalmente.
