# Security and observability

- **Chiavi:** le chiavi private SSH sono generate o importate nell'Android Keystore e non sono esportabili; la firma avviene nel Keystore via JCA e la libreria SSH riceve solo chiave pubblica e firma. Algoritmo Ed25519 (verificato sul Pixel 11 Pro), ripiego automatico ECDSA P-256 se il Keystore non lo supporta. Le password, se usate, sono cifrate con AES-GCM con una chiave del Keystore (`herdr-secret-cipher`) e decifrate solo per l'autenticazione. I dati dell'app sono esclusi dal backup e dal trasferimento tra dispositivi (`allowBackup=false`, `data_extraction_rules.xml`).
- **Host key:** impronta mostrata e confermata al primo collegamento, poi verificata a ogni collegamento; se cambia, il collegamento viene bloccato.
- **Rete:** l'app si collega solo alle macchine configurate. Non ci sono account, server intermedi, SDK di analisi o telemetria.
- **Log:** in logcat compaiono eventi di connessione e di errore senza chiavi, password, passphrase né contenuto dei terminali: l'app registra solo id della macchina e nome dello stato (tag `HerdrConnection`); sshlib usa SLF4J senza binding su Android, quindi non scrive nulla.
- **Permessi:** solo `INTERNET`, usato solo per le connessioni SSH alle macchine configurate.
