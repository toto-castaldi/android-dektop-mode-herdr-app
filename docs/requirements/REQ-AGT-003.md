# REQ-AGT-003 — Comandi degli agenti all'app

**State:** Active

Un agente in un terminale di un core collegato può chiedere all'app, attraverso il core e senza altri canali di rete, di creare uno spazio o una tab, dividere un pane, selezionare uno spazio o una tab e portare in primo piano un agente; riceve una risposta con l'esito nello stile di `herdr-app do`, e le azioni non supportate rispondono `unsupported`. I comandi non chiudono spazi, tab o pane e non terminano terminali. Le azioni passano dallo stato del core come i comandi dell'utente e lasciano invariati gli spazi dell'app desktop Herdr. Con più dispositivi collegati allo stesso core ogni richiesta è eseguita una sola volta; una richiesta fatta mentre nessun dispositivo è collegato viene eseguita al collegamento se l'agente aspetta ancora la risposta, altrimenti scade. Il log dell'app registra solo l'azione e l'esito, mai gli argomenti.
