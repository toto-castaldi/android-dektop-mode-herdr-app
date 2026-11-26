# Glossary

Terms used consistently in requirements, backlog and code.

- **herdr-core (core):** il runtime di Herdr che esegue terminali e agenti su una macchina e li tiene vivi quando i client si chiudono; espone un'API (OpenRPC, `hc schema`).
- **hc:** la CLI di herdr-core (`hc run`, `hc ls`, `hc send`, …).
- **Macchina:** un host configurato nell'app su cui gira un core: remota (raggiunta via SSH) o "Questo dispositivo" (il Linux Terminal).
- **Linux Terminal:** l'ambiente Linux (VM Debian) integrato in Android, in cui gira il core locale.
- **Desktop Mode:** la modalità desktop di Android con finestre ridimensionabili su display esterno, tastiera e mouse.
- **Spazio:** contenitore del lavoro su una macchina, di solito in un repository; contiene tab.
- **Tab:** scheda di uno spazio; si divide in pane.
- **Pane:** area di una tab che mostra un terminale.
- **Terminale:** processo in esecuzione sul core, con il suo I/O; l'app lo mostra in un pane o in peek.
- **Agente:** programma in un terminale che riporta il proprio stato al core.
- **Worker:** agente avviato da un altro agente, spesso senza pane.
- **Stato dell'agente:** Working (al lavoro), Blocked (in attesa dell'utente), Done (finito mentre l'utente guardava altrove), Idle (fermo, o finito e già visto).
- **Sidebar degli agenti:** elenco degli agenti di tutti gli spazi delle macchine collegate.
- **Peek:** vista temporanea di un agente senza pane, sopra la tab corrente.
- **Host key:** chiave pubblica con cui un server SSH si identifica; l'app ne verifica l'impronta.
- **Dispositivo di riferimento:** Pixel 11 Pro, su cui si verificano i criteri di accettazione.
