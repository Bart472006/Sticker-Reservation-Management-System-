# UE Caloocan Campus Pass — Vehicle Sticker Reservation Management System

A cross-platform vehicle sticker application, verification, and permit management system built for the **University of the East (UE)**. The system connects a **JavaFX Desktop Application** (IntelliJ IDEA) and a **Native Android Application** (Android Studio) to a shared **XAMPP (Apache + MySQL/MariaDB + PHP)** backend.

> Looking for full installation and network setup steps? Read **[`help.md`](./help.md)**.

---

## Project Structure

```text
UE-Vehicle-Sticker-System/
├── UE-Vehicle-Sticker-App-PC/       # Desktop client (IntelliJ IDEA, Java 17, JavaFX 21, Gradle)
├── UE-Vehicle-Sticker-App-Android/  # Mobile client (Android Studio, Java, OkHttp, Glide, Gradle)
├── ue_sticker_DB/                   # XAMPP backend (copy into C:\xampp\htdocs\)
│   ├── api.php                      # PHP PDO REST API for Android & remote PC clients
│   ├── ue_sticker_db.sql            # Database schema + default starter accounts
│   └── uploads/                     # Storage folder for uploaded documents & attachments
├── help.md                          # Step-by-step setup & troubleshooting guide
└── README.md                        # Project overview
```

---

## Key Features

### Student / Applicant Portal (PC & Android)
- **Role-Based Login:** Institutional email authentication against the shared `users` table.
- **Sticker Registration (1-Sticker Quota / Academic Year):** Apply for either a **Parking Sticker** or **Drop-Off Sticker**, declare vehicle ownership (*My Own Vehicle* or *Another Person's Vehicle*), and enter vehicle specifications.
- **Digital Credential Uploads:** Upload **CR / OR Document**, **Driver's License**, **UE Registration Form**, **Vehicle Photo**, and (when applicable) **Owner's Valid ID & Approval Letter**, with automatic image compression on Android.
- **Digital UE Campus E-Pass:** Upon approval, displays a digital E-Pass card showing plate number, vehicle model, pass classification, verified documents, and a **1-year validity period** from the approval date.
- **Renewal & Rejection Handling:** Automatically detects expired 1-year stickers with an in-app **Renewal / Reapplication** flow; displays itemized rejection feedback so applicants can replace flagged documents and resubmit or withdraw their application.

### Security Admin Portal (PC & Android)
- **Operational Dashboard:** Live KPI counters (*Total Submissions*, *Pending Review*, *Approved*, *Rejected*), a **500-Sticker Quota Tracker**, a **Pending Review Queue**, and a **Recent Approvals Log**.
- **Records Database & Document Inspector:** Keyword search, status filters, full-size credential image inspection, one-click approval, and a structured **Reject / Flag Application Report** modal.

### Shared Communication & Support
- **Two-Way Inbox:** Broadcast announcements, direct administrative notices, student inquiries, and file attachments (`uploads/msg_attachments/` on desktop, `api.php` on mobile).
- **Pickup & Support Hub:** Gate 1 Security Office (Window 2) claiming requirements, office schedule, and interactive FAQs.
- **Cross-Platform Synchronization:** Android portal auto-syncs every 6 seconds and re-syncs automatically on network changes.

---

## Tech Stack

| Component | Technologies |
| --- | --- |
| **PC Desktop App** | Java 17, JavaFX 21.0.6, FXML, CSS, JDBC (`mysql-connector-j:8.3.0`), Gradle 8.13 (IntelliJ IDEA) |
| **Android App** | Java (minSdk 24 / targetSdk 37), XML Layouts, OkHttp 4.12.0, Glide 4.16.0, Gradle 9.6.0 (Android Studio) |
| **Backend & Database** | XAMPP (Apache HTTP Server, MariaDB/MySQL, PHP PDO `api.php`), `ue_sticker_db` |

---

## Quick Start

1. **Set up XAMPP & Database:**
   - Start **Apache** and **MySQL** in the XAMPP Control Panel.
   - Open `http://localhost/phpmyadmin` and import **`ue_sticker_DB/ue_sticker_db.sql`**.
   - Copy the **`ue_sticker_DB/`** folder into **`C:\xampp\htdocs\`**.
2. **Connect Your Server / Database Host:**
   - **PC App:**
     - [`Main.java`](./UE-Vehicle-Sticker-App-PC/src/main/java/com/ue/vehiclestickersystem/Main.java) → configure `DatabaseManager.URL`, `USER`, and `PASS` (defaults to `localhost`).
     - [`PortalController.java`](./UE-Vehicle-Sticker-App-PC/src/main/java/com/ue/vehiclestickersystem/PortalController.java) → configure `SERVER_IP` (`"localhost"` on the host PC, or your host PC's LAN/Tailscale IP for remote PCs).
   - **Android App:**
     - [`ApiClient.java`](./UE-Vehicle-Sticker-App-Android/app/src/main/java/com/ue/vehiclestickersystem/ApiClient.java) → set `USE_EMULATOR = true` for the Android Studio Emulator (`10.0.2.2`), or set `PC_IP` to your host PC's Wi-Fi IPv4 or Tailscale IP for a physical device.
3. **Run the Apps:**
   - Open `UE-Vehicle-Sticker-App-PC` in **IntelliJ IDEA** and run `.\gradlew.bat run` (or run via Gradle tasks).
   - Open `UE-Vehicle-Sticker-App-Android` in **Android Studio** and click **Run 'app'**.

For detailed instructions, database schema notes, starter test credentials, and troubleshooting, see **[`help.md`](./help.md)**.

---

## Proponents

**University of the East — College of Engineering (Computer Engineering Department)**  
*NCP 3105 — Software Design*
- Bartolome, Moses Ivan C.
- Dela Cruz, Arabela Grace Y.
- Ng, Jeanne Charlotte Belle V.
