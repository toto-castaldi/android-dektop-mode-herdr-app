# REQ-WS-003 — Terminali avviati dagli agenti

**State:** Active

Un agente in un terminale del core può organizzare il proprio lavoro nell'app: un terminale avviato sul core con l'etichetta `herdr.space` di uno spazio compare in quello spazio, nella tab indicata dall'etichetta `herdr.tab` oppure in una nuova tab se l'etichetta manca, anche se è stato avviato mentre l'app era chiusa. Togliere le etichette lo toglie dall'app senza terminarlo. I terminali senza quelle etichette e senza un pane restano fuori dagli spazi (un worker compare solo nella sidebar degli agenti). Nei terminali dell'app gli agenti trovano il proprio indirizzo e le etichette del proprio spazio e della propria tab, e comunicano tra loro tramite il core (`hc run`, `hc send`, `hc wait`) come nell'app desktop.
