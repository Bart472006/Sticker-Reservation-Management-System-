# UE Campus Pass — Setup Guide (`help.md`)

Welcome to the **UE Vehicle Sticker System (UE Campus Pass)**!  
This repository contains both the **PC Desktop App** and the **Android Mobile App**, connected to a shared **XAMPP (Apache + MySQL/MariaDB + PHP)** backend.

---

## 1. Project Structure

```text
UE-Vehicle-Sticker-System/
├── UE-Vehicle-Sticker-App-PC/       # Desktop client (IntelliJ IDEA, Java 17, JavaFX 21, Gradle)
├── UE-Vehicle-Sticker-App-Android/  # Mobile client (Android Studio, Java, Gradle, OkHttp + Glide)
├── ue_sticker_DB/                   # XAMPP backend folder (copy into C:\xampp\htdocs\)
│   ├── api.php                      # REST API used by the Android app & remote PC clients
│   ├── ue_sticker_db.sql            # Empty database schema + default demo accounts
│   └── uploads/                     # Stores uploaded user documents & message attachments
└── help.md                          # This setup guide
```

---

## 2. Prerequisites

Install the following before running the apps:

1. **[XAMPP](https://www.apachefriends.org/)** (includes Apache, MySQL/MariaDB, and phpMyAdmin)
2. **[IntelliJ IDEA](https://www.jetbrains.com/idea/)** + **JDK 17** (for the PC App)
3. **[Android Studio](https://developer.android.com/studio)** + Android SDK (compileSdk 37, minSdk 24, for the Android App)

---

## 3. Step 1 — Set Up XAMPP, Database (`ue_sticker_db`), `api.php`, and `uploads`

### A. Start XAMPP Services
1. Open the **XAMPP Control Panel**.
2. Start **Apache** and **MySQL**.

### B. Create & Import the MySQL Database (`ue_sticker_db`)
1. Open your browser and go to **`http://localhost/phpmyadmin`**.
2. Click the **Import** tab at the top.
3. Click **Choose File**, select **`ue_sticker_DB/ue_sticker_db.sql`** from this repository, and click **Import** (or **Go**).
   - This creates the `ue_sticker_db` database, the three required tables (`users`, `vehicle_applications`, `messages`), and starter test accounts:
     - **Admin:** `admin@ue.edu.ph` / `admin123` (`ADMIN-001`)
     - **Student:** `juan.delacruz@ue.edu.ph` / `student123` (`2021-09876`)
   - On startup, the PC app also automatically inserts a demo expired-sticker student account (`maria.santos@ue.edu.ph` / `student123`, ID `2022-10452`) if it does not already exist in `users`.

### C. Copy `ue_sticker_DB` into XAMPP (`C:\xampp\htdocs\`)
1. Copy the **`ue_sticker_DB`** folder from this repository into **`C:\xampp\htdocs\`** so you have:
   - `C:\xampp\htdocs\ue_sticker_DB\api.php`
   - `C:\xampp\htdocs\ue_sticker_DB\uploads\`
   - `C:\xampp\htdocs\ue_sticker_DB\uploads\msg_attachments\`
   *(On Windows, folder names are case-insensitive; on Linux/macOS Apache servers, keep the exact folder name **`ue_sticker_DB`** to match the URLs in both apps.)*
2. If your XAMPP MySQL username or password is not the default (`root` / empty password), update `$host`, `$user`, and `$pass` at the top of `C:\xampp\htdocs\ue_sticker_DB\api.php`.
3. Verify it works by opening **`http://localhost/ue_sticker_DB/api.php?action=get_all_apps`** in your browser — you should see `{"success":true,"applications":[]}`.

---

## 4. Step 2 — Set Up & Run the PC App (IntelliJ IDEA + JavaFX)

1. Open **IntelliJ IDEA** and choose **Open** → select the **`UE-Vehicle-Sticker-App-PC`** folder.
2. Wait for Gradle to sync. Make sure the Project SDK / Gradle JVM is set to **JDK 17** (`File → Project Structure` and `Settings → Build, Execution, Deployment → Build Tools → Gradle`).
3. Configure your database/server connection in **two** files:
   - **`UE-Vehicle-Sticker-App-PC/src/main/java/com/ue/vehiclestickersystem/Main.java`** (inside `DatabaseManager`):
     - `URL`: defaults to `"jdbc:mysql://localhost:3306/ue_sticker_db?..."`
     - `USER`: `"root"` (change if your MySQL user is different)
     - `PASS`: `""` (change if your MySQL user has a password)
   - **`UE-Vehicle-Sticker-App-PC/src/main/java/com/ue/vehiclestickersystem/PortalController.java`**:
     - `SERVER_IP`: defaults to `"localhost"`
     - Keep `"localhost"` if XAMPP is running on the same computer (files save directly to `C:\xampp\htdocs\ue_sticker_DB\uploads`).
     - Replace `"localhost"` with the host PC's LAN IP (e.g., `"192.168.1.10"`) or Tailscale IP if running the PC app on another computer.
4. Run the application:
   - In IntelliJ's **Gradle** tool panel: `vehicle-sticker-system → Tasks → application → run`
   - Or via terminal in `UE-Vehicle-Sticker-App-PC`:
     ```powershell
     .\gradlew.bat run
     ```

---

## 5. Step 3 — Set Up & Run the Android App (Android Studio)

1. Open **Android Studio** and choose **Open** → select the **`UE-Vehicle-Sticker-App-Android`** folder.
2. Wait for Gradle Sync to finish (Android Studio automatically updates `local.properties` with your local Android SDK path).
3. Open **`UE-Vehicle-Sticker-App-Android/app/src/main/java/com/ue/vehiclestickersystem/ApiClient.java`** and configure your server connection:

   - **Option A — Android Studio Emulator (running on the same PC as XAMPP):**
     ```java
     private static final boolean USE_EMULATOR = true;
     ```
     *(Uses `10.0.2.2`, which routes directly to your PC's `localhost`.)*

   - **Option B — Physical Android Phone (same Wi-Fi / LAN as the PC running XAMPP):**
     1. Find your PC's local IPv4 address by running `ipconfig` in Command Prompt / PowerShell (for example, `192.168.1.10`).
     2. Keep `USE_EMULATOR = false;` and update `PC_IP`:
        ```java
        private static final boolean USE_EMULATOR = false;
        private static final String PC_IP = "192.168.1.10"; // Replace with your PC's IPv4 address
        ```

   - **Option C — Remote Access via Tailscale / VPN:**
     1. Install Tailscale on both the host PC (running XAMPP) and your Android phone.
     2. Keep `USE_EMULATOR = false;` and set `PC_IP` to the host PC's Tailscale IPv4 address (`100.x.x.x`).

4. Click **Run `'app'`** in Android Studio to deploy to your emulator or USB-connected Android device.

---

## 6. Troubleshooting & Multi-Device Tips

- **PC App says `"XAMPP MySQL offline. Running in local fallback mode."`**
  - Verify **MySQL** is running in XAMPP Control Panel on port `3306`.
  - Verify the database `ue_sticker_db` exists in phpMyAdmin.
  - Check `URL`, `USER`, and `PASS` in `Main.java`.
- **Android App says `"Server unreachable"`**
  - Verify **Apache** is running in XAMPP Control Panel.
  - Test `http://localhost/ue_sticker_DB/api.php?action=get_all_apps` in your PC's browser first.
  - If using a physical phone on Wi-Fi, ensure the phone and PC are on the same network (not guest Wi-Fi with AP isolation) and allow incoming TCP port **80** (Apache HTTP Server) in **Windows Defender Firewall**.
- **Connecting a second PC to MySQL over LAN / Tailscale**
  - By default, XAMPP's `root` user only accepts connections from `localhost` / `127.0.0.1`. Create a MySQL user (or grant privileges for `%` or the client's IP) in phpMyAdmin and allow port **3306** in Windows Firewall if another PC needs direct JDBC access.
- **Note before pushing to GitHub**
  - Make sure you do **not** commit your personal `local.properties` or signed APK/keystore files (`UEPass.jks`) if you build release artifacts locally.
