# REQ-NFR-003 — Reattività del terminale

**State:** Active

Con tastiera fisica e un core sulla stessa rete locale, verificato sul dispositivo di riferimento in Desktop Mode:

- **Latenza tasto-eco:** su almeno 200 tasti premuti in un programma che ne mostra l'eco (per esempio vim in modalità insert), il tempo dal timestamp dell'evento di tasto al completamento del frame che mostra l'eco è al massimo 40 ms al 50° percentile, 60 ms al 95° e 100 ms al 99°.
- **Output continuo:** durante 15 s di output continuo di testo con colori truecolor e caratteri Unicode (CJK, emoji, box drawing), in una finestra larga quanto il monitor, il frame rate medio è almeno l'83% della frequenza del display (50 fps a 60 Hz) e nessun frame dura più di 100 ms.

Metodo di misura: tempi letti sullo stesso clock monotono dall'evento di tasto (`KeyEvent.getEventTime()`) e da `FrameMetrics` (vsync previsto più durata totale del frame) per il frame che contiene i byte dell'eco; frame rate come numero di frame disegnati diviso per la durata della prova, con la durata dei frame da `FrameMetrics`. Le misure escludono la composizione e la scansione del monitor.
