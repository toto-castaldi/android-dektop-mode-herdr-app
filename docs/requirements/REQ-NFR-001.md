# REQ-NFR-001 — Chiavi SSH e host key

**State:** Active

Le chiavi private SSH sono generate o importate nell'Android Keystore e non lasciano mai il dispositivo; chiavi, password e passphrase non compaiono in log, notifiche, appunti o backup dell'app. Al primo collegamento l'app mostra l'impronta della host key e chiede conferma; una host key cambiata blocca il collegamento con un avviso. Si verifica con test unitari sulla gestione delle host key e con l'ispezione dei log durante un collegamento.
