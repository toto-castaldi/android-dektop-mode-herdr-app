# Security and observability

- **Chiavi:** le chiavi private SSH sono generate o importate nell'Android Keystore e non sono esportabili; le password, se usate, sono cifrate con una chiave del Keystore. I dati dell'app sono esclusi dal backup.
- **Host key:** impronta mostrata e confermata al primo collegamento, poi verificata a ogni collegamento; se cambia, il collegamento viene bloccato.
- **Rete:** l'app si collega solo alle macchine configurate. Non ci sono account, server intermedi, SDK di analisi o telemetria.
- **Log:** in logcat compaiono eventi di connessione e di errore senza chiavi, password, passphrase né contenuto dei terminali.
