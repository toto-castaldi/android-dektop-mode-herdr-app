# Context and main flows

```mermaid
flowchart LR
  U[Utente<br/>monitor, tastiera, mouse] --> A[Herdr Desktop for Android<br/>Pixel in Desktop Mode]
  A -- SSH --> R[herdr-core remoto<br/>host Linux/macOS]
  A -- SSH --> L[herdr-core locale<br/>Linux Terminal di Android]
  R --> T1[terminali e agenti]
  L --> T2[terminali e agenti]
```

- L'app è un client: non esegue terminali. Ogni macchina configurata ha un core che tiene vivi terminali e agenti anche quando l'app è chiusa.
- L'app apre una connessione SSH per macchina e, su di essa, parla con l'API del core (dettagli in `integrations.md`).

## Flussi principali

1. **Aggiungere una macchina:** l'utente inserisce host, porta e utente, sceglie una chiave del Keystore o una password, conferma l'impronta della host key; l'app si collega e verifica la versione del core.
2. **Core assente o incompatibile:** l'app lo segnala e, dopo conferma, lo installa o aggiorna sulla macchina, poi si collega.
3. **Lavorare:** l'app mostra spazi e tab dal core; l'utente divide le tab in pane, ogni pane è collegato a un terminale del core e ne trasmette input e dimensioni.
4. **Seguire gli agenti:** l'app riceve dal core gli stati degli agenti e li mostra nella sidebar; un agente senza pane si apre in peek.
5. **Chiudere e riaprire:** chiudere l'app non ferma i terminali; riaprendola, l'app ricostruisce spazi, tab e pane dallo stato del core.

## Finestre in Desktop Mode

- Ogni finestra è un'istanza di `MainActivity` in un task proprio (`FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK`), quindi una finestra separata, ridimensionabile e massimizzabile (REQ-DM-001).
- "Nuova finestra", nella schermata degli spazi, apre un'altra finestra sulla stessa macchina e sullo spazio successivo a quello selezionato; macchina e spazio iniziali passano negli extra dell'intent (`WindowTarget`).
- Connessioni, sessioni e stato degli spazi stanno nell'`AppContainer` dell'applicazione e sono condivisi tra le finestre; ogni finestra tiene solo la propria selezione (macchina, spazio, tab) nello stato salvato.
- L'attività dichiara in `configChanges` dimensioni, layout, densità, display e tema: ridimensionare, massimizzare, spostare la finestra tra display o cambiare la densità non la ricrea, e Compose ridisegna il layout senza perdere stato né connessioni.
