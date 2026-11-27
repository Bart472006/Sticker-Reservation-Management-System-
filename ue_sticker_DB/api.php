<?php
header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST");

// Connect your XAMPP MySQL database here:
// - Keep "localhost" when api.php and MySQL run on the same XAMPP server.
// - Make sure the database "ue_sticker_db" exists (import ue_sticker_db.sql in phpMyAdmin)
//   and update $user / $pass if your MySQL credentials differ from XAMPP defaults.
$host = "localhost";
$db   = "ue_sticker_db";
$user = "root";
$pass = "";

try {
    $pdo = new PDO("mysql:host=$host;dbname=$db;charset=utf8mb4", $user, $pass, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
    ]);
} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(["success" => false, "message" => "DB Error: " . $e->getMessage()]);
    exit;
}

$action = $_REQUEST['action'] ?? '';

switch ($action) {
    case 'login':
        $email = trim($_POST['email'] ?? '');
        $password = trim($_POST['password'] ?? '');
        $stmt = $pdo->prepare("SELECT id, name, email, role, department FROM users WHERE email = ? AND password = ? LIMIT 1");
        $stmt->execute([$email, $password]);
        $row = $stmt->fetch();
        echo json_encode($row
            ? ["success" => true, "user" => $row]
            : ["success" => false, "message" => "Invalid email or password."]);
        break;

    case 'get_user_app':
        $ownerId = trim($_GET['owner_id'] ?? '');
        $stmt = $pdo->prepare("SELECT * FROM vehicle_applications WHERE owner_id = ? LIMIT 1");
        $stmt->execute([$ownerId]);
        echo json_encode(["success" => true, "application" => $stmt->fetch() ?: null]);
        break;

    case 'get_all_apps':
        $stmt = $pdo->query("SELECT * FROM vehicle_applications ORDER BY id DESC");
        echo json_encode(["success" => true, "applications" => $stmt->fetchAll()]);
        break;

    case 'create_app':
        // Ensure contact_number column exists in vehicle_applications
        $colCheck = $pdo->query("SHOW COLUMNS FROM vehicle_applications LIKE 'contact_number'")->fetch();
        if (!$colCheck) {
            $pdo->exec("ALTER TABLE vehicle_applications ADD COLUMN contact_number VARCHAR(50) NULL");
        }

        $stmt = $pdo->prepare("INSERT INTO vehicle_applications (owner_id, owner_name, contact_number, vehicle_model, plate_number, pass_type, ownership) VALUES (?, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([
            $_POST['owner_id'] ?? '',
            trim($_POST['owner_name'] ?? ''),
            trim($_POST['contact_number'] ?? ''),
            $_POST['vehicle_model'] ?? '',
            strtoupper(trim($_POST['plate_number'] ?? '')),
            $_POST['pass_type'] ?? 'Parking Sticker',
            $_POST['ownership'] ?? 'My Own Vehicle'
        ]);
        echo json_encode(["success" => true, "id" => $pdo->lastInsertId()]);
        break;

    case 'update_status':
        $colCheck = $pdo->query("SHOW COLUMNS FROM vehicle_applications LIKE 'approval_date'")->fetch();
        if (!$colCheck) {
            $pdo->exec("ALTER TABLE vehicle_applications ADD COLUMN approval_date VARCHAR(30) NULL");
        }

        $status = $_POST['status'] ?? 'PENDING';
        $approvalDate = (strtoupper($status) === 'APPROVED') ? date('m/d/Y') : null;

        $stmt = $pdo->prepare("UPDATE vehicle_applications SET status = ?, rejection_reason = ?, approval_date = ? WHERE id = ?");
        $stmt->execute([
            $status,
            $_POST['rejection_reason'] ?? null,
            $approvalDate,
            intval($_POST['id'] ?? 0)
        ]);
        echo json_encode(["success" => true]);
        break;

    case 'delete_app':
        $stmt = $pdo->prepare("DELETE FROM vehicle_applications WHERE id = ? OR owner_id = ?");
        $stmt->execute([intval($_POST['id'] ?? 0), $_POST['owner_id'] ?? '']);
        echo json_encode(["success" => true]);
        break;

    case 'get_messages':
        $mode   = $_GET['mode'] ?? 'USER_RECEIVED';
        $userId = $_GET['user_id'] ?? '';
        $email  = $_GET['email'] ?? '';

        if ($mode === 'USER_RECEIVED') {
            $stmt = $pdo->prepare("SELECT * FROM messages WHERE recipient_id = ? OR LOWER(recipient_id) = LOWER(?) OR recipient_id = 'ALL' ORDER BY id DESC");
            $stmt->execute([$userId, $email]);
        } elseif ($mode === 'USER_SENT') {
            $stmt = $pdo->prepare("SELECT * FROM messages WHERE recipient_id = 'ADMIN' AND sender_name LIKE ? ORDER BY id DESC");
            $stmt->execute(["%" . $userId . "%"]);
        } elseif ($mode === 'ADMIN_RECEIVED') {
            $stmt = $pdo->query("SELECT * FROM messages WHERE recipient_id = 'ADMIN' ORDER BY id DESC");
        } else {
            $stmt = $pdo->query("SELECT * FROM messages WHERE recipient_id != 'ADMIN' ORDER BY id DESC");
        }
        echo json_encode(["success" => true, "messages" => $stmt->fetchAll()]);
        break;

    case 'send_message':
        $stmt = $pdo->prepare("INSERT INTO messages (sender_name, recipient_id, subject, body, is_announcement) VALUES (?, ?, ?, ?, ?)");
        $stmt->execute([
            $_POST['sender_name'] ?? '',
            $_POST['recipient_id'] ?? '',
            $_POST['subject'] ?? '',
            $_POST['body'] ?? '',
            intval($_POST['is_announcement'] ?? 0)
        ]);
        echo json_encode(["success" => true]);
        break;

    case 'upload_doc':
        $userId = preg_replace('/[^a-zA-Z0-9._-]/', '_', trim($_POST['user_id'] ?? 'unknown'));
        $rawKey = strtolower(trim($_POST['doc_key'] ?? $_POST['doc_type'] ?? 'doc'));

        // Normalize doc_key to match canonical PC application prefixes
        if (strpos($rawKey, 'owner') !== false || strpos($rawKey, 'auth') !== false || strpos($rawKey, 'letter') !== false || strpos($rawKey, 'valid_id') !== false || strpos($rawKey, 'valid id') !== false) {
            $docKey = 'Owner_s_Valid_ID___Approval_Letter';
        } elseif (strpos($rawKey, 'reg') !== false || strpos($rawKey, 'form') !== false) {
            $docKey = 'UE_Registration_Form';
        } elseif (strpos($rawKey, 'licen') !== false || strpos($rawKey, 'driver') !== false) {
            $docKey = 'Driver_s_License';
        } elseif (strpos($rawKey, 'vehicle') !== false || strpos($rawKey, 'photo') !== false || strpos($rawKey, 'car') !== false) {
            $docKey = 'Vehicle_Photo';
        } elseif (strpos($rawKey, 'cr') !== false || preg_match('/(^|[^a-z])or([^a-z]|$)/', $rawKey)) {
            $docKey = 'CR___OR_Document';
        } else {
            $docKey = preg_replace('/[^a-zA-Z0-9._-]/', '_', $rawKey);
        }

        if (!isset($_FILES['file'])) {
            echo json_encode(["success" => false, "message" => "No file received."]);
            exit;
        }

        $uploadDir = __DIR__ . "/uploads/";
        if (!is_dir($uploadDir)) {
            mkdir($uploadDir, 0777, true);
        }

        // Remove older upload for the same user + document slot to prevent duplicates
        $prefixLower = strtolower($userId . "_" . $docKey . "_");
        foreach (scandir($uploadDir) as $existingFile) {
            if ($existingFile === '.' || $existingFile === '..') continue;
            $existingPath = $uploadDir . $existingFile;
            if (is_file($existingPath) && strpos(strtolower($existingFile), $prefixLower) === 0) {
                @unlink($existingPath);
            }
        }

        $safeName = preg_replace('/[^a-zA-Z0-9._-]/', '_', basename($_FILES['file']['name']));
        $targetFile = $uploadDir . $userId . "_" . $docKey . "_" . $safeName;
        echo json_encode(move_uploaded_file($_FILES['file']['tmp_name'], $targetFile)
            ? ["success" => true, "file" => basename($targetFile)]
            : ["success" => false, "message" => "Failed to save file."]);
        break;

    case 'get_user_docs':
        $userId = preg_replace('/[^a-zA-Z0-9._-]/', '_', $_GET['user_id'] ?? '');
        $uploadDir = __DIR__ . "/uploads/";
        $matched = [];
        if (is_dir($uploadDir) && $userId !== '') {
            foreach (scandir($uploadDir) as $file) {
                if (strpos($file, $userId . "_") === 0) $matched[] = $file;
            }
        }
        echo json_encode(["success" => true, "files" => $matched]);
        break;

    default:
        echo json_encode(["success" => false, "message" => "Unknown action"]);
}
