# BT Audio Boost 🎧

## ⚡ MODO PIÙ VELOCE: Build gratis con GitHub Actions

> Nessun software da installare — solo un account GitHub gratuito!

### Passo 1 – Crea un repository GitHub
1. Vai su **github.com** → clicca **"+"** → **"New repository"**
2. Nome: `BluetoothAudioBoost`
3. Lascia **Public** (è gratuito per le Actions)
4. Clicca **"Create repository"**

### Passo 2 – Carica i file
- Trascina l'intera cartella `BluetoothAudioBoost` nella pagina del repository
- Oppure usa il pulsante **"uploading an existing file"**
- Clicca **"Commit changes"**

### Passo 3 – Avvia la build
1. Nel repository vai su **"Actions"** (tab in alto)
2. Clicca **"Build APK"** nella lista a sinistra
3. Clicca **"Run workflow"** → **"Run workflow"**
4. Attendi ~3 minuti ⏳

### Passo 4 – Scarica l'APK
1. Clicca sul workflow completato (✅ verde)
2. In fondo alla pagina, sotto **"Artifacts"**
3. Clicca **"BluetoothAudioBoost-debug"** → si scarica lo ZIP
4. Estrai lo ZIP → trovi **app-debug.apk**
5. Trasferiscilo sul telefono e installalo!

> ⚠️ Per installare APK sideloaded: Impostazioni → Sicurezza → "Origini sconosciute" (o "Installa app sconosciute")

---

## Con Android Studio (alternativa)

1. Apri **Android Studio** → `File → Open` → seleziona la cartella
2. Attendi sync Gradle
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. APK in: `app/build/outputs/apk/debug/app-debug.apk`

---

## Funzionalità app

| | |
|---|---|
| 📱 Selezione dispositivo | Mostra dispositivi BT abbinati, salva quello scelto |
| 🔊 Slider boost | 100%–160% (~+4 dB) con LoudnessEnhancer |
| 🔗 Auto-attivazione | Connessione BT → boost immediato |
| 🔇 Auto-ripristino | Disconnessione → volume originale |
| 📞 Chiamate | Boost sospeso, audio al telefono al volume normale |
| 🔄 Boot automatico | Servizio riavviato al riavvio del telefono |

## Permessi richiesti

- `BLUETOOTH_CONNECT / SCAN` — per leggere dispositivi abbinati  
- `READ_PHONE_STATE` — per rilevare chiamate in arrivo  
- `MODIFY_AUDIO_SETTINGS` — per modificare il volume  
- `FOREGROUND_SERVICE` — per girare in background  
