# Configuration and local runtime

- **Prerequisiti di sviluppo:** JDK e Android SDK nelle versioni bloccate dallo scaffold; Gradle solo tramite il wrapper del repository; `adb` e il Pixel 11 Pro per test strumentali ed end-to-end.
- **Configurazione dell'app:** nessuna variabile d'ambiente né file di configurazione con segreti; macchine e chiavi si configurano dall'app.
- **Comandi:** quelli del quality gate in `AGENTS.md` §6.
- Struttura dei moduli Gradle, minSdk, targetSdk e versioni della toolchain sono decisioni aperte (vedi `README.md`, Open decisions).
