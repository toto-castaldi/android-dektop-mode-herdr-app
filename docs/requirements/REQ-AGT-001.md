# REQ-AGT-001 — Sidebar degli agenti

**State:** Active

Una sidebar elenca gli agenti degli spazi dell'app sulle macchine collegate, inclusi i worker avviati da quegli agenti; gli agenti dell'app desktop Herdr sullo stesso core non compaiono e l'app non ne cambia lo stato. Per ogni agente mostra lo stato riportato dal programma: Working (al lavoro), Blocked (in attesa dell'utente), Done (finito mentre l'utente guardava altrove), Idle (fermo, oppure finito e già visto). Gli stati si aggiornano in tempo reale; un agente Done diventa Idle quando l'utente lo guarda. Selezionare un agente con un pane lo porta in primo piano; selezionare un agente senza pane lo apre in peek (REQ-AGT-002).
