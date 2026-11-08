<?php
/**
 * API Endpoints - v3.2
 * - 🔒 سجل استهلاك دائم card_redemptions مع UNIQUE(code)
 * - 🔒 استحالة إعادة استخدام أي قسيمة على أي جهاز بعد أول تفعيل
 * - 🆕 verify_card (فحص بدون إحراق) + admin_burn_card + admin_list_redemptions
 * - التجربة 3 أيام، التجديد التلقائي، Activity Log، Rate Limiting
 */

ini_set('display_errors', 0);
ini_set('html_errors', 0);
@ini_set('memory_limit', '1024M');
@ini_set('max_execution_time', '600');
@set_time_limit(600);
@ini_set('post_max_size', '256M');
@ini_set('upload_max_filesize', '256M');
@ini_set('max_input_vars', '100000');
error_reporting(E_ALL);

set_error_handler(function($errno, $errstr, $errfile, $errline) {
    if (!(error_reporting() & $errno)) return false;
    if (in_array($errno, [E_NOTICE, E_USER_NOTICE, E_DEPRECATED, E_USER_DEPRECATED, E_WARNING, E_USER_WARNING, E_STRICT], true)) {
        return true;
    }
    throw new ErrorException($errstr, 0, $errno, $errfile, $errline);
});
set_exception_handler(function($e) {
    if (!headers_sent()) { http_response_code(500); header('Content-Type: application/json; charset=utf-8'); }
    error_log('Unhandled API exception: ' . $e->getMessage());
    echo json_encode(['success' => false, 'error' => 'حدث خطأ داخلي. يرجى مراجعة سجل الخادم.'], JSON_UNESCAPED_UNICODE);
    exit;
});
register_shutdown_function(function() {
    $error = error_get_last();
    if ($error !== null && in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR])) {
        if (!headers_sent()) { http_response_code(500); header('Content-Type: application/json; charset=utf-8'); }
        error_log('Fatal API error: ' . $error['message'] . ' in ' . $error['file'] . ':' . $error['line']);
        echo json_encode(['success' => false, 'error' => 'حدث خطأ داخلي. يرجى مراجعة سجل الخادم.'], JSON_UNESCAPED_UNICODE);
    }
});
if (!function_exists('str_starts_with')) {
    function str_starts_with($haystack, $needle) { return strpos($haystack, $needle) === 0; }
}

require_once __DIR__ . '/db.php';

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Expires: 0');
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') { http_response_code(200); exit; }

define('TRIAL_DAYS_FALLBACK', 3);
define('MAX_BAD_ATTEMPTS_PER_HOUR', 10);
define('MAX_DEVICE_NAME_LEN', 60);

/* ============================================================
   🔄 الترحيل التلقائي الشامل
   ============================================================ */
try {
    $dbCheck = getDB();
    try {
        $dbCheck->exec("PRAGMA journal_mode = WAL");
        $dbCheck->exec("PRAGMA synchronous = NORMAL");
        $dbCheck->exec("PRAGMA temp_store = MEMORY");
        $dbCheck->exec("PRAGMA cache_size = -128000");
        $dbCheck->exec("PRAGMA mmap_size = 268435456");
        $dbCheck->exec("PRAGMA busy_timeout = 15000");
    } catch (Throwable $e) {}

    // القنوات
    $chCols = $dbCheck->query("PRAGMA table_info(channels)")->fetchAll(PDO::FETCH_ASSOC);
    $chExisting = array_column($chCols, 'name');
    foreach (['original_url' => "TEXT DEFAULT ''", 'sort_order' => "INTEGER DEFAULT 0", 'logo_url' => "TEXT DEFAULT ''"] as $col => $def) {
        if (!in_array($col, $chExisting, true)) $dbCheck->exec("ALTER TABLE channels ADD COLUMN $col $def");
    }

    // الباقات
    $catCols = $dbCheck->query("PRAGMA table_info(categories)")->fetchAll(PDO::FETCH_ASSOC);
    if (!in_array('sort_order', array_column($catCols, 'name'), true)) $dbCheck->exec("ALTER TABLE categories ADD COLUMN sort_order INTEGER DEFAULT 0");

    // المستخدمون
    $uCols = $dbCheck->query("PRAGMA table_info(users)")->fetchAll(PDO::FETCH_ASSOC);
    $uExisting = array_column($uCols, 'name');
    foreach ([
        'trial_used' => "INTEGER DEFAULT 0", 'trial_started_at' => "TEXT DEFAULT NULL",
        'bad_attempts' => "INTEGER DEFAULT 0", 'last_bad_attempt_at' => "TEXT DEFAULT NULL",
        'created_at' => "TEXT DEFAULT NULL", 'updated_at' => "TEXT DEFAULT NULL",
        'blocked_reason' => "TEXT DEFAULT NULL", 'total_paid_days' => "INTEGER DEFAULT 0",
        'last_card_code' => "TEXT DEFAULT NULL", 'last_activation_at' => "TEXT DEFAULT NULL",
        'registration_source' => "TEXT DEFAULT 'app'", 'last_seen' => "TEXT DEFAULT NULL",
    ] as $col => $def) {
        if (!in_array($col, $uExisting, true)) $dbCheck->exec("ALTER TABLE users ADD COLUMN $col $def");
    }

    // القسائم
    $cCols = $dbCheck->query("PRAGMA table_info(activation_cards)")->fetchAll(PDO::FETCH_ASSOC);
    $cExisting = array_column($cCols, 'name');
    foreach ([
        'used_at' => "TEXT DEFAULT NULL", 'used_by_device' => "TEXT DEFAULT NULL",
        'attempts' => "INTEGER DEFAULT 0", 'last_attempt_at' => "TEXT DEFAULT NULL",
        'notes' => "TEXT DEFAULT NULL", 'created_by' => "TEXT DEFAULT NULL",
    ] as $col => $def) {
        if (!in_array($col, $cExisting, true)) $dbCheck->exec("ALTER TABLE activation_cards ADD COLUMN $col $def");
    }

    // Activity Log
    $dbCheck->exec("CREATE TABLE IF NOT EXISTS activity_log (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT DEFAULT NULL, action TEXT NOT NULL, details TEXT DEFAULT NULL, ip_address TEXT DEFAULT NULL, user_agent TEXT DEFAULT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_activity_device  ON activity_log(device_id)");
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_activity_action  ON activity_log(action)");
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_activity_created ON activity_log(created_at)");

    // فهارس
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_cards_code   ON activation_cards(code)");
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_cards_status ON activation_cards(status)");
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_users_expire ON users(expire_date)");

    // 🆕 v3.2: جدول الاستهلاك الدائم
    $dbCheck->exec("
        CREATE TABLE IF NOT EXISTS card_redemptions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT NOT NULL,
            redeemed_by_device TEXT NOT NULL,
            redeemed_at TEXT NOT NULL,
            card_id INTEGER DEFAULT NULL,
            duration_days INTEGER DEFAULT 0,
            new_expire TEXT DEFAULT NULL,
            is_renewal INTEGER DEFAULT 0,
            ip_address TEXT DEFAULT NULL
        )
    ");
    $dbCheck->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_redemptions_code_unique ON card_redemptions(code)");
    // يمنع اختلاف حالة الأحرف من إنشاء استهلاك ثانٍ للرمز نفسه.
    try {
        $dbCheck->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_redemptions_code_nocase ON card_redemptions(code COLLATE NOCASE)");
    } catch (Throwable $indexError) {
        // قد توجد بيانات تاريخية مكررة باختلاف حالة الأحرف؛ لا نحذف السجل التاريخي تلقائياً.
        error_log('Could not create case-insensitive redemption index: ' . $indexError->getMessage());
    }
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_redemptions_device ON card_redemptions(redeemed_by_device)");
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_redemptions_time ON card_redemptions(redeemed_at)");

    // 🆕 v9.0: جدول قوائم XTREAM المشفرة الخاصة بالزبائن والمتكامل بين الشاشة ولوحة فضاء الزبون ولوحة التحكم
    $dbCheck->exec("
        CREATE TABLE IF NOT EXISTS customer_xtream_lists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            playlist_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            server_name TEXT NOT NULL,
            server_url TEXT NOT NULL,
            xtream_user TEXT NOT NULL,
            xtream_pass TEXT NOT NULL,
            m3u_url TEXT DEFAULT '',
            exp_date TEXT DEFAULT '',
            enabled INTEGER DEFAULT 1,
            order_index INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ");
    $cxCols = array_column($dbCheck->query("PRAGMA table_info(customer_xtream_lists)")->fetchAll(PDO::FETCH_ASSOC), 'name');
    if (!in_array('exp_date', $cxCols, true)) {
        $dbCheck->exec("ALTER TABLE customer_xtream_lists ADD COLUMN exp_date TEXT DEFAULT ''");
    }
    $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_cx_device ON customer_xtream_lists(device_id)");
    $dbCheck->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_cx_device_pid ON customer_xtream_lists(device_id, playlist_id)");

    // 🆕 v23.3: جدول المفضلة السحابية الموحد (مع التحقق التلقائي من الأعمدة)
    $dbCheck->exec("
        CREATE TABLE IF NOT EXISTS device_favorites (
            device_id TEXT PRIMARY KEY NOT NULL,
            favorites_json TEXT NOT NULL DEFAULT '[]',
            items_count INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ");
    try {
        $dfCols = array_column($dbCheck->query("PRAGMA table_info(device_favorites)")->fetchAll(PDO::FETCH_ASSOC), 'name');
        if (!in_array('favorites_json', $dfCols, true)) $dbCheck->exec("ALTER TABLE device_favorites ADD COLUMN favorites_json TEXT NOT NULL DEFAULT '[]'");
        if (!in_array('items_count', $dfCols, true))    $dbCheck->exec("ALTER TABLE device_favorites ADD COLUMN items_count INTEGER DEFAULT 0");
        if (!in_array('updated_at', $dfCols, true))     $dbCheck->exec("ALTER TABLE device_favorites ADD COLUMN updated_at TEXT DEFAULT CURRENT_TIMESTAMP");
    } catch (Throwable $e) {}

    // ترحيل المستخدمين القدامى
    try {
        if (getSetting('migration_v3_trial') !== '1') {
            $dbCheck->exec("UPDATE users SET trial_used = 1, created_at = COALESCE(created_at, datetime('now')), updated_at = COALESCE(updated_at, datetime('now')) WHERE trial_used = 0");
            setSetting('migration_v3_trial', '1');
        }
    } catch (Throwable $e) {}

    // إصلاح بيانات القسائم القديمة
    try {
        if (getSetting('migration_v3_1_card_status_fix') !== '1') {
            $dbCheck->exec("UPDATE activation_cards SET status = 'used' WHERE (status IS NULL OR status != 'used') AND (used_at IS NOT NULL OR used_by_device IS NOT NULL)");
            $dbCheck->exec("UPDATE activation_cards SET used_at = COALESCE(used_at, created_at, datetime('now')) WHERE status = 'used' AND used_at IS NULL");
            $duplicates = $dbCheck->query("SELECT code, COUNT(*) as cnt FROM activation_cards WHERE code IS NOT NULL GROUP BY code HAVING cnt > 1")->fetchAll(PDO::FETCH_ASSOC);
            foreach ($duplicates as $dup) {
                $keepStmt = $dbCheck->prepare("SELECT id FROM activation_cards WHERE code = ? ORDER BY (CASE WHEN status='used' THEN 0 ELSE 1 END) ASC, id ASC LIMIT 1");
                $keepStmt->execute([$dup['code']]);
                $keepId = (int)$keepStmt->fetchColumn();
                if ($keepId > 0) {
                    $dbCheck->prepare("UPDATE activation_cards SET status = 'invalid_duplicate', notes = COALESCE(notes,'') || ' | [AUTO] رمز مكرر' WHERE code = ? AND id != ?")->execute([$dup['code'], $keepId]);
                }
            }
            setSetting('migration_v3_1_card_status_fix', '1');
        }
    } catch (Throwable $e) {}

    // 🆕 v3.2: ترحيل القسائم المستعملة سابقاً إلى سجل الاستهلاك (تم إصلاح الاستعلام ليعمل بدون عمود total_paid_days غير الموجود)
    try {
        if (getSetting('migration_v3_2_redemptions_fixed') !== '1') {
            $used = $dbCheck->query("SELECT id, code, used_by_device, used_at, duration_days FROM activation_cards WHERE status = 'used' AND code IS NOT NULL AND code != ''")->fetchAll(PDO::FETCH_ASSOC);
            $ins = $dbCheck->prepare("INSERT OR IGNORE INTO card_redemptions (code, redeemed_by_device, redeemed_at, card_id, duration_days) VALUES (?, ?, ?, ?, ?)");
            foreach ($used as $u) {
                $ins->execute([
                    strtoupper(trim((string)$u['code'])),
                    $u['used_by_device'] ?: 'migrated',
                    $u['used_at'] ?: date('Y-m-d H:i:s'),
                    $u['id'],
                    (int)($u['duration_days'] ?? 0)
                ]);
            }
            setSetting('migration_v3_2_redemptions_fixed', '1');
        }
    } catch (Throwable $e) {}

    // 🆕 v25.0: نظام فضاء الموزعين الاحترافي (Resellers & Points System)
    try {
        $cColsNow = array_column($dbCheck->query("PRAGMA table_info(activation_cards)")->fetchAll(PDO::FETCH_ASSOC), 'name');
        foreach ([
            'reseller_id'   => "INTEGER DEFAULT NULL",
            'reseller_name' => "TEXT DEFAULT NULL",
            'points_cost'   => "INTEGER DEFAULT 0"
        ] as $col => $def) {
            if (!in_array($col, $cColsNow, true)) {
                $dbCheck->exec("ALTER TABLE activation_cards ADD COLUMN $col $def");
            }
        }
        $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_cards_reseller ON activation_cards(reseller_id)");

        $dbCheck->exec("
            CREATE TABLE IF NOT EXISTS resellers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL COLLATE NOCASE,
                password_hash TEXT NOT NULL,
                password_plain TEXT DEFAULT '',
                display_name TEXT NOT NULL,
                phone TEXT DEFAULT '',
                points INTEGER NOT NULL DEFAULT 0,
                total_points_received INTEGER NOT NULL DEFAULT 0,
                total_points_spent INTEGER NOT NULL DEFAULT 0,
                total_cards_generated INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                notes TEXT DEFAULT '',
                last_login_at TEXT DEFAULT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        ");
        $dbCheck->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_resellers_username ON resellers(username COLLATE NOCASE)");
        $rColsNow = array_column($dbCheck->query("PRAGMA table_info(resellers)")->fetchAll(PDO::FETCH_ASSOC), 'name');
        if (!in_array('parent_reseller_id', $rColsNow, true)) {
            $dbCheck->exec("ALTER TABLE resellers ADD COLUMN parent_reseller_id INTEGER DEFAULT NULL");
        }
        if (!in_array('parent_reseller_name', $rColsNow, true)) {
            $dbCheck->exec("ALTER TABLE resellers ADD COLUMN parent_reseller_name TEXT DEFAULT NULL");
        }
        $uColsNow2 = array_column($dbCheck->query("PRAGMA table_info(users)")->fetchAll(PDO::FETCH_ASSOC), 'name');
        if (!in_array('reseller_id', $uColsNow2, true)) {
            $dbCheck->exec("ALTER TABLE users ADD COLUMN reseller_id INTEGER DEFAULT NULL");
        }
        $dbCheck->exec("
            CREATE TABLE IF NOT EXISTS portal_security_pins (
                device_id TEXT PRIMARY KEY,
                pin_hash TEXT NOT NULL,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        ");

        $dbCheck->exec("
            CREATE TABLE IF NOT EXISTS reseller_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reseller_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                points_delta INTEGER NOT NULL,
                balance_after INTEGER NOT NULL,
                package_type TEXT DEFAULT '',
                duration_days INTEGER DEFAULT 0,
                card_code TEXT DEFAULT NULL,
                target_device_id TEXT DEFAULT NULL,
                notes TEXT DEFAULT '',
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        ");
        $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_res_tx_reseller ON reseller_transactions(reseller_id)");
        $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_res_tx_created ON reseller_transactions(created_at)");

        $dbCheck->exec("
            CREATE TABLE IF NOT EXISTS reseller_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reseller_id INTEGER NOT NULL,
                token TEXT UNIQUE NOT NULL,
                expires_at TEXT NOT NULL,
                ip_address TEXT DEFAULT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        ");
        $dbCheck->exec("CREATE INDEX IF NOT EXISTS idx_res_sessions_token ON reseller_sessions(token)");
    } catch (Throwable $e) {}

    try { $dbCheck->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_cards_code_unique ON activation_cards(code) WHERE status != 'invalid_duplicate'"); } catch (Throwable $e) {}
} catch (Throwable $e) {}

$action = trim((string)(($_SERVER['REQUEST_METHOD'] === 'POST'
    ? ($_POST['action'] ?? '')
    : ($_GET['action'] ?? '')) ?: ($_REQUEST['action'] ?? '')));
// PRODUCTION: mutating actions must be POST
$_mutatingApi = ['activate_card','save_xtream','delete_xtream','admin_burn_card','admin_create_cards','admin_delete_device','transfer_device','admin_add_reseller','admin_update_reseller','admin_add_reseller_points','admin_delete_reseller','admin_bulk_replace_domain','admin_remove_user_pin','admin_save_data_txt_entry','admin_delete_data_txt_entry','admin_save_data_txt_raw','admin_save_app_update','admin_toggle_portal_main','reseller_login','reseller_add_sub_reseller','reseller_update_sub_reseller','reseller_transfer_sub_points','reseller_delete_sub_reseller','reseller_bulk_replace_domain','reseller_logout','reseller_generate_cards','reseller_direct_activate','reseller_change_password'];
if (in_array($action, $_mutatingApi, true) && ($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    http_response_code(405);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['success' => false, 'error' => 'Method Not Allowed — use POST'], JSON_UNESCAPED_UNICODE);
    exit;
}

/* ============================================================
   دوال مساعدة
   ============================================================ */
function jsonOk($data = [], int $code = 200) {
    http_response_code($code);
    $json = json_encode(['success' => true, 'data' => $data], JSON_UNESCAPED_UNICODE);
    $acceptEnc = $_SERVER['HTTP_ACCEPT_ENCODING'] ?? '';
    if ($json !== false && strlen($json) > 4096 && strpos($acceptEnc, 'gzip') !== false && function_exists('gzencode') && !headers_sent()) {
        header('Content-Encoding: gzip');
        header('Vary: Accept-Encoding');
        echo gzencode($json, 6);
    } else {
        echo $json;
    }
    exit;
}
function jsonError(string $msg, int $code = 400, string $errorCode = '') {
    http_response_code($code);
    $payload = ['success' => false, 'error' => $msg];
    if ($errorCode !== '') $payload['error_code'] = $errorCode;
    echo json_encode($payload, JSON_UNESCAPED_UNICODE);
    exit;
}
function requireAdmin() {
    $token = $_REQUEST['token'] ?? '';
    $authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if ($authHeader) $token = str_replace('Bearer ', '', $authHeader);
    if (empty($token)) jsonError('مطلوب رمز مصادقة', 401);
    $stmt = getDB()->prepare("SELECT * FROM admin_sessions WHERE token = ? AND expires_at > datetime('now')");
    $stmt->execute([$token]);
    if (!$stmt->fetch()) jsonError('جلسة منتهية أو غير صالحة', 401);
    return true;
}

/**
 * تطبيع ذكي وصارم لرمز القسيمة (XXXX-XXXX) يمنع تبعثر الأحرف عند اللصق أو الكتابة في وضع RTL
 * ويزيل محارف الاتجاه المخفية (BiDi Marks) والأرقام العربية-الهندية وأي نص محيط بالرمز.
 */
function normalizeVoucherCode(string $raw): string {
    if ($raw === '') return '';
    // إزالة محارف الاتجاه المخفية (Zero-Width / BiDi Marks)
    $clean = preg_replace('/[\x{200B}-\x{200F}\x{202A}-\x{202E}\x{2060}-\x{2069}\x{FEFF}]/u', '', $raw);
    if ($clean === null) $clean = $raw;

    // تحويل الأرقام العربية والهندية والفارسية إلى أرقام لاتينية
    $arabicDigits  = ['٠','١','٢','٣','٤','٥','٦','٧','٨','٩','۰','۱','۲','۳','۴','۵','۶','۷','۸','۹'];
    $latinDigits   = ['0','1','2','3','4','5','6','7','8','9','0','1','2','3','4','5','6','7','8','9'];
    $clean = str_replace($arabicDigits, $latinDigits, $clean);
    $clean = strtoupper(trim($clean));

    // إزالة أي رمز شاشة كامل DEV-XXXX-XXXX إن كان ملصوقاً في نفس الرسالة حتى لا يختلط مع القسيمة
    $cleanNoDev = preg_replace('/DEV-[A-Z0-9]{4}-[A-Z0-9]{4}/i', ' ', $clean);
    if ($cleanNoDev !== null) $clean = $cleanNoDev;
    $clean = preg_replace('/\bDEV-?/i', ' ', $clean);

    // 1) البحث عن النمط الصريح XXXX-XXXX أولاً للحفاظ على الترتيب الدقيق 100%
    if (preg_match('/(?<![A-Z0-9])([A-Z0-9]{4})[\-\s_\x{2010}-\x{2015}\x{2212}\x{FE63}\x{FF0D}]+([A-Z0-9]{4})(?![A-Z0-9])/u', $clean, $m)) {
        return $m[1] . '-' . $m[2];
    }

    // 2) البحث عن كتلة متصلة من 8 أحرف وأرقام بالضبط
    if (preg_match('/(?<![A-Z0-9])([A-Z0-9]{8})(?![A-Z0-9])/', $clean, $m)) {
        return substr($m[1], 0, 4) . '-' . substr($m[1], 4, 4);
    }

    // 3) تصفية الأحرف والأرقام فقط
    $alnum = preg_replace('/[^A-Z0-9]/', '', $clean);
    if (strlen($alnum) === 8) {
        return substr($alnum, 0, 4) . '-' . substr($alnum, 4, 4);
    }
    return $clean;
}

function normalizeDeviceCodeApi(string $raw): string {
    if ($raw === '') return '';
    $clean = preg_replace('/[\x{200B}-\x{200F}\x{202A}-\x{202E}\x{2060}-\x{2069}\x{FEFF}]/u', '', $raw);
    if ($clean === null) $clean = $raw;
    $arabicDigits = ['٠','١','٢','٣','٤','٥','٦','٧','٨','٩','۰','۱','۲','۳','۴','۵','۶','۷','۸','۹'];
    $latinDigits  = ['0','1','2','3','4','5','6','7','8','9','0','1','2','3','4','5','6','7','8','9'];
    $clean = strtoupper(trim(str_replace($arabicDigits, $latinDigits, $clean)));
    if (preg_match('/DEV-([A-Z0-9]{4})-([A-Z0-9]{4})/', $clean, $m)) {
        return 'DEV-' . $m[1] . '-' . $m[2];
    }
    if (preg_match('/^([A-Z0-9]{4})-([A-Z0-9]{4})$/', $clean, $m)) {
        return 'DEV-' . $m[1] . '-' . $m[2];
    }
    $alnum = preg_replace('/[^A-Z0-9]/', '', $clean);
    if (strlen($alnum) === 11 && strpos($alnum, 'DEV') === 0) {
        return 'DEV-' . substr($alnum, 3, 4) . '-' . substr($alnum, 7, 4);
    }
    if (strlen($alnum) === 8) {
        return 'DEV-' . substr($alnum, 0, 4) . '-' . substr($alnum, 4, 4);
    }
    return $clean;
}

function getResellerPackagesCatalog(): array {
    return [
        '1month'  => ['type' => '1month',  'days' => 30,  'points' => 1,  'label' => 'شهر واحد (30 يوم)',   'short' => 'شهر واحد'],
        '3months' => ['type' => '3months', 'days' => 90,  'points' => 3,  'label' => '3 أشهر (90 يوم)',     'short' => '3 أشهر'],
        '6months' => ['type' => '6months', 'days' => 180, 'points' => 6,  'label' => '6 أشهر (180 يوم)',    'short' => '6 أشهر'],
        '1year'   => ['type' => '1year',   'days' => 365, 'points' => 12, 'label' => 'سنة كاملة (365 يوم)', 'short' => 'سنة كاملة'],
    ];
}

function requireReseller(): array {
    $token = trim((string)($_REQUEST['reseller_token'] ?? $_REQUEST['token'] ?? ''));
    $authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if ($authHeader && stripos($authHeader, 'Bearer ') === 0) {
        $token = trim(substr($authHeader, 7));
    }
    if ($token === '') {
        jsonError('مطلوب تسجيل الدخول إلى حساب الموزع', 401, 'RESELLER_AUTH_REQUIRED');
    }
    $db = getDB();
    $db->exec("DELETE FROM reseller_sessions WHERE expires_at <= datetime('now')");
    $stmt = $db->prepare("
        SELECT r.*, s.token AS session_token, s.expires_at AS session_expires
        FROM reseller_sessions s
        JOIN resellers r ON r.id = s.reseller_id
        WHERE s.token = ? AND s.expires_at > datetime('now')
        LIMIT 1
    ");
    $stmt->execute([$token]);
    $reseller = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$reseller) {
        jsonError('انتهت جلسة الموزع، يرجى تسجيل الدخول مجدداً', 401, 'RESELLER_SESSION_EXPIRED');
    }
    if ((int)($reseller['is_active'] ?? 1) !== 1) {
        jsonError('حساب الموزع موقوف حالياً. يرجى التواصل مع الإدارة.', 403, 'RESELLER_DISABLED');
    }
    return $reseller;
}

function generateUniqueVoucherCode(PDO $db): string {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for ($try = 0; $try < 50; $try++) {
        $p1 = '';
        $p2 = '';
        for ($i = 0; $i < 4; $i++) {
            $p1 .= $alphabet[random_int(0, strlen($alphabet) - 1)];
            $p2 .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }
        $candidate = $p1 . '-' . $p2;
        $chk1 = $db->prepare("SELECT 1 FROM activation_cards WHERE code = ? COLLATE NOCASE LIMIT 1");
        $chk1->execute([$candidate]);
        if ($chk1->fetchColumn()) continue;
        $chk2 = $db->prepare("SELECT 1 FROM card_redemptions WHERE code = ? COLLATE NOCASE LIMIT 1");
        $chk2->execute([$candidate]);
        if ($chk2->fetchColumn()) continue;
        return $candidate;
    }
    return substr(strtoupper(bin2hex(random_bytes(4))), 0, 4) . '-' . substr(strtoupper(bin2hex(random_bytes(4))), 0, 4);
}

function syncUserToSecondaryPortalDb(string $device_id, string $device_name, string $expire_date, int $is_active, string $notes): void {
    try {
        $sec = getSecondaryPortalPdo();
        if ($sec === null) return;
        $sec->exec("CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT UNIQUE NOT NULL,
            device_name TEXT DEFAULT '',
            expire_date TEXT DEFAULT '',
            is_active INTEGER DEFAULT 1,
            trial_used INTEGER DEFAULT 0,
            notes TEXT DEFAULT '',
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
            last_seen TEXT DEFAULT CURRENT_TIMESTAMP
        )");
        $sec->prepare("
            INSERT INTO users (device_id, device_name, expire_date, is_active, trial_used, notes, created_at, updated_at, last_seen)
            VALUES (?, ?, ?, ?, 1, ?, datetime('now'), datetime('now'), datetime('now'))
            ON CONFLICT(device_id) DO UPDATE SET
                expire_date = excluded.expire_date,
                is_active = excluded.is_active,
                trial_used = 1,
                notes = excluded.notes,
                updated_at = datetime('now'),
                last_seen = datetime('now')
        ")->execute([$device_id, $device_name, $expire_date, $is_active, $notes]);
    } catch (Throwable $e) {}
}
function logActivity(PDO $db, string $device_id, string $action, array $details = []): void {
    try {
        $db->prepare("INSERT INTO activity_log (device_id, action, details, ip_address, user_agent, created_at) VALUES (?, ?, ?, ?, ?, datetime('now'))")
           ->execute([$device_id ?: null, $action, json_encode($details, JSON_UNESCAPED_UNICODE), $_SERVER['REMOTE_ADDR'] ?? null, mb_substr($_SERVER['HTTP_USER_AGENT'] ?? '', 0, 255)]);
    } catch (Throwable $e) {}
}
function countRecentBadAttempts(PDO $db, string $device_id): int {
    try {
        $stmt = $db->prepare("SELECT COUNT(*) FROM activity_log WHERE device_id = ? AND action = 'bad_card_attempt' AND created_at >= datetime('now', '-1 hour')");
        $stmt->execute([$device_id]);
        return (int)$stmt->fetchColumn();
    } catch (Throwable $e) { return 0; }
}
function recordBadAttempt(PDO $db, string $device_id, string $card_code, string $reason, string $device_name = ''): void {
    logActivity($db, $device_id, 'bad_card_attempt', ['card_code' => $card_code, 'reason' => $reason]);
    try { $db->prepare("UPDATE activation_cards SET attempts = attempts + 1, last_attempt_at = datetime('now') WHERE code = ? COLLATE NOCASE")->execute([$card_code]); } catch (Throwable $e) {}
    try {
        $stmt = $db->prepare("SELECT id FROM users WHERE device_id = ?");
        $stmt->execute([$device_id]);
        $existing = $stmt->fetch();
        if ($existing) {
            $db->prepare("UPDATE users SET bad_attempts = bad_attempts + 1, last_bad_attempt_at = datetime('now'), updated_at = datetime('now') WHERE id = ?")->execute([$existing['id']]);
        } else {
            $safeName = mb_substr($device_name ?: 'جهاز جديد', 0, MAX_DEVICE_NAME_LEN);
            $db->prepare("INSERT INTO users (device_id, device_name, expire_date, is_active, notes, trial_used, bad_attempts, last_bad_attempt_at, blocked_reason, created_at, updated_at, registration_source) VALUES (?, ?, date('now'), 0, ?, 1, 1, datetime('now'), ?, datetime('now'), datetime('now'), 'bad_card_attempt')")
               ->execute([$device_id, $safeName, $reason, $reason]);
        }
    } catch (Throwable $e) {}
}

function detectCategorySmart(string $name, string $logo = ''): string {
    if ($logo && preg_match('/channel_original_image\/([^\/]+)\//i', $logo, $m)) {
        $rawCat = ucfirst(strtolower($m[1]));
        $map = ['Sport' => 'رياضة', 'Sports' => 'رياضة', 'Arabic' => 'عربية', 'German' => 'ألمانية', 'English' => 'إنجليزية', 'French' => 'فرنسية', 'Hindi' => 'هندية', 'Turkish' => 'تركية', 'Spanish' => 'إسبانية', 'Albania' => 'ألبانية', 'Movies' => 'أفلام', 'Cinema' => 'أفلام', 'Kids' => 'أطفال', 'Cartoon' => 'أطفال', 'News' => 'أخبار', 'Music' => 'موسيقى', 'SkyUK' => 'بريطانية'];
        return $map[$rawCat] ?? $rawCat;
    }
    if (preg_match('/(MBC|beIN|SSC|AD Sport|Al Jazeera|Rotana)/i', $name, $m)) return $m[1];
    if (stripos($name, 'sport') !== false) return 'رياضة';
    if (stripos($name, 'movie') !== false || stripos($name, 'cinema') !== false) return 'أفلام';
    if (stripos($name, 'news') !== false) return 'أخبار';
    if (stripos($name, 'kids') !== false || stripos($name, 'cartoon') !== false) return 'أطفال';
    return 'عام';
}
function downloadRemotePlaylistToTempFile(string $url): ?string {
    $url = trim($url);
    if ($url === '') return null;
    $tmpFile = tempnam(sys_get_temp_dir(), 'livepro_m3u_');
    if ($tmpFile === false) return null;

    $userAgents = [
        'IPTVSmartersPlayer/6.0 (LIVE-PRO-UNIVERSAL/10.0)',
        'VLC/3.0.20 LibVLC/3.0.20',
        'TiviMate/4.7.0 (Android 14)',
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
    ];

    if (function_exists('curl_init')) {
        foreach ($userAgents as $ua) {
            $fp = @fopen($tmpFile, 'wb');
            if (!$fp) break;
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_FILE           => $fp,
                CURLOPT_FOLLOWLOCATION => true,
                CURLOPT_MAXREDIRS      => 10,
                CURLOPT_CONNECTTIMEOUT => 20,
                CURLOPT_TIMEOUT        => 360,
                CURLOPT_USERAGENT      => $ua,
                CURLOPT_ENCODING       => '',
                CURLOPT_SSL_VERIFYPEER => false,
                CURLOPT_SSL_VERIFYHOST => 0,
                CURLOPT_HTTPHEADER     => ['Accept: */*', 'Connection: keep-alive']
            ]);
            $ok = @curl_exec($ch);
            $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
            curl_close($ch);
            fclose($fp);
            if ($ok && $code >= 200 && $code < 400 && is_file($tmpFile) && filesize($tmpFile) > 16) {
                return $tmpFile;
            }
        }
    }

    foreach ($userAgents as $ua) {
        $ctx = stream_context_create([
            'http' => ['timeout' => 180, 'user_agent' => $ua, 'follow_location' => 1],
            'ssl'  => ['verify_peer' => false, 'verify_peer_name' => false]
        ]);
        $in = @fopen($url, 'rb', false, $ctx);
        if ($in) {
            $out = @fopen($tmpFile, 'wb');
            if ($out) {
                stream_copy_to_stream($in, $out);
                fclose($out);
            }
            fclose($in);
            if (is_file($tmpFile) && filesize($tmpFile) > 16) {
                return $tmpFile;
            }
        }
    }

    @unlink($tmpFile);
    return null;
}

function parseM3UStreamFromFile(string $filePath): array {
    $fp = @fopen($filePath, 'rb');
    if (!$fp) return [];
    $channels = []; $current = null; $catFirstSeen = []; $catCounters = [];
    while (($rawLine = fgets($fp, 16384)) !== false) {
        $line = trim($rawLine);
        if ($line === '') continue;
        if (stripos($line, '#EXTINF:') === 0) {
            $info = substr($line, 8); $attrs = [];
            if (preg_match_all('/([a-zA-Z0-9_-]+)="([^"]*)"/', $info, $m, PREG_SET_ORDER)) {
                foreach ($m as $pair) $attrs[strtolower($pair[1])] = $pair[2];
            }
            $lastComma = strrpos($info, ',');
            $name = $lastComma !== false ? trim(substr($info, $lastComma + 1)) : '';
            $ua = trim($attrs['user-agent'] ?? $attrs['http-user-agent'] ?? '');
            $ref = trim($attrs['referer'] ?? $attrs['http-referrer'] ?? '');
            $current = [
                'name' => $name ?: ($attrs['tvg-name'] ?? ''),
                'logo' => $attrs['tvg-logo'] ?? '',
                'tvg_id' => $attrs['tvg-id'] ?? '',
                'explicit_group' => trim($attrs['group-title'] ?? ''),
                'ua' => $ua,
                'ref' => $ref,
                'url' => ''
            ];
            continue;
        }
        if (stripos($line, '#EXTGRP:') === 0 && $current !== null) {
            $grp = trim(substr($line, 8));
            if ($grp !== '' && $current['explicit_group'] === '') $current['explicit_group'] = $grp;
            continue;
        }
        if (stripos($line, '#EXTVLCOPT:') === 0 && $current !== null) {
            $opt = trim(substr($line, 11));
            if (stripos($opt, 'http-user-agent=') === 0) $current['ua'] = trim(substr($opt, 16));
            elseif (stripos($opt, 'http-referrer=') === 0) $current['ref'] = trim(substr($opt, 14));
            elseif (stripos($opt, 'http-referer=') === 0) $current['ref'] = trim(substr($opt, 13));
            continue;
        }
        if ($line[0] === '#') continue;
        if ($current !== null && preg_match('#^(https?|rtmp|rtmps|rtmpt|rtsp|udp|rtp|mms|mmsh)://#i', $line)) {
            $finalUrl = $line;
            if (strpos($finalUrl, '|') === false) {
                $hdrs = [];
                if (!empty($current['ua'])) $hdrs[] = 'User-Agent=' . $current['ua'];
                if (!empty($current['ref'])) $hdrs[] = 'Referer=' . $current['ref'];
                if (!empty($hdrs)) $finalUrl .= '|' . implode('&', $hdrs);
            }
            $current['url'] = $finalUrl;
            $cat = $current['explicit_group'] !== '' ? $current['explicit_group'] : detectCategorySmart($current['name'], $current['logo']);
            if (!isset($catFirstSeen[$cat])) { $catFirstSeen[$cat] = count($catFirstSeen); $catCounters[$cat] = 0; }
            if (isAdultOr18PlusContent((string)$current['name'], (string)$cat)) { $current = null; continue; }
            $current['category'] = $cat;
            $current['cat_sort'] = $catFirstSeen[$cat];
            $current['ch_sort'] = $catCounters[$cat]++;
            $channels[] = $current;
            $current = null;
        }
    }
    fclose($fp);
    return $channels;
}

function parseM3UContent(string $content): array {
    $tmp = tempnam(sys_get_temp_dir(), 'livepro_str_');
    if ($tmp !== false) {
        file_put_contents($tmp, $content);
        $res = parseM3UStreamFromFile($tmp);
        @unlink($tmp);
        return $res;
    }
    return [];
}

/* ============================================================
   🎫 فحص الاشتراك
   ============================================================ */
function checkUserSubscription(string $device_id, string $device_name = ''): array {
    if (empty($device_id) || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/i', $device_id)) {
        return ['active' => false, 'reason' => 'invalid_format', 'is_trial' => false, 'message' => 'صيغة رمز الجهاز غير صحيحة'];
    }
    $device_id = strtoupper($device_id);
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM users WHERE device_id = ?");
    $stmt->execute([$device_id]);
    $user = $stmt->fetch();

    if (!$user) {
        $trial_days = defined('TRIAL_DAYS') ? TRIAL_DAYS : (int)(getSetting('trial_days') ?: TRIAL_DAYS_FALLBACK);
        $expireDate = date('Y-m-d', strtotime("+{$trial_days} days"));
        $notes = "🎁 تجربة مجانية — {$trial_days} أيام";
        $safeName = mb_substr(trim($device_name ?: 'جهاز جديد'), 0, MAX_DEVICE_NAME_LEN);
        try {
            $db->prepare("INSERT INTO users (device_id, device_name, expire_date, is_active, notes, trial_used, trial_started_at, created_at, updated_at, registration_source, last_seen) VALUES (?, ?, ?, 1, ?, 1, datetime('now'), datetime('now'), datetime('now'), 'trial', datetime('now'))")
               ->execute([$device_id, $safeName, $expireDate, $notes]);
            $stmt->execute([$device_id]);
            $user = $stmt->fetch();
            logActivity($db, $device_id, 'trial_granted', ['days' => $trial_days, 'expire' => $expireDate]);
        } catch (Exception $e) {
            return ['active' => false, 'reason' => 'registration_failed', 'is_trial' => false, 'message' => 'تعذّر إنشاء الحساب التجريبي.'];
        }
    }
    if ((int)$user['is_active'] === 0) {
        return ['active' => false, 'reason' => 'disabled', 'is_trial' => false, 'message' => $user['blocked_reason'] ? 'تم حجب الحساب: ' . $user['blocked_reason'] : 'تم تعطيل اشتراكك.'];
    }
    $expireTimestamp = strtotime($user['expire_date'] . ' 23:59:59');
    if ($expireTimestamp < time()) {
        return ['active' => false, 'reason' => 'expired', 'expire' => $user['expire_date'], 'is_trial' => (bool)$user['trial_used'] && empty($user['last_card_code']), 'message' => 'انتهى اشتراكك بتاريخ ' . $user['expire_date']];
    }
    try { $db->exec("ALTER TABLE users ADD COLUMN portal_main_enabled INTEGER NOT NULL DEFAULT 0"); } catch (Throwable $e) {}
    $db->prepare("UPDATE users SET last_seen = datetime('now') WHERE id = ?")->execute([$user['id']]);
    $days = (int)floor(($expireTimestamp - time()) / 86400);
    $isTrial = (bool)$user['trial_used'] && empty($user['last_card_code']);
    $portalMainEnabled = ((int)($user['portal_main_enabled'] ?? 0)) === 1;
    return [
        'active'              => true,
        'reason'              => $isTrial ? 'trial' : 'ok',
        'expire'              => $user['expire_date'],
        'days_left'           => $days,
        'device_name'         => $user['device_name'],
        'is_trial'            => $isTrial,
        'trial_used'          => (bool)$user['trial_used'],
        'bad_attempts'        => (int)$user['bad_attempts'],
        'portal_main_enabled' => $portalMainEnabled,
        'app_update'          => getAppUpdateConfig($db)
    ];
}

/* ============================================================
   🛡️ فلتر الحماية الذكي ضد قنوات وباقات (+18 / Adult) + تشفير القوائم + تجزئة M3U تلقائياً
   ============================================================ */
function isAdultOr18PlusContent(string $name, string $category = ''): bool {
    $combined = mb_strtolower($name . ' ' . $category, 'UTF-8');
    $pattern = '/(\+18|18\+|_18\+|\+21|21\+|\badult\b|\badults\b|\bxxx\b|\bporn\b|\bporno\b|\bsex\b|\berotic\b|\bnsfw\b|\bplayboy\b|\bbrazzers\b|\bxvideos\b|\bxnxx\b|\bonlyfans\b|\bhentai\b|إباحي|اباحي|اباحية|إباحية|للكبار فقط|قنوات الكبار)/iu';
    return (bool)preg_match($pattern, $combined);
}

function parseXtreamFromM3uLink(string $rawUrl): ?array {
    $rawUrl = trim($rawUrl);
    if ($rawUrl === '' || !preg_match('#^https?://#i', $rawUrl)) return null;
    $parts = @parse_url($rawUrl);
    if (!is_array($parts) || empty($parts['scheme']) || empty($parts['host'])) return null;
    $base = $parts['scheme'] . '://' . $parts['host'] . (!empty($parts['port']) ? ':' . $parts['port'] : '');

    if (!empty($parts['query'])) {
        parse_str($parts['query'], $q);
        $u = trim((string)($q['username'] ?? $q['user'] ?? ''));
        $p = trim((string)($q['password'] ?? $q['pass'] ?? ''));
        if ($u !== '' && $p !== '') {
            return ['server_url' => $base, 'username' => $u, 'password' => $p];
        }
    }
    if (!empty($parts['path']) && preg_match('#/(?:live|movie|series)/([^/]+)/([^/]+)/#i', $parts['path'], $m)) {
        return ['server_url' => $base, 'username' => urldecode($m[1]), 'password' => urldecode($m[2])];
    }
    return null;
}

function encryptXtreamSecret(string $plain): string {
    if ($plain === '' || str_starts_with($plain, 'ENCv9:')) return $plain;
    if (function_exists('encryptUrl')) {
        try { return 'ENCv9:' . encryptUrl($plain); } catch (Throwable $e) {}
    }
    $key = hash('sha256', (defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026'), true);
    $iv = random_bytes(16);
    $cipher = openssl_encrypt($plain, 'AES-256-CBC', $key, OPENSSL_RAW_DATA, $iv);
    return $cipher !== false ? ('ENCv9:' . base64_encode($iv . $cipher)) : $plain;
}

function decryptXtreamSecret(string $stored): string {
    if ($stored === '') return '';
    if (str_starts_with($stored, 'ENCv9:')) {
        $payload = substr($stored, 6);
        if (function_exists('decryptUrl')) {
            try {
                $dec = decryptUrl($payload);
                if ($dec !== '' && $dec !== false) return $dec;
            } catch (Throwable $e) {}
        }
        $raw = base64_decode($payload, true);
        if ($raw === false || strlen($raw) <= 16) return $payload;
        $key = hash('sha256', (defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026'), true);
        $iv = substr($raw, 0, 16);
        $cipher = substr($raw, 16);
        $plain = openssl_decrypt($cipher, 'AES-256-CBC', $key, OPENSSL_RAW_DATA, $iv);
        return $plain !== false ? $plain : $payload;
    }
    if (str_starts_with($stored, 'ENC:')) {
        $raw = base64_decode(substr($stored, 4), true);
        if ($raw === false || strlen($raw) <= 16) return $stored;
        $key = hash('sha256', (defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026'), true);
        $plain = openssl_decrypt(substr($raw, 16), 'AES-256-CBC', $key, OPENSSL_RAW_DATA, substr($raw, 0, 16));
        return $plain !== false ? $plain : $stored;
    }
    return $stored;
}

/**
 * دالة ذكية لاستبدال الدومين القديم بالدومين الجديد داخل أي رابط (سواء كتب الأدمن الدومين مع http:// والمنفذ أو اسم النطاق فقط)
 */

function extractDomainAuthority(string $input): string {
    $s = trim($input);
    if ($s === '') return '';
    $s = preg_replace('/[\x{200B}-\x{200F}\x{202A}-\x{202E}\x{2060}-\x{2069}\x{FEFF}]/u', '', $s);
    $s = preg_replace('~^https?://(?:www\.)?google\.[^/]+/search\?(?:.*&)?q=~i', '', $s);
    if (!preg_match('~^[a-zA-Z][a-zA-Z0-9+\-.]*://~', $s)) {
        $s = 'http://' . ltrim($s, '/');
    }
    $parts = @parse_url($s);
    if (!is_array($parts) || empty($parts['host'])) return '';
    $host = strtolower(trim($parts['host']));
    $port = isset($parts['port']) ? ':' . (int)$parts['port'] : '';
    return $host . $port;
}

function getAppUpdateConfig(PDO $db): array {
    $default = [
        'enabled'      => false,
        'version_code' => 26,
        'version_name' => '26.0',
        'apk_url'      => '',
        'title'        => '📲 تحديث جديد متوفر للتطبيق',
        'notes'        => 'يرجى ترقية نسخة التطبيق للحصول على أحدث المزايا وتحسينات الأداء.',
        'is_mandatory' => true,
        'updated_at'   => ''
    ];
    // 1. فحص ملف app_update_config.json أولاً لأنه يُكتب ذرياً مباشرة من لوحة الأدمن
    $jsonCandidate = null;
    $jsonFile = __DIR__ . '/app_update_config.json';
    if (is_file($jsonFile)) {
        $j = @json_decode((string)@file_get_contents($jsonFile), true);
        if (is_array($j) && trim((string)($j['apk_url'] ?? '')) !== '') {
            $jsonCandidate = [
                'enabled'      => !empty($j['enabled']) && trim((string)($j['apk_url'] ?? '')) !== '',
                'version_code' => max(1, (int)($j['version_code'] ?? 27)),
                'version_name' => trim((string)($j['version_name'] ?? '27.0')),
                'apk_url'      => trim((string)($j['apk_url'] ?? '')),
                'title'        => trim((string)($j['title'] ?? $default['title'])),
                'notes'        => trim((string)($j['notes'] ?? $default['notes'])),
                'is_mandatory' => isset($j['is_mandatory']) ? (bool)$j['is_mandatory'] : true,
                'updated_at'   => (string)($j['updated_at'] ?? '')
            ];
        }
    }
    $dbs = [$db];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;
    $dbCandidate = null;
    foreach ($dbs as $pdo) {
        try {
            $pdo->exec("
                CREATE TABLE IF NOT EXISTS app_update_config (
                    id INTEGER PRIMARY KEY,
                    update_enabled INTEGER NOT NULL DEFAULT 0,
                    version_code INTEGER NOT NULL DEFAULT 27,
                    version_name TEXT NOT NULL DEFAULT '27.0',
                    apk_url TEXT NOT NULL DEFAULT '',
                    update_title TEXT NOT NULL DEFAULT 'تحديث جديد متوفر للتطبيق',
                    update_notes TEXT NOT NULL DEFAULT 'يرجى ترقية نسخة التطبيق للحصول على أحدث المزايا وتحسينات الأداء.',
                    is_mandatory INTEGER NOT NULL DEFAULT 1,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            ");
            $st = $pdo->query("SELECT * FROM app_update_config WHERE id = 1 LIMIT 1");
            $row = $st ? $st->fetch(PDO::FETCH_ASSOC) : false;
            if ($row && is_array($row) && trim((string)($row['apk_url'] ?? '')) !== '') {
                $cand = [
                    'enabled'      => ((int)($row['update_enabled'] ?? 0)) === 1 && trim((string)($row['apk_url'] ?? '')) !== '',
                    'version_code' => (int)($row['version_code'] ?? 27),
                    'version_name' => (string)($row['version_name'] ?? '27.0'),
                    'apk_url'      => trim((string)($row['apk_url'] ?? '')),
                    'title'        => (string)($row['update_title'] ?? $default['title']),
                    'notes'        => (string)($row['update_notes'] ?? $default['notes']),
                    'is_mandatory' => ((int)($row['is_mandatory'] ?? 1)) === 1,
                    'updated_at'   => (string)($row['updated_at'] ?? '')
                ];
                if ($dbCandidate === null || ($cand['enabled'] && !$dbCandidate['enabled']) || strcmp($cand['updated_at'], $dbCandidate['updated_at']) > 0) {
                    $dbCandidate = $cand;
                }
            }
        } catch (Throwable $e) {}
    }
    if ($jsonCandidate !== null && $dbCandidate !== null) {
        if ($jsonCandidate['updated_at'] >= $dbCandidate['updated_at']) {
            return $jsonCandidate;
        }
        return $dbCandidate;
    }
    if ($jsonCandidate !== null) return $jsonCandidate;
    if ($dbCandidate !== null) return $dbCandidate;
    if (is_file($jsonFile)) {
        $j = @json_decode((string)@file_get_contents($jsonFile), true);
        if (is_array($j)) return array_merge($default, $j);
    }
    return $default;
}

function saveAppUpdateConfigAcrossDbs(PDO $db, array $data): array {
    $enabled     = !empty($data['update_enabled']) ? 1 : 0;
    $versionCode = max(1, (int)($data['version_code'] ?? 27));
    $versionName = trim((string)($data['version_name'] ?? ($versionCode . '.0'))) ?: ($versionCode . '.0');
    $apkUrl      = trim((string)($data['apk_url'] ?? ''));
    $apkUrl      = preg_replace('~^https?://(?:www\.)?google\.[^/]+/search\?(?:.*&)?q=~i', '', $apkUrl);
    $title       = trim((string)($data['update_title'] ?? '📲 تحديث جديد متوفر للتطبيق')) ?: '📲 تحديث جديد متوفر للتطبيق';
    $notes       = trim((string)($data['update_notes'] ?? 'يرجى ترقية نسخة التطبيق للحصول على أحدث المزايا وتحسينات الأداء.'));
    $mandatory   = (!isset($data['is_mandatory']) || !empty($data['is_mandatory'])) ? 1 : 0;
    $nowStr      = date('Y-m-d H:i:s');

    $dbs = [$db];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;

    foreach ($dbs as $pdo) {
        try {
            $pdo->exec("
                CREATE TABLE IF NOT EXISTS app_update_config (
                    id INTEGER PRIMARY KEY,
                    update_enabled INTEGER NOT NULL DEFAULT 0,
                    version_code INTEGER NOT NULL DEFAULT 27,
                    version_name TEXT NOT NULL DEFAULT '27.0',
                    apk_url TEXT NOT NULL DEFAULT '',
                    update_title TEXT NOT NULL DEFAULT 'تحديث جديد متوفر للتطبيق',
                    update_notes TEXT NOT NULL DEFAULT 'يرجى ترقية نسخة التطبيق للحصول على أحدث المزايا وتحسينات الأداء.',
                    is_mandatory INTEGER NOT NULL DEFAULT 1,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            ");
            $pdo->prepare("
                INSERT OR REPLACE INTO app_update_config (id, update_enabled, version_code, version_name, apk_url, update_title, update_notes, is_mandatory, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
            ")->execute([$enabled, $versionCode, $versionName, $apkUrl, $title, $notes, $mandatory, $nowStr]);
        } catch (Throwable $e) {}
    }

    $savedObj = [
        'enabled'      => ($enabled === 1 && $apkUrl !== ''),
        'version_code' => $versionCode,
        'version_name' => $versionName,
        'apk_url'      => $apkUrl,
        'title'        => $title,
        'notes'        => $notes,
        'is_mandatory' => ($mandatory === 1),
        'updated_at'   => $nowStr
    ];
    @file_put_contents(__DIR__ . '/app_update_config.json', json_encode($savedObj, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
    return $savedObj;
}

function getDefaultDataTxtContent(): string {
    return <<<'TXT'
# =============================================================================
# LIVE PRO v26.0 — قاعدة بيانات السيرفرات والهوستات السرية للاكتشاف التلقائي الذكي (data.txt)
# الصيغة: اسم السيرفر|رابط الهوست (ServerName|http://host:port)
# ملاحظة أمنية: هذا الملف محمي عبر .htaccess ولا يظهر أي رابط هوست منه للمستخدم نهائياً.
# =============================================================================
Temu VIP|http://tmasta.com
King 365|http://179.61.217.72
Nexon|http://serine-tv.xyz
LIVE PRO VIP 4K|http://tvflux4k.com:8080
IRON PRO MAX|http://line.iron-ott.com:80
IRON TV PRO|http://irontvpro.com:8080
KING 365 4K|http://line.king365tv.com:8080
KING 4K OTT|http://king4k-ott.com:8080
TREX 4K OTT|http://line.trex-ott.com:80
TREX VIP|http://trex-panel.com:8080
DINO IPTV|http://line.dino-iptv.com:8080
DINO PLUS|http://dino-ott.net:8080
STRONG 8K UHD|http://line.strong8k.com:8080
STRONG 4K VIP|http://strong4k.me:8080
MEGA OTT PRO|http://line.megaott.net:80
MEGA VIP 4K|http://mega-ott.com:8080
CRYSTAL OTT|http://line.crystal-ott.com:8080
CRYSTAL VIP|http://crystal-tv.net:8080
MAGNUM 4K|http://magnum-ott.net:80
GOLD TV VIP|http://line.goldtv.cc:8080
ATLAS PRO ONTV|http://atlas-ontv.com:8080
ATLAS VIP 4K|http://line.atlaspro.tv:8080
LYNX IPTV 4K|http://lynx-iptv.net:8080
SANSAT PRO|http://sansat-ott.com:8080
GOOL PRO TV|http://gool-tv.com:8080
STAR SHARE 4K|http://starshare.live:8080
STAR 4K OTT|http://line.starshare.org:8080
LIONZ TV VIP|http://lionz-tv.com:8080
ZENITH 4K|http://zenith-ott.com:8080
COBRA ULTRA|http://cobra-ott.com:8080
FALCON PRO 4K|http://falcon-iptv.net:8080
SPIDER OTT VIP|http://spider-ott.com:8080
B1G OTT 4K|http://line.b1g-ott.com:8080
EAGLE 4K VIP|http://line.eagle4k.cc:8080
MAX OTT PRO|http://line.maxott.net:8080
ROYAL 4K TV|http://royal4k-ott.com:8080
AROMA VIP 4K|http://aroma-ott.com:8080
DIAMOND 4K OTT|http://diamond-ott.net:8080
PLATINUM IPTV|http://platinum-ott.com:8080
NOVA 4K OTT|http://nova-iptv.net:8080
INFINITY 4K|http://infinity-ott.com:8080
MATADOR PRO|http://matador-ott.com:8080
HAWK 4K VIP|http://hawk-ott.net:8080
TXT;
}

function getDataTxtFilePath(): string {
    $file = __DIR__ . '/data.txt';
    if (!is_file($file)) {
        @file_put_contents($file, getDefaultDataTxtContent() . "\n");
    }
    return $file;
}

function normalizeServerBaseUrlApi(string $rawUrl): string {
    $u = trim($rawUrl);
    if ($u === '') return '';
    $u = preg_replace('/[\x{200B}-\x{200F}\x{202A}-\x{202E}\x{2060}-\x{2069}\x{FEFF}]/u', '', $u);
    $u = preg_replace('~^https?://(?:www\.)?google\.[^/]+/search\?(?:.*&)?q=~i', '', $u);
    if (!preg_match('~^https?://~i', $u)) {
        $u = 'http://' . ltrim($u, '/');
    }
    return rtrim($u, '/');
}

function parseDataTxtLinesToEntries(string $rawContent): array {
    $lines = preg_split('/\r\n|\r|\n/', $rawContent) ?: [];
    $entries = [];
    $idx = 0;
    foreach ($lines as $line) {
        $trimLn = trim($line);
        if ($trimLn === '' || $trimLn[0] === '#') continue;
        $name = 'VIP Server';
        $url  = $trimLn;
        if (strpos($trimLn, '|') !== false) {
            [$nm, $u] = explode('|', $trimLn, 2);
            $name = trim($nm) ?: 'VIP Server';
            $url  = trim($u);
        } elseif (strpos($trimLn, '=') !== false && !preg_match('~^https?://~i', explode('=', $trimLn, 2)[0])) {
            [$nm, $u] = explode('=', $trimLn, 2);
            $name = trim($nm) ?: 'VIP Server';
            $url  = trim($u);
        }
        $normUrl = normalizeServerBaseUrlApi($url);
        $domain  = extractDomainAuthority($normUrl);
        if ($normUrl !== '' && $domain !== '') {
            $entries[] = [
                'index'  => $idx++,
                'name'   => $name,
                'url'    => $normUrl,
                'domain' => $domain
            ];
        }
    }
    return $entries;
}

function readDataTxtEntries(): array {
    $file = getDataTxtFilePath();
    $raw = (string)@file_get_contents($file);
    if (trim($raw) === '') {
        $raw = getDefaultDataTxtContent();
        @file_put_contents($file, $raw . "\n");
    }
    return parseDataTxtLinesToEntries($raw);
}

function writeDataTxtEntries(array $entries): bool {
    $file = getDataTxtFilePath();
    $out = [
        "# =============================================================================",
        "# LIVE PRO v26.0 — قاعدة بيانات السيرفرات والهوستات السرية للاكتشاف التلقائي الذكي (data.txt)",
        "# الصيغة: اسم السيرفر|رابط الهوست (ServerName|http://host:port)",
        "# ملاحظة أمنية: هذا الملف محمي عبر .htaccess ولا يظهر أي رابط هوست منه للمستخدم نهائياً.",
        "# ============================================================================="
    ];
    foreach ($entries as $e) {
        $nm  = str_replace(['|', "\r", "\n"], ' ', trim((string)($e['name'] ?? 'VIP Server'))) ?: 'VIP Server';
        $url = normalizeServerBaseUrlApi((string)($e['url'] ?? ''));
        if ($url !== '') {
            $out[] = $nm . '|' . $url;
        }
    }
    return @file_put_contents($file, implode("\n", $out) . "\n") !== false;
}

function updateDataTxtDomainAndName(string $oldDomain, string $newDomain, string $oldName = '', string $newName = ''): int {
    $file = getDataTxtFilePath();
    if (!is_file($file)) return 0;
    $entries = readDataTxtEntries();
    $updatedCount = 0;
    $oldHost = extractDomainAuthority($oldDomain);
    $newNormUrl = normalizeServerBaseUrlApi($newDomain);

    foreach ($entries as &$entry) {
        $matchedByName = ($oldName !== '' && strcasecmp(trim($entry['name']), trim($oldName)) === 0);
        $repUrl = ($oldDomain !== '') ? replaceDomainInSingleUrl($entry['url'], $oldDomain, $newDomain) : null;
        if ($matchedByName || $repUrl !== null || ($oldHost !== '' && strcasecmp($entry['domain'], $oldHost) === 0)) {
            if ($repUrl !== null) {
                $entry['url'] = $repUrl;
            } elseif ($newNormUrl !== '') {
                $entry['url'] = $newNormUrl;
            }
            $entry['domain'] = extractDomainAuthority($entry['url']);
            if ($newName !== '') {
                $entry['name'] = trim($newName);
            }
            $updatedCount++;
        }
    }
    unset($entry);

    if ($updatedCount > 0) {
        writeDataTxtEntries($entries);
    }
    return $updatedCount;
}

function recordDomainMigration(PDO $db, string $oldDomain, string $newDomain, string $serverName = '', string $scope = 'global', ?int $resellerId = null): void {
    $oldHost = extractDomainAuthority($oldDomain);
    $newHost = extractDomainAuthority($newDomain);
    $newNormUrl = normalizeServerBaseUrlApi($newDomain);
    if ($oldHost === '' || $newHost === '' || strcasecmp($oldHost, $newHost) === 0) return;

    $dbs = [$db];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;

    foreach ($dbs as $pdo) {
        try {
            $pdo->exec("
                CREATE TABLE IF NOT EXISTS domain_migrations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    old_domain TEXT NOT NULL,
                    new_domain TEXT NOT NULL,
                    server_name TEXT DEFAULT '',
                    scope TEXT NOT NULL DEFAULT 'global',
                    reseller_id INTEGER DEFAULT NULL,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            ");
            $pdo->prepare("UPDATE domain_migrations SET new_domain = ?, server_name = CASE WHEN ? != '' THEN ? ELSE server_name END, updated_at = datetime('now') WHERE LOWER(new_domain) = LOWER(?) OR LOWER(new_domain) = LOWER(?)")
                ->execute([$newNormUrl, $serverName, $serverName, $oldHost, normalizeServerBaseUrlApi($oldDomain)]);

            $pdo->prepare("DELETE FROM domain_migrations WHERE LOWER(old_domain) = LOWER(?)")->execute([$oldHost]);
            $pdo->prepare("INSERT INTO domain_migrations (old_domain, new_domain, server_name, scope, reseller_id, updated_at) VALUES (?, ?, ?, ?, ?, datetime('now'))")
                ->execute([$oldHost, $newNormUrl, $serverName, $scope, $resellerId]);
        } catch (Throwable $e) {}
    }
}

function getApplicableDomainMigrations(PDO $db, string $deviceId = ''): array {
    $migrations = [];
    $seen = [];
    $dbs = [$db];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;

    foreach ($dbs as $pdo) {
        try {
            $st = $pdo->query("SELECT old_domain, new_domain, server_name, scope, reseller_id, updated_at FROM domain_migrations ORDER BY id DESC LIMIT 200");
            if ($st) {
                while ($r = $st->fetch(PDO::FETCH_ASSOC)) {
                    $oldH = strtolower(trim((string)($r['old_domain'] ?? '')));
                    $newU = trim((string)($r['new_domain'] ?? ''));
                    if ($oldH === '' || $newU === '' || isset($seen[$oldH])) continue;
                    $seen[$oldH] = true;
                    $migrations[] = [
                        'old_domain'  => $oldH,
                        'new_domain'  => $newU,
                        'new_host'    => extractDomainAuthority($newU),
                        'server_name' => (string)($r['server_name'] ?? ''),
                        'scope'       => (string)($r['scope'] ?? 'global'),
                        'updated_at'  => (string)($r['updated_at'] ?? '')
                    ];
                }
            }
        } catch (Throwable $e) {}
    }
    return $migrations;
}

function bulkRenameAndReplaceServerInPdo(PDO $pdo, string $oldName, string $newName, string $oldUrl, string $newUrl): array {
    $res = ['playlists' => 0, 'devices' => [], 'channels' => 0, 'favorites' => 0];
    $oldUrlTrim = trim($oldUrl);
    $newUrlTrim = trim($newUrl);
    $oldNameTrim = trim($oldName);
    $newNameTrim = trim($newName);

    if ($oldUrlTrim !== '' && $newUrlTrim !== '' && strcasecmp(rtrim($oldUrlTrim, '/'), rtrim($newUrlTrim, '/')) !== 0) {
        $res = bulkReplaceDomainInPdo($pdo, $oldUrlTrim, $newUrlTrim, true);
    }

    try {
        $rows = $pdo->query("SELECT id, device_id, server_name, server_url, m3u_url FROM customer_xtream_lists")->fetchAll(PDO::FETCH_ASSOC);
        $upd = $pdo->prepare("UPDATE customer_xtream_lists SET server_name = ?, server_url = ?, m3u_url = ?, updated_at = datetime('now') WHERE id = ?");
        foreach ($rows as $r) {
            $curName = trim((string)($r['server_name'] ?? ''));
            $decUrl  = decryptXtreamSecret((string)($r['server_url'] ?? ''));
            $decM3u  = decryptXtreamSecret((string)($r['m3u_url'] ?? ''));
            $nameMatches = ($oldNameTrim !== '' && strcasecmp($curName, $oldNameTrim) === 0);
            $urlMatches  = ($oldUrlTrim !== '' && extractDomainAuthority($decUrl) !== '' && strcasecmp(extractDomainAuthority($decUrl), extractDomainAuthority($oldUrlTrim)) === 0);

            if ($nameMatches || $urlMatches) {
                $finalName = ($newNameTrim !== '' && ($nameMatches || $curName === '' || $curName === 'XTREAM VIP')) ? $newNameTrim : $curName;
                $finalUrlPlain = $decUrl;
                $finalM3uPlain = $decM3u;
                if ($newUrlTrim !== '') {
                    if ($oldUrlTrim !== '') {
                        $rep1 = replaceDomainInSingleUrl($decUrl, $oldUrlTrim, $newUrlTrim);
                        if ($rep1 !== null) $finalUrlPlain = $rep1;
                        $rep2 = replaceDomainInSingleUrl($decM3u, $oldUrlTrim, $newUrlTrim);
                        if ($rep2 !== null) $finalM3uPlain = $rep2;
                    }
                    if ($nameMatches && $finalUrlPlain === $decUrl && extractDomainAuthority($decUrl) !== extractDomainAuthority($newUrlTrim)) {
                        $repByCur = replaceDomainInSingleUrl($decUrl, $decUrl, $newUrlTrim);
                        if ($repByCur !== null) $finalUrlPlain = $repByCur;
                    }
                }
                if ($finalName !== $curName || $finalUrlPlain !== $decUrl || $finalM3uPlain !== $decM3u) {
                    $encUrl = (strpos((string)$r['server_url'], 'ENC') === 0) ? encryptXtreamSecret($finalUrlPlain) : $finalUrlPlain;
                    $encM3u = ($finalM3uPlain !== '' && strpos((string)$r['m3u_url'], 'ENC') === 0) ? encryptXtreamSecret($finalM3uPlain) : $finalM3uPlain;
                    $upd->execute([$finalName, $encUrl, $encM3u, $r['id']]);
                    $res['playlists']++;
                    $devId = strtoupper(trim((string)($r['device_id'] ?? '')));
                    if ($devId !== '') $res['devices'][$devId] = true;
                }
            }
        }
    } catch (Throwable $e) {}

    $res['devices'] = array_keys($res['devices']);
    return $res;
}

function isGenericServerNameApi(string $name): bool {
    $n = strtolower(trim($name));
    if ($n === '') return true;
    return in_array($n, [
        'vip server', 'xtream vip', 'xtream', 'قائمة xtream', 'خدمة xtream',
        'سيرفر مشتركين', 'سيرفر شاشاتي', 'iptv', 'server'
    ], true) || preg_match('/^(xtream|حساب|قائمة)\s*#?\d*$/iu', trim($name)) === 1;
}

function getAllKnownDiscoveryServers(PDO $primaryDb): array {
    $byUrl = [];
    foreach (readDataTxtEntries() as $dt) {
        $u = normalizeServerBaseUrlApi((string)($dt['url'] ?? ''));
        if ($u === '') continue;
        $key = strtolower($u);
        $byUrl[$key] = [
            'name'   => trim((string)($dt['name'] ?? 'VIP Server')) ?: 'VIP Server',
            'url'    => $u,
            'domain' => extractDomainAuthority($u)
        ];
    }

    $dbs = [$primaryDb];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;

    foreach ($dbs as $pdo) {
        try {
            $st = $pdo->query("SELECT server_name, server_url FROM customer_xtream_lists WHERE server_url IS NOT NULL AND server_url != '' ORDER BY id DESC LIMIT 500");
            if ($st) {
                while ($r = $st->fetch(PDO::FETCH_ASSOC)) {
                    $decU = normalizeServerBaseUrlApi(decryptXtreamSecret((string)($r['server_url'] ?? '')));
                    if ($decU === '') continue;
                    $sName = trim((string)($r['server_name'] ?? ''));
                    $key = strtolower($decU);
                    if (!isset($byUrl[$key])) {
                        $byUrl[$key] = [
                            'name'   => $sName !== '' ? $sName : 'VIP Server',
                            'url'    => $decU,
                            'domain' => extractDomainAuthority($decU)
                        ];
                    } elseif (!isGenericServerNameApi($sName) && isGenericServerNameApi($byUrl[$key]['name'])) {
                        $byUrl[$key]['name'] = $sName;
                    }
                }
            }
        } catch (Throwable $e) {}
    }

    return array_values($byUrl);
}

function resolveRealServerNameApi(PDO $db, string $rawName, string $serverUrl): string {
    $normUrl = normalizeServerBaseUrlApi($serverUrl);
    $cleanName = trim($rawName);
    $targetAuth = strtolower(extractDomainAuthority($normUrl));
    $targetHostOnly = explode(':', $targetAuth)[0];

    $matchedKnownName = '';
    foreach (getAllKnownDiscoveryServers($db) as $k) {
        $kName = trim((string)($k['name'] ?? ''));
        if ($kName === '' || isGenericServerNameApi($kName)) continue;
        $kUrl = normalizeServerBaseUrlApi((string)($k['url'] ?? ''));
        $kAuth = strtolower(extractDomainAuthority($kUrl));
        $kHostOnly = explode(':', $kAuth)[0];
        if ($kUrl === $normUrl || ($targetAuth !== '' && $kAuth === $targetAuth)) {
            $matchedKnownName = $kName;
            break;
        }
        if ($matchedKnownName === '' && $targetHostOnly !== '' && $kHostOnly === $targetHostOnly) {
            $matchedKnownName = $kName;
        }
    }

    if ($cleanName !== '' &&
        !isGenericServerNameApi($cleanName) &&
        stripos($cleanName, 'http://') === false &&
        stripos($cleanName, 'https://') === false &&
        ($targetHostOnly === '' || strcasecmp($cleanName, $targetHostOnly) !== 0)
    ) {
        return $cleanName;
    }

    if ($matchedKnownName !== '') {
        return $matchedKnownName;
    }

    if ($cleanName !== '' && stripos($cleanName, 'http') === false) {
        return $cleanName;
    }
    return 'VIP Server';
}

function learnServerToDataTxtApi(PDO $db, string $serverName, string $serverUrl): void {
    $normUrl = normalizeServerBaseUrlApi($serverUrl);
    if ($normUrl === '' || extractDomainAuthority($normUrl) === '') return;
    $realName = resolveRealServerNameApi($db, $serverName, $normUrl);
    $cleanName = trim(str_replace(['|', "\r", "\n"], ' ', $realName)) ?: 'VIP Server';

    $entries = readDataTxtEntries();
    $found = false;
    $updated = false;
    foreach ($entries as &$e) {
        if (strcasecmp($e['url'], $normUrl) === 0 || strcasecmp($e['domain'], extractDomainAuthority($normUrl)) === 0) {
            $found = true;
            if (!isGenericServerNameApi($cleanName) && isGenericServerNameApi($e['name'])) {
                $e['name'] = $cleanName;
                $updated = true;
            }
            break;
        }
    }
    unset($e);
    if (!$found) {
        $entries[] = [
            'index'  => count($entries),
            'name'   => $cleanName,
            'url'    => $normUrl,
            'domain' => extractDomainAuthority($normUrl)
        ];
        $updated = true;
    }
    if ($updated) {
        writeDataTxtEntries($entries);
    }
}

function replaceDomainInSingleUrl(string $rawUrl, string $oldDomainInput, string $newDomainInput): ?string {
    $decUrl = trim(decryptXtreamSecret($rawUrl));
    if ($decUrl === '') return null;

    $oldClean = rtrim(trim($oldDomainInput), '/');
    $newClean = rtrim(trim($newDomainInput), '/');
    if ($oldClean === '' || $newClean === '') return null;

    $oldHasScheme = (bool)preg_match('#^https?://#i', $oldClean);
    $newHasScheme = (bool)preg_match('#^https?://#i', $newClean);

    $oldForParse = $oldHasScheme ? $oldClean : ('http://' . $oldClean);
    $newForParse = $newHasScheme ? $newClean : ('http://' . $newClean);

    $oldParts = @parse_url($oldForParse);
    $newParts = @parse_url($newForParse);
    if (!is_array($oldParts) || empty($oldParts['host']) || !is_array($newParts) || empty($newParts['host'])) {
        return null;
    }

    $oldHost = strtolower($oldParts['host']);
    $oldPort = $oldParts['port'] ?? null;

    $newScheme = isset($newParts['scheme']) ? strtolower($newParts['scheme']) : 'http';
    $newHost   = $newParts['host'];
    $newPort   = $newParts['port'] ?? null;

    // فصل أي ترويسات مضافة بعد علامة | (مثل |User-Agent=...)
    $pipePos = strpos($decUrl, '|');
    $mainUrl = $pipePos !== false ? substr($decUrl, 0, $pipePos) : $decUrl;
    $pipeSuffix = $pipePos !== false ? substr($decUrl, $pipePos) : '';

    $uHasScheme = (bool)preg_match('#^[a-zA-Z][a-zA-Z0-9+\-.]*://#', $mainUrl);
    $uForParse  = $uHasScheme ? $mainUrl : ('http://' . $mainUrl);
    $uParts     = @parse_url($uForParse);

    if (is_array($uParts) && !empty($uParts['host'])) {
        $uHost = strtolower($uParts['host']);
        $uPort = $uParts['port'] ?? null;

        $hostMatches = ($uHost === $oldHost);
        $portMatches = ($oldPort === null) || ((int)$uPort === (int)$oldPort);

        if ($hostMatches && $portMatches) {
            $finalScheme = $newHasScheme ? $newScheme : ($uParts['scheme'] ?? 'http');
            $finalPort = null;
            if ($newPort !== null) {
                $finalPort = $newPort;
            } elseif (!$newHasScheme && $oldPort === null && $uPort !== null) {
                // إذا كتب الأدمن اسم الدومين فقط بدون http وبدون منفذ، نحافظ على منفذ الرابط الأصلي
                $finalPort = $uPort;
            }
            $rebuilt = ($uHasScheme || $newHasScheme ? ($finalScheme . '://') : '')
                . $newHost
                . ($finalPort !== null ? (':' . $finalPort) : '')
                . ($uParts['path'] ?? '')
                . (isset($uParts['query']) && $uParts['query'] !== '' ? ('?' . $uParts['query']) : '')
                . (isset($uParts['fragment']) && $uParts['fragment'] !== '' ? ('#' . $uParts['fragment']) : '');
            $finalUrl = $rebuilt . $pipeSuffix;
            return ($finalUrl !== $decUrl) ? $finalUrl : null;
        }
    }

    // احتياطي: استبدال نصي غير حساس لحالة الأحرف إذا كان الدومين القديم جزءاً من الرابط
    if (stripos($decUrl, $oldClean) !== false) {
        $replaced = str_ireplace($oldClean, $newClean, $decUrl);
        return ($replaced !== $decUrl) ? $replaced : null;
    }

    return null;
}

function collectActiveDomainsSummary(PDO $primaryDb): array {
    $counts = [];
    $dbs = [$primaryDb];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;

    // ربط أسماء السيرفرات من data.txt
    $dataTxtByHost = [];
    foreach (readDataTxtEntries() as $dt) {
        $hKey = strtolower(trim($dt['domain']));
        if ($hKey !== '') {
            $dataTxtByHost[$hKey] = $dt;
        }
    }

    $seenRows = [];
    foreach ($dbs as $db) {
        try {
            $rows = $db->query("SELECT device_id, playlist_id, server_url, server_name FROM customer_xtream_lists")->fetchAll(PDO::FETCH_ASSOC);
            foreach ($rows as $r) {
                $devId = strtoupper(trim((string)($r['device_id'] ?? '')));
                $dedupKey = $devId . '|' . trim((string)($r['playlist_id'] ?? ''));
                if (isset($seenRows[$dedupKey])) continue;
                $seenRows[$dedupKey] = true;

                $decUrl = trim(decryptXtreamSecret((string)($r['server_url'] ?? '')));
                if ($decUrl === '') continue;
                $forParse = preg_match('#^https?://#i', $decUrl) ? $decUrl : ('http://' . $decUrl);
                $p = @parse_url($forParse);
                if (!is_array($p) || empty($p['host'])) continue;
                $hostPort = strtolower($p['host']) . (!empty($p['port']) ? (':' . $p['port']) : '');
                $base = strtolower($p['scheme'] ?? 'http') . '://' . $hostPort;

                if (!isset($counts[$hostPort])) {
                    $counts[$hostPort] = [
                        'domain'          => $base,
                        'host'            => $hostPort,
                        'playlists_count' => 0,
                        'devices_map'     => [],
                        'channels_count'  => 0,
                        'names_map'       => [],
                        'in_data_txt'     => isset($dataTxtByHost[$hostPort])
                    ];
                    if (isset($dataTxtByHost[$hostPort]) && !empty($dataTxtByHost[$hostPort]['name'])) {
                        $counts[$hostPort]['names_map'][$dataTxtByHost[$hostPort]['name']] = true;
                    }
                }
                $counts[$hostPort]['playlists_count']++;
                if ($devId !== '') {
                    $counts[$hostPort]['devices_map'][$devId] = true;
                }
                $srvName = trim((string)($r['server_name'] ?? ''));
                if ($srvName !== '' && stripos($srvName, 'http') === false) {
                    $counts[$hostPort]['names_map'][$srvName] = true;
                }
            }
        } catch (Throwable $e) {}
    }

    try {
        $chRows = $primaryDb->query("SELECT original_url FROM channels WHERE original_url IS NOT NULL AND original_url != ''")->fetchAll(PDO::FETCH_ASSOC);
        foreach ($chRows as $ch) {
            $u = trim(explode('|', (string)$ch['original_url'], 2)[0]);
            $p = @parse_url($u);
            if (!is_array($p) || empty($p['host'])) continue;
            $hostPort = strtolower($p['host']) . (!empty($p['port']) ? (':' . $p['port']) : '');
            $base = strtolower($p['scheme'] ?? 'http') . '://' . $hostPort;
            if (!isset($counts[$hostPort])) {
                $counts[$hostPort] = [
                    'domain'          => $base,
                    'host'            => $hostPort,
                    'playlists_count' => 0,
                    'devices_map'     => [],
                    'channels_count'  => 0,
                    'names_map'       => [],
                    'in_data_txt'     => isset($dataTxtByHost[$hostPort])
                ];
            }
            $counts[$hostPort]['channels_count']++;
        }
    } catch (Throwable $e) {}

    // إضافة دومينات data.txt المسجلة أيضاً حتى تظهر في الكشف الشامل للأدمن
    foreach ($dataTxtByHost as $hostPort => $dt) {
        if (!isset($counts[$hostPort])) {
            $counts[$hostPort] = [
                'domain'          => $dt['url'],
                'host'            => $hostPort,
                'playlists_count' => 0,
                'devices_map'     => [],
                'channels_count'  => 0,
                'names_map'       => [$dt['name'] => true],
                'in_data_txt'     => true
            ];
        }
    }

    $list = [];
    foreach ($counts as $info) {
        $names = array_keys($info['names_map']);
        $list[] = [
            'domain'          => $info['domain'],
            'host'            => $info['host'],
            'server_name'     => !empty($names) ? implode(' • ', array_slice($names, 0, 2)) : 'سيرفر مشتركين',
            'playlists_count' => (int)$info['playlists_count'],
            'devices_count'   => count($info['devices_map']),
            'channels_count'  => (int)$info['channels_count'],
            'in_data_txt'     => (bool)$info['in_data_txt']
        ];
    }

    usort($list, function($a, $b) {
        $scoreA = ($a['playlists_count'] * 100) + ($a['devices_count'] * 10) + $a['channels_count'];
        $scoreB = ($b['playlists_count'] * 100) + ($b['devices_count'] * 10) + $b['channels_count'];
        if ($scoreA !== $scoreB) return $scoreB <=> $scoreA;
        return strcmp($a['host'], $b['host']);
    });
    return $list;
}

function getPinProtectedDevicesMap(PDO $primaryDb): array {
    $map = [];
    $dbs = [$primaryDb];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;
    foreach ($dbs as $db) {
        try {
            $rows = $db->query("SELECT device_id FROM portal_security_pins WHERE pin_hash IS NOT NULL AND pin_hash != ''")->fetchAll(PDO::FETCH_ASSOC);
            foreach ($rows as $r) {
                $d = strtoupper(trim((string)($r['device_id'] ?? '')));
                if ($d !== '') $map[$d] = true;
            }
        } catch (Throwable $e) {}
    }
    return $map;
}

function removeDevicePinAcrossAllDbs(PDO $primaryDb, string $rawDeviceId): int {
    $devId = normalizeDeviceCodeApi($rawDeviceId);
    if ($devId === '') return 0;
    $removed = 0;
    $dbs = [$primaryDb];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;
    foreach ($dbs as $db) {
        try {
            $db->exec("CREATE TABLE IF NOT EXISTS portal_security_pins (device_id TEXT PRIMARY KEY, pin_hash TEXT NOT NULL, updated_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            $st = $db->prepare("DELETE FROM portal_security_pins WHERE UPPER(TRIM(device_id)) = ?");
            $st->execute([$devId]);
            $removed += $st->rowCount();
        } catch (Throwable $e) {}
    }
    return $removed;
}

function getResellerOwnedDeviceIds(PDO $db, int $resellerId): array {
    $devices = [];
    // نشمل الشاشات التي فعلها الموزع نفسه أو الموزعون الفرعيون التابعون له
    $resellerIds = [$resellerId];
    try {
        $subSt = $db->prepare("SELECT id FROM resellers WHERE parent_reseller_id = ?");
        $subSt->execute([$resellerId]);
        foreach ($subSt->fetchAll(PDO::FETCH_COLUMN) as $sid) {
            $resellerIds[] = (int)$sid;
        }
    } catch (Throwable $e) {}

    $inPlaceholders = implode(',', array_fill(0, count($resellerIds), '?'));

    try {
        $st1 = $db->prepare("SELECT DISTINCT used_by_device FROM activation_cards WHERE reseller_id IN ($inPlaceholders) AND used_by_device IS NOT NULL AND used_by_device != '' AND used_by_device != 'ADMIN_BURN'");
        $st1->execute($resellerIds);
        foreach ($st1->fetchAll(PDO::FETCH_COLUMN) as $d) {
            $norm = normalizeDeviceCodeApi((string)$d);
            if (preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $norm)) $devices[$norm] = true;
        }
    } catch (Throwable $e) {}

    try {
        $st2 = $db->prepare("SELECT DISTINCT target_device_id FROM reseller_transactions WHERE reseller_id IN ($inPlaceholders) AND target_device_id IS NOT NULL AND target_device_id != ''");
        $st2->execute($resellerIds);
        foreach ($st2->fetchAll(PDO::FETCH_COLUMN) as $d) {
            $norm = normalizeDeviceCodeApi((string)$d);
            if (preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $norm)) $devices[$norm] = true;
        }
    } catch (Throwable $e) {}

    try {
        $st3 = $db->prepare("SELECT DISTINCT r.redeemed_by_device FROM card_redemptions r JOIN activation_cards c ON c.code = r.code COLLATE NOCASE WHERE c.reseller_id IN ($inPlaceholders)");
        $st3->execute($resellerIds);
        foreach ($st3->fetchAll(PDO::FETCH_COLUMN) as $d) {
            $norm = normalizeDeviceCodeApi((string)$d);
            if (preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $norm)) $devices[$norm] = true;
        }
    } catch (Throwable $e) {}

    try {
        $st4 = $db->prepare("SELECT DISTINCT device_id FROM users WHERE reseller_id IN ($inPlaceholders)");
        $st4->execute($resellerIds);
        foreach ($st4->fetchAll(PDO::FETCH_COLUMN) as $d) {
            $norm = normalizeDeviceCodeApi((string)$d);
            if (preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $norm)) $devices[$norm] = true;
        }
    } catch (Throwable $e) {}

    return array_keys($devices);
}

function collectResellerActiveDomainsSummary(PDO $primaryDb, array $ownedDevices): array {
    if (empty($ownedDevices)) return [];
    $ownedSet = array_flip(array_map('strtoupper', $ownedDevices));
    $counts = [];
    $dbs = [$primaryDb];
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) $dbs[] = $sec;

    $seenRows = [];
    foreach ($dbs as $db) {
        try {
            $rows = $db->query("SELECT device_id, playlist_id, server_url, server_name FROM customer_xtream_lists")->fetchAll(PDO::FETCH_ASSOC);
            foreach ($rows as $r) {
                $devId = strtoupper(trim((string)($r['device_id'] ?? '')));
                if (!isset($ownedSet[$devId])) continue;
                $dedupKey = $devId . '|' . trim((string)($r['playlist_id'] ?? ''));
                if (isset($seenRows[$dedupKey])) continue;
                $seenRows[$dedupKey] = true;

                $decUrl = trim(decryptXtreamSecret((string)($r['server_url'] ?? '')));
                if ($decUrl === '') continue;
                $forParse = preg_match('#^https?://#i', $decUrl) ? $decUrl : ('http://' . $decUrl);
                $p = @parse_url($forParse);
                if (!is_array($p) || empty($p['host'])) continue;
                $hostPort = strtolower($p['host']) . (!empty($p['port']) ? (':' . $p['port']) : '');
                $base = strtolower($p['scheme'] ?? 'http') . '://' . $hostPort;
                if (!isset($counts[$hostPort])) {
                    $counts[$hostPort] = [
                        'domain'          => $base,
                        'host'            => $hostPort,
                        'playlists_count' => 0,
                        'devices_map'     => [],
                        'names_map'       => []
                    ];
                }
                $counts[$hostPort]['playlists_count']++;
                $counts[$hostPort]['devices_map'][$devId] = true;
                $srvName = trim((string)($r['server_name'] ?? ''));
                if ($srvName !== '' && stripos($srvName, 'http') === false) {
                    $counts[$hostPort]['names_map'][$srvName] = true;
                }
            }
        } catch (Throwable $e) {}
    }

    $list = [];
    foreach ($counts as $info) {
        $names = array_keys($info['names_map']);
        $list[] = [
            'domain'          => $info['domain'],
            'host'            => $info['host'],
            'server_name'     => !empty($names) ? implode(' • ', array_slice($names, 0, 2)) : 'سيرفر شاشاتي',
            'playlists_count' => (int)$info['playlists_count'],
            'devices_count'   => count($info['devices_map'])
        ];
    }
    usort($list, fn($a, $b) => $b['playlists_count'] <=> $a['playlists_count']);
    return $list;
}

function bulkReplaceDomainForOwnedDevicesInPdo(PDO $db, array $ownedDevices, string $oldDomain, string $newDomain): array {
    if (empty($ownedDevices)) return ['playlists' => 0, 'devices' => [], 'favorites' => 0];
    $ownedSet = array_flip(array_map('strtoupper', $ownedDevices));
    $updatedPlaylists = 0;
    $updatedDevices = [];
    $updatedFavorites = 0;

    $db->beginTransaction();
    try {
        $rows = $db->query("SELECT id, device_id, server_url, m3u_url FROM customer_xtream_lists")->fetchAll(PDO::FETCH_ASSOC);
        $updStmt = $db->prepare("UPDATE customer_xtream_lists SET server_url = ?, m3u_url = ?, updated_at = datetime('now') WHERE id = ?");
        foreach ($rows as $r) {
            $devKey = strtoupper(trim((string)($r['device_id'] ?? '')));
            if (!isset($ownedSet[$devKey])) continue;

            $rawSrv = (string)($r['server_url'] ?? '');
            $rawM3u = (string)($r['m3u_url'] ?? '');
            $wasEncrypted = str_starts_with($rawSrv, 'ENCv9:') || str_starts_with($rawSrv, 'ENC:');

            $newSrvPlain = replaceDomainInSingleUrl($rawSrv, $oldDomain, $newDomain);
            $newM3uPlain = $rawM3u !== '' ? replaceDomainInSingleUrl($rawM3u, $oldDomain, $newDomain) : null;

            if ($newSrvPlain !== null || $newM3uPlain !== null) {
                $finalSrv = $newSrvPlain !== null
                    ? ($wasEncrypted ? encryptXtreamSecret($newSrvPlain) : $newSrvPlain)
                    : $rawSrv;
                $finalM3u = $newM3uPlain !== null ? $newM3uPlain : $rawM3u;
                $updStmt->execute([$finalSrv, $finalM3u, $r['id']]);
                $updatedPlaylists++;
                $updatedDevices[$devKey] = true;
            }
        }

        $favRows = $db->query("SELECT device_id, favorites_json FROM device_favorites")->fetchAll(PDO::FETCH_ASSOC);
        $updFav = $db->prepare("UPDATE device_favorites SET favorites_json = ?, updated_at = datetime('now') WHERE device_id = ?");
        foreach ($favRows as $fr) {
            $devKey = strtoupper(trim((string)($fr['device_id'] ?? '')));
            if (!isset($ownedSet[$devKey])) continue;
            $arr = json_decode((string)($fr['favorites_json'] ?? '[]'), true);
            if (!is_array($arr) || empty($arr)) continue;
            $changed = false;
            foreach ($arr as &$item) {
                if (!is_array($item)) continue;
                foreach (['url', 'streamBaseUrl', 'referer', 'origin'] as $fKey) {
                    if (!empty($item[$fKey]) && is_string($item[$fKey])) {
                        $rep = replaceDomainInSingleUrl($item[$fKey], $oldDomain, $newDomain);
                        if ($rep !== null) {
                            $item[$fKey] = $rep;
                            $changed = true;
                        }
                    }
                }
            }
            unset($item);
            if ($changed) {
                $updFav->execute([json_encode($arr, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), $fr['device_id']]);
                $updatedFavorites++;
            }
        }

        $db->commit();
    } catch (Throwable $e) {
        if ($db->inTransaction()) $db->rollBack();
        throw $e;
    }

    return [
        'playlists' => $updatedPlaylists,
        'devices'   => array_keys($updatedDevices),
        'favorites' => $updatedFavorites
    ];
}

function bulkReplaceDomainInPdo(PDO $db, string $oldDomain, string $newDomain, bool $isPrimary): array {
    $updatedPlaylists = 0;
    $updatedDevices = [];
    $updatedChannels = 0;
    $updatedFavorites = 0;

    $db->beginTransaction();
    try {
        // 1) تحديث قوائم XTREAM الخاصة بجميع المستخدمين (customer_xtream_lists) مع دعم فك التشفير
        try {
            $rows = $db->query("SELECT id, device_id, server_url, m3u_url FROM customer_xtream_lists")->fetchAll(PDO::FETCH_ASSOC);
            $updStmt = $db->prepare("UPDATE customer_xtream_lists SET server_url = ?, m3u_url = ?, updated_at = datetime('now') WHERE id = ?");
            foreach ($rows as $r) {
                $rawSrv = (string)($r['server_url'] ?? '');
                $rawM3u = (string)($r['m3u_url'] ?? '');
                $wasEncrypted = str_starts_with($rawSrv, 'ENCv9:') || str_starts_with($rawSrv, 'ENC:');

                $newSrvPlain = replaceDomainInSingleUrl($rawSrv, $oldDomain, $newDomain);
                $newM3uPlain = $rawM3u !== '' ? replaceDomainInSingleUrl($rawM3u, $oldDomain, $newDomain) : null;

                if ($newSrvPlain !== null || $newM3uPlain !== null) {
                    $finalSrv = $newSrvPlain !== null
                        ? ($wasEncrypted ? encryptXtreamSecret($newSrvPlain) : $newSrvPlain)
                        : $rawSrv;
                    $finalM3u = $newM3uPlain !== null ? $newM3uPlain : $rawM3u;
                    $updStmt->execute([$finalSrv, $finalM3u, $r['id']]);
                    $updatedPlaylists++;
                    $devKey = strtoupper(trim((string)($r['device_id'] ?? '')));
                    if ($devKey !== '') $updatedDevices[$devKey] = true;
                }
            }
        } catch (Throwable $e) {}

        // 2) تحديث جدول القنوات (channels) في قاعدة البيانات الرئيسية
        if ($isPrimary) {
            try {
                $chRows = $db->query("SELECT id, original_url FROM channels")->fetchAll(PDO::FETCH_ASSOC);
                $updCh = $db->prepare("UPDATE channels SET original_url = ?, encrypted_url = ? WHERE id = ?");
                foreach ($chRows as $ch) {
                    $origUrl = (string)($ch['original_url'] ?? '');
                    $newChUrl = replaceDomainInSingleUrl($origUrl, $oldDomain, $newDomain);
                    if ($newChUrl !== null) {
                        $updCh->execute([$newChUrl, encryptUrl($newChUrl), $ch['id']]);
                        $updatedChannels++;
                    }
                }
            } catch (Throwable $e) {}
        }

        // 3) تحديث المفضلة السحابية لجميع الأجهزة (device_favorites)
        try {
            $favRows = $db->query("SELECT device_id, favorites_json FROM device_favorites")->fetchAll(PDO::FETCH_ASSOC);
            $updFav = $db->prepare("UPDATE device_favorites SET favorites_json = ?, updated_at = datetime('now') WHERE device_id = ?");
            foreach ($favRows as $fr) {
                $arr = json_decode((string)($fr['favorites_json'] ?? '[]'), true);
                if (!is_array($arr) || empty($arr)) continue;
                $changed = false;
                foreach ($arr as &$item) {
                    if (!is_array($item)) continue;
                    foreach (['url', 'streamBaseUrl', 'referer', 'origin'] as $fKey) {
                        if (!empty($item[$fKey]) && is_string($item[$fKey])) {
                            $rep = replaceDomainInSingleUrl($item[$fKey], $oldDomain, $newDomain);
                            if ($rep !== null) {
                                $item[$fKey] = $rep;
                                $changed = true;
                            }
                        }
                    }
                }
                unset($item);
                if ($changed) {
                    $updFav->execute([json_encode($arr, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), $fr['device_id']]);
                    $updatedFavorites++;
                }
            }
        } catch (Throwable $e) {}

        $db->commit();
    } catch (Throwable $e) {
        if ($db->inTransaction()) $db->rollBack();
        throw $e;
    }

    return [
        'playlists' => $updatedPlaylists,
        'devices'   => array_keys($updatedDevices),
        'channels'  => $updatedChannels,
        'favorites' => $updatedFavorites
    ];
}

function ensureDeviceFavoritesSchema(PDO $pdo): void {
    try {
        $pdo->exec("
            CREATE TABLE IF NOT EXISTS device_favorites (
                device_id TEXT PRIMARY KEY NOT NULL,
                favorites_json TEXT NOT NULL DEFAULT '[]',
                items_count INTEGER DEFAULT 0,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        ");
        $cols = array_column($pdo->query("PRAGMA table_info(device_favorites)")->fetchAll(PDO::FETCH_ASSOC), 'name');
        if (!in_array('favorites_json', $cols, true)) $pdo->exec("ALTER TABLE device_favorites ADD COLUMN favorites_json TEXT NOT NULL DEFAULT '[]'");
        if (!in_array('items_count', $cols, true))    $pdo->exec("ALTER TABLE device_favorites ADD COLUMN items_count INTEGER DEFAULT 0");
        if (!in_array('updated_at', $cols, true))     $pdo->exec("ALTER TABLE device_favorites ADD COLUMN updated_at TEXT DEFAULT CURRENT_TIMESTAMP");
    } catch (Throwable $e) {}
}

function getSecondaryPortalPdo(): ?PDO {
    $portalDbFile = __DIR__ . '/livepro_portal.sqlite';
    if (!is_file($portalDbFile)) return null;
    try {
        $p = new PDO('sqlite:' . $portalDbFile, null, null, [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
        ]);
        ensureDeviceFavoritesSchema($p);
        return $p;
    } catch (Throwable $e) {
        return null;
    }
}

function computeFavoriteItemIdentityKey(array $item): string {
    $ctype = strtoupper(trim((string)($item['contentType'] ?? 'LIVE_TV')));
    $pid   = trim((string)($item['playlistId'] ?? 'PORTAL_MAIN'));
    $sid   = (int)($item['seriesId'] ?? 0);
    $stmId = (int)($item['streamId'] ?? 0);
    $url   = strtolower(trim((string)($item['url'] ?? '')));
    $name  = mb_strtolower(trim((string)($item['name'] ?? '')), 'UTF-8');
    if ($ctype === 'SERIES' && $sid > 0) {
        return "FAV_SERIES_{$pid}_{$sid}";
    }
    if ($stmId > 0) {
        return "FAV_{$ctype}_{$pid}_{$stmId}";
    }
    if ($url !== '') {
        return "FAV_{$ctype}_{$url}";
    }
    return "FAV_{$ctype}_{$pid}_{$name}";
}

function doesFavoriteItemMatchDeletedSet(array $item, array $deletedLookup): bool {
    if (empty($deletedLookup)) return false;
    $idKey = computeFavoriteItemIdentityKey($item);
    if (isset($deletedLookup[$idKey])) return true;
    $ctype = strtoupper(trim((string)($item['contentType'] ?? 'LIVE_TV')));
    $pid   = trim((string)($item['playlistId'] ?? 'PORTAL_MAIN'));
    $sid   = (int)($item['seriesId'] ?? 0);
    $stmId = (int)($item['streamId'] ?? 0);
    $id    = (int)($item['id'] ?? 0);
    $url   = strtolower(trim((string)($item['url'] ?? '')));
    $stableKey = "{$pid}_{$ctype}_{$id}_" . ($stmId > 0 ? $stmId : $sid);
    if (isset($deletedLookup[$stableKey])) return true;
    if ($url !== '' && isset($deletedLookup[$url])) return true;
    if ($ctype === 'SERIES' && $sid > 0 && isset($deletedLookup["S|{$pid}|{$sid}"])) return true;
    if ($stmId > 0 && isset($deletedLookup["STM|{$ctype}|{$pid}|{$stmId}"])) return true;
    return false;
}

function writeDeviceFavoritesToPdo(PDO $pdo, string $reqDeviceId, string $storeJson, int $count): void {
    ensureDeviceFavoritesSchema($pdo);
    $ownTx = !$pdo->inTransaction();
    if ($ownTx) $pdo->beginTransaction();
    try {
        $pdo->prepare("DELETE FROM device_favorites WHERE UPPER(TRIM(device_id)) = ?")->execute([$reqDeviceId]);
        $pdo->prepare("INSERT INTO device_favorites (device_id, favorites_json, items_count, updated_at) VALUES (?, ?, ?, datetime('now'))")
            ->execute([$reqDeviceId, $storeJson, $count]);
        if ($ownTx) $pdo->commit();
    } catch (Throwable $e) {
        if ($ownTx && $pdo->inTransaction()) $pdo->rollBack();
        throw $e;
    }
}

function syncFavoritesAcrossAllDbs(PDO $primaryDb, string $reqDeviceId, string $storeJson, int $count): void {
    writeDeviceFavoritesToPdo($primaryDb, $reqDeviceId, $storeJson, $count);
    $sec = getSecondaryPortalPdo();
    if ($sec !== null) {
        try {
            writeDeviceFavoritesToPdo($sec, $reqDeviceId, $storeJson, $count);
        } catch (Throwable $e) {}
    }
}

function getDeviceXtreamLists(PDO $db, string $device_id, bool $maskPassword = false): array {
    $device_id = strtoupper(trim($device_id));
    if ($device_id === '') return [];
    try {
        $mergedRows = [];
        $dbs = [$db];
        $sec = getSecondaryPortalPdo();
        if ($sec !== null) $dbs[] = $sec;

        foreach ($dbs as $pdo) {
            try {
                $stmt = $pdo->prepare("SELECT * FROM customer_xtream_lists WHERE UPPER(device_id) = ? ORDER BY order_index ASC, id ASC");
                $stmt->execute([$device_id]);
                foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
                    $pid = (string)($row['playlist_id'] ?? '');
                    if ($pid === '') continue;
                    if (!isset($mergedRows[$pid]) || strcmp((string)($row['updated_at'] ?? ''), (string)($mergedRows[$pid]['updated_at'] ?? '')) > 0) {
                        $mergedRows[$pid] = $row;
                    }
                }
            } catch (Throwable $e) {}
        }

        $migrations = getApplicableDomainMigrations($db, $device_id);
        $dataTxtByName = [];
        $dataTxtByHost = [];
        foreach (readDataTxtEntries() as $dt) {
            $dataTxtByName[strtolower(trim($dt['name']))] = $dt;
            $dataTxtByHost[strtolower(trim($dt['domain']))] = $dt;
        }

        $lists = [];
        foreach (array_values($mergedRows) as $r) {
            $decUrl  = decryptXtreamSecret((string)$r['server_url']);
            $decM3u  = decryptXtreamSecret((string)($r['m3u_url'] ?? ''));
            $decPass = decryptXtreamSecret((string)$r['xtream_pass']);
            $srvName = trim((string)($r['server_name'] ?? ''));
            $changedInDb = false;

            // 1. فحص سجل تغييرات الدومينات (domain_migrations) وتطبيقه تلقائياً
            foreach ($migrations as $mig) {
                $repU = replaceDomainInSingleUrl($decUrl, $mig['old_domain'], $mig['new_domain']);
                if ($repU !== null && $repU !== $decUrl) {
                    $decUrl = $repU;
                    if (!empty($mig['server_name']) && ($srvName === '' || $srvName === 'XTREAM VIP')) {
                        $srvName = $mig['server_name'];
                    }
                    $changedInDb = true;
                }
                if ($decM3u !== '') {
                    $repM = replaceDomainInSingleUrl($decM3u, $mig['old_domain'], $mig['new_domain']);
                    if ($repM !== null && $repM !== $decM3u) {
                        $decM3u = $repM;
                        $changedInDb = true;
                    }
                }
            }

            // 2. إذا كان اسم السيرفر مطابقاً لسيرفر في data.txt وتم تغيير دومينه في data.txt، نحدث الرابط تلقائياً
            $lowName = strtolower($srvName);
            if ($lowName !== '' && isset($dataTxtByName[$lowName])) {
                $targetUrl = $dataTxtByName[$lowName]['url'];
                if ($targetUrl !== '' && extractDomainAuthority($decUrl) !== extractDomainAuthority($targetUrl)) {
                    $repFromDataTxt = replaceDomainInSingleUrl($decUrl, $decUrl, $targetUrl);
                    $decUrl = $repFromDataTxt ?? $targetUrl;
                    $changedInDb = true;
                }
            }

            // 3. استرجاع اسم السيرفر الحقيقي دائماً إذا كان الاسم المسجل عاماً (مثل XTREAM VIP أو VIP Server)
            if (isGenericServerNameApi($srvName)) {
                $resolvedReal = resolveRealServerNameApi($db, $srvName, $decUrl);
                if (!isGenericServerNameApi($resolvedReal) && $resolvedReal !== $srvName) {
                    $srvName = $resolvedReal;
                    $changedInDb = true;
                }
            }

            if ($changedInDb) {
                foreach ($dbs as $pdo) {
                    try {
                        $pdo->prepare("UPDATE customer_xtream_lists SET server_name = ?, server_url = ?, m3u_url = ?, updated_at = datetime('now') WHERE UPPER(device_id) = ? AND playlist_id = ?")
                            ->execute([$srvName, encryptXtreamSecret($decUrl), $decM3u !== '' ? encryptXtreamSecret($decM3u) : '', $device_id, $r['playlist_id']]);
                    } catch (Throwable $e) {}
                }
            }

            $lists[] = [
                'id'          => (string)$r['playlist_id'],
                'playlist_id' => (string)$r['playlist_id'],
                'device_id'   => (string)$r['device_id'],
                'server_name' => $srvName ?: (string)$r['server_name'],
                'server_url'  => $decUrl,
                'username'    => (string)$r['xtream_user'],
                'password'    => $maskPassword ? '••••••••' : $decPass,
                'm3u_url'     => $decM3u,
                'exp_date'    => (string)($r['exp_date'] ?? ''),
                'enabled'     => ((int)($r['enabled'] ?? 1)) === 1,
                'order_index' => (int)($r['order_index'] ?? 0),
                'updated_at'  => (string)($r['updated_at'] ?? '')
            ];
        }
        return $lists;
    } catch (Throwable $e) {
        return [];
    }
}

function saveDeviceXtreamList(PDO $db, string $device_id, array $payload): array {
    $device_id = strtoupper(trim($device_id));
    $playlist_id = trim((string)($payload['playlist_id'] ?? $payload['id'] ?? ''));
    if ($playlist_id === '') {
        $playlist_id = 'PL_' . strtoupper(substr(bin2hex(random_bytes(4)), 0, 8));
    }
    $server_name = trim((string)($payload['server_name'] ?? 'قائمة XTREAM')) ?: 'قائمة XTREAM';
    $server_url  = rtrim(trim((string)($payload['server_url'] ?? '')), '/');
    $xtream_user = trim((string)($payload['xtream_user'] ?? $payload['username'] ?? ''));
    $xtream_pass = trim((string)($payload['xtream_pass'] ?? $payload['password'] ?? ''));
    $m3u_url     = trim((string)($payload['m3u_url'] ?? ''));
    $exp_date    = trim((string)($payload['exp_date'] ?? ''));
    $enabled     = (!isset($payload['enabled']) || !empty($payload['enabled'])) ? 1 : 0;
    $req_order   = isset($payload['order_index']) ? (int)$payload['order_index'] : -1;

    // تجزئة ذكية تلقائية: إذا أدخل المستخدم رابط M3U في خانة السيرفر أو M3U، نستخرج الهوست واسم المستخدم وكلمة المرور تلقائياً!
    $parsedFromUrl = parseXtreamFromM3uLink($server_url) ?: parseXtreamFromM3uLink($m3u_url);
    if ($parsedFromUrl !== null) {
        $server_url = $parsedFromUrl['server_url'];
        if ($xtream_user === '') $xtream_user = $parsedFromUrl['username'];
        if ($xtream_pass === '' || $xtream_pass === '••••••••') $xtream_pass = $parsedFromUrl['password'];
        $m3u_url = '';
    }

    if ($server_url !== '' && !preg_match('#^https?://#i', $server_url)) {
        $server_url = 'http://' . $server_url;
    }
    if ($server_url !== '') {
        $server_name = resolveRealServerNameApi($db, $server_name, $server_url);
        learnServerToDataTxtApi($db, $server_name, $server_url);
    }

    $stmt = $db->prepare("SELECT id, xtream_pass, exp_date FROM customer_xtream_lists WHERE UPPER(device_id) = ? AND playlist_id = ? LIMIT 1");
    $stmt->execute([$device_id, $playlist_id]);
    $existingRow = $stmt->fetch(PDO::FETCH_ASSOC);

    if ($existingRow) {
        if ($xtream_pass === '' || $xtream_pass === '••••••••') {
            $encPass = (string)$existingRow['xtream_pass'];
        } else {
            $encPass = encryptXtreamSecret($xtream_pass);
        }
        if ($exp_date === '') {
            $exp_date = (string)($existingRow['exp_date'] ?? '');
        }
        $finalOrder = $req_order >= 0 ? $req_order : (int)($existingRow['order_index'] ?? 0);
        $db->prepare("
            UPDATE customer_xtream_lists
            SET server_name = ?, server_url = ?, xtream_user = ?, xtream_pass = ?, m3u_url = ?, exp_date = ?, enabled = ?, order_index = ?, updated_at = datetime('now')
            WHERE id = ?
        ")->execute([$server_name, $server_url, $xtream_user, $encPass, $m3u_url, $exp_date, $enabled, $finalOrder, $existingRow['id']]);
    } else {
        $countStmt = $db->prepare("SELECT COUNT(*) FROM customer_xtream_lists WHERE UPPER(device_id) = ?");
        $countStmt->execute([$device_id]);
        $orderIdx = (int)$countStmt->fetchColumn();
        $encPass = encryptXtreamSecret($xtream_pass);

        $db->prepare("
            INSERT INTO customer_xtream_lists (playlist_id, device_id, server_name, server_url, xtream_user, xtream_pass, m3u_url, exp_date, enabled, order_index, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
        ")->execute([$playlist_id, $device_id, $server_name, $server_url, $xtream_user, $encPass, $m3u_url, $exp_date, $enabled, $orderIdx]);
    }

    try {
        $totalLists = count(getDeviceXtreamLists($db, $device_id));
        $xtreamTag = "📲 XTREAM ({$totalLists}): {$server_name}";
        $uStmt = $db->prepare("SELECT id, notes FROM users WHERE device_id = ? LIMIT 1");
        $uStmt->execute([$device_id]);
        $uRow = $uStmt->fetch(PDO::FETCH_ASSOC);
        if ($uRow) {
            $cleanOld = preg_replace('/\s*\|\s*📲 XTREAM.*$/u', '', (string)($uRow['notes'] ?? ''));
            $newNotes = trim($cleanOld) !== '' ? ($cleanOld . ' | ' . $xtreamTag) : $xtreamTag;
            $db->prepare("UPDATE users SET notes = ?, updated_at = datetime('now'), last_seen = datetime('now') WHERE id = ?")
               ->execute([$newNotes, $uRow['id']]);
        }
    } catch (Throwable $e) {}

    logActivity($db, $device_id, 'xtream_saved', ['playlist_id' => $playlist_id, 'server_name' => $server_name, 'server_url' => $server_url]);
    return getDeviceXtreamLists($db, $device_id);
}

/* ============================================================
   🎫 verify_card — فحص فقط (لا يحرق القسيمة)
   ============================================================ */

function transferDeviceSubscriptionAndListsApi(PDO $db, string $oldDeviceId, string $newDeviceId): array {
    $oldId = strtoupper(trim($oldDeviceId));
    $newId = strtoupper(trim($newDeviceId));
    if (preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $oldId)) $oldId = 'DEV-' . $oldId;
    if (preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $newId)) $newId = 'DEV-' . $newId;

    if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $oldId) || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $newId)) {
        return ['ok' => false, 'error' => 'يرجى إدخال رمز شاشة جديد بالصيغة الصحيحة (مثال: DEV-A1B2-C3D4)'];
    }
    if ($oldId === $newId) {
        return ['ok' => false, 'error' => 'رمز الشاشة الجديد مطابق لرمز الشاشة الحالي'];
    }

    try {
        $db->beginTransaction();
        $oldStmt = $db->prepare("SELECT * FROM users WHERE UPPER(device_id) = ? LIMIT 1");
        $oldStmt->execute([$oldId]);
        $oldUser = $oldStmt->fetch(PDO::FETCH_ASSOC);

        $newStmt = $db->prepare("SELECT * FROM users WHERE UPPER(device_id) = ? LIMIT 1");
        $newStmt->execute([$newId]);
        $newUser = $newStmt->fetch(PDO::FETCH_ASSOC);

        if ($newUser) {
            $newExpTs = !empty($newUser['expire_date']) ? strtotime($newUser['expire_date'] . ' 23:59:59') : 0;
            $newDaysLeft = $newExpTs > 0 ? (int)ceil(($newExpTs - time()) / 86400) : 0;
            $newPaidDays = (int)($newUser['total_paid_days'] ?? 0);
            if ($newDaysLeft > 7 && $newPaidDays > 0) {
                $db->rollBack();
                return ['ok' => false, 'error' => "الشاشة الجديدة ({$newId}) مسجلة مسبقاً باشتراك مدفوع نشط ({$newDaysLeft} يوم)"];
            }
        }

        $expiryMoved = date('Y-m-d', strtotime('+3 days'));
        $daysLeftMoved = 3;
        if ($oldUser) {
            $expiryMoved = (string)($oldUser['expire_date'] ?? $expiryMoved);
            $expTs = $expiryMoved !== '' ? strtotime($expiryMoved . ' 23:59:59') : 0;
            $daysLeftMoved = $expTs > 0 ? max(0, (int)ceil(($expTs - time()) / 86400)) : 0;
            $transferNote = trim((string)($oldUser['notes'] ?? '')) . " | 🔄 منقول من {$oldId}";

            if ($newUser) {
                $db->prepare("UPDATE users SET expire_date = ?, is_active = ?, total_paid_days = ?, trial_used = 1, notes = ?, updated_at = datetime('now'), last_seen = datetime('now') WHERE id = ?")
                   ->execute([$expiryMoved, (int)($oldUser['is_active'] ?? 1), (int)($oldUser['total_paid_days'] ?? 0), $transferNote, $newUser['id']]);
                $db->prepare("DELETE FROM users WHERE id = ?")->execute([$oldUser['id']]);
            } else {
                $db->prepare("UPDATE users SET device_id = ?, notes = ?, updated_at = datetime('now'), last_seen = datetime('now') WHERE id = ?")
                   ->execute([$newId, $transferNote, $oldUser['id']]);
            }
        }

        $updLists = $db->prepare("UPDATE customer_xtream_lists SET device_id = ?, updated_at = datetime('now') WHERE UPPER(device_id) = ?");
        $updLists->execute([$newId, $oldId]);
        $movedCount = $updLists->rowCount();

        logActivity($db, $newId, 'device_transferred', ['from' => $oldId, 'to' => $newId, 'moved_lists' => $movedCount, 'expire_date' => $expiryMoved]);
        $db->commit();

        return [
            'ok'          => true,
            'old_device'  => $oldId,
            'new_device'  => $newId,
            'expire_date' => $expiryMoved,
            'days_left'   => $daysLeftMoved,
            'moved_lists' => $movedCount,
            'message'     => "✅ تم نقل الاشتراك وجميع القوائم ({$movedCount} قائمة) من {$oldId} إلى {$newId} بنجاح!"
        ];
    } catch (Throwable $e) {
        if ($db->inTransaction()) $db->rollBack();
        return ['ok' => false, 'error' => 'حدث خطأ أثناء نقل الشاشة: ' . $e->getMessage()];
    }
}

function verifyCard(string $card_code): array {
    $card_code = normalizeVoucherCode($card_code);
    if (!preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $card_code)) {
        return ['ok' => false, 'error' => 'صيغة رمز البطاقة غير صحيحة (مثال: A1B2-C3D4)', 'code' => 'CODE_INVALID', 'http' => 400];
    }

    $db = getDB();

    // فحص سجل الاستهلاك أولاً
    $stmt = $db->prepare("SELECT * FROM card_redemptions WHERE code = ? COLLATE NOCASE LIMIT 1");
    $stmt->execute([$card_code]);
    if ($stmt->fetch()) {
        return ['ok' => false, 'error' => 'هذه البطاقة مستخدمة مسبقاً', 'code' => 'CARD_USED', 'http' => 410];
    }

    // فحص القسيمة
    $stmt = $db->prepare("SELECT * FROM activation_cards WHERE code = ? COLLATE NOCASE ORDER BY (CASE WHEN status='used' THEN 0 ELSE 1 END) ASC, id ASC");
    $stmt->execute([$card_code]);
    $allCards = $stmt->fetchAll();

    if (empty($allCards)) return ['ok' => false, 'error' => 'رمز البطاقة غير موجود', 'code' => 'CARD_NOT_FOUND', 'http' => 404];

    foreach ($allCards as $c) {
        if (($c['status'] ?? '') === 'used' || ($c['status'] ?? '') === 'invalid_duplicate' || !empty($c['used_at']) || !empty($c['used_by_device'])) {
            return ['ok' => false, 'error' => 'هذه البطاقة مستخدمة مسبقاً', 'code' => 'CARD_USED', 'http' => 410];
        }
    }

    $card = $allCards[0];
    if (($card['status'] ?? '') !== 'unused') {
        return ['ok' => false, 'error' => 'هذه البطاقة غير متاحة', 'code' => 'CARD_UNAVAILABLE', 'http' => 410];
    }

    if (!empty($card['expires_at'])) {
        $expStr = trim($card['expires_at']);
        $baseStr = (strlen($expStr) <= 10) ? $expStr . ' 23:59:59' : $expStr;
        $expireTs = strtotime($baseStr);
        if ($expireTs === false || $expireTs < time()) {
            return ['ok' => false, 'error' => 'انتهت صلاحية البطاقة', 'code' => 'CARD_EXPIRED', 'http' => 410];
        }
    }

    return ['ok' => true, 'duration_days' => (int)$card['duration_days'], 'card_type' => $card['card_type'] ?? '', 'message' => '✅ القسيمة صالحة وجاهزة للتفعيل'];
}

/* ============================================================
   🎫 activateCard — تفعيل + إحراق ذرّي عبر card_redemptions
   ============================================================ */
function activateCard(string $device_id, string $card_code, string $device_name = ''): array {
    $device_id = normalizeDeviceCodeApi($device_id);
    $card_code = normalizeVoucherCode($card_code);
    $device_name = trim($device_name);

    if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $device_id)) {
        return ['ok' => false, 'error' => 'صيغة رمز الجهاز غير صحيحة (DEV-XXXX-XXXX)', 'code' => 'DEVICE_INVALID', 'http' => 400];
    }
    if ($card_code === '') {
        return ['ok' => false, 'error' => 'أدخل رمز البطاقة', 'code' => 'CODE_EMPTY', 'http' => 400];
    }
    if (!preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $card_code)) {
        return ['ok' => false, 'error' => 'صيغة رمز البطاقة غير صحيحة (مثال: A1B2-C3D4)', 'code' => 'CODE_INVALID', 'http' => 400];
    }

    $db = getDB();
    if (countRecentBadAttempts($db, $device_id) >= MAX_BAD_ATTEMPTS_PER_HOUR) {
        logActivity($db, $device_id, 'rate_limited', ['card_code' => $card_code]);
        return ['ok' => false, 'error' => 'عدد المحاولات كبير، يرجى المحاولة لاحقاً', 'code' => 'RATE_LIMITED', 'http' => 429];
    }

    $failure = null;
    $duration = 0;
    $newExpire = null;
    $isRenewal = false;

    try {
        $db->beginTransaction();

        // لا ننشئ سجل استهلاك قبل التأكد من وجود القسيمة وصلاحيتها.
        $stmt = $db->prepare("SELECT 1 FROM card_redemptions WHERE code = ? COLLATE NOCASE LIMIT 1");
        $stmt->execute([$card_code]);
        if ($stmt->fetchColumn()) {
            $failure = ['ok' => false, 'error' => 'هذه البطاقة مستخدمة مسبقاً ولا يمكن إعادة استخدامها', 'code' => 'CARD_USED', 'http' => 410, 'reason' => 'قسيمة محروقة سابقاً'];
            $db->rollBack();
        } else {
            $stmt = $db->prepare("SELECT * FROM activation_cards WHERE code = ? COLLATE NOCASE ORDER BY id ASC");
            $stmt->execute([$card_code]);
            $allCards = $stmt->fetchAll();

            if (!$allCards) {
                $failure = ['ok' => false, 'error' => 'رمز البطاقة غير موجود', 'code' => 'CARD_NOT_FOUND', 'http' => 404, 'reason' => 'قسيمة غير موجودة'];
                $db->rollBack();
            } else {
                $card = null;
                foreach ($allCards as $candidate) {
                    if (($candidate['status'] ?? '') === 'used' ||
                        ($candidate['status'] ?? '') === 'invalid_duplicate' ||
                        !empty($candidate['used_at']) ||
                        !empty($candidate['used_by_device'])) {
                        $failure = ['ok' => false, 'error' => 'هذه البطاقة مستخدمة مسبقاً', 'code' => 'CARD_USED', 'http' => 410, 'reason' => 'قسيمة مستعملة'];
                        break;
                    }
                    if (($candidate['status'] ?? '') === 'unused' && $card === null) {
                        $card = $candidate;
                    }
                }

                if ($failure === null && $card === null) {
                    $failure = ['ok' => false, 'error' => 'هذه البطاقة غير متاحة', 'code' => 'CARD_UNAVAILABLE', 'http' => 410, 'reason' => 'قسيمة بحالة غير صالحة'];
                }

                if ($failure === null && !empty($card['expires_at'])) {
                    $expStr = trim((string)$card['expires_at']);
                    $expireTs = strtotime(strlen($expStr) <= 10 ? $expStr . ' 23:59:59' : $expStr);
                    if ($expireTs === false || $expireTs < time()) {
                        $failure = ['ok' => false, 'error' => 'انتهت صلاحية البطاقة', 'code' => 'CARD_EXPIRED', 'http' => 410, 'reason' => 'قسيمة منتهية'];
                    }
                }

                if ($failure !== null) {
                    $db->rollBack();
                } else {
                    $duration = max(1, (int)$card['duration_days']);

                    // هذا الإدخال هو الحجز الدائم. القيد الفريد يمنع تفعيل الرمز مرتين.
                    try {
                        $reserve = $db->prepare("INSERT INTO card_redemptions (code, redeemed_by_device, redeemed_at, card_id, duration_days, ip_address) VALUES (?, ?, datetime('now'), ?, ?, ?)");
                        $reserve->execute([$card_code, $device_id, $card['id'], $duration, $_SERVER['REMOTE_ADDR'] ?? null]);
                    } catch (PDOException $e) {
                        if (stripos($e->getMessage(), 'unique constraint') !== false ||
                            stripos($e->getMessage(), 'primary key') !== false) {
                            if ($db->inTransaction()) $db->rollBack();
                            return ['ok' => false, 'error' => 'هذه البطاقة مستخدمة مسبقاً ولا يمكن إعادة استخدامها', 'code' => 'CARD_USED', 'http' => 410];
                        }
                        throw $e;
                    }

                    $stmt = $db->prepare("SELECT * FROM users WHERE device_id = ? LIMIT 1");
                    $stmt->execute([$device_id]);
                    $user = $stmt->fetch();

                    $today = date('Y-m-d');
                    $todayTs = strtotime($today . ' 00:00:00');
                    $baseDate = $today;
                    if ($user && !empty($user['expire_date'])) {
                        $currentTs = strtotime($user['expire_date'] . ' 23:59:59');
                        if ($currentTs !== false && $currentTs > $todayTs) {
                            $baseDate = $user['expire_date'];
                            $isRenewal = true;
                        }
                    }
                    $newExpire = date('Y-m-d', strtotime($baseDate . ' +' . $duration . ' days'));

                    if ($user) {
                        $oldNotes = trim((string)($user['notes'] ?? ''));
                        $newNote = ($isRenewal ? '💳 تجديد' : '💳 تفعيل') . " ببطاقة {$card_code} (+{$duration} يوم)";
                        $isTrialNote = mb_strpos($oldNotes, 'تجربة مجانية') !== false && mb_strpos($oldNotes, 'ببطاقة') === false;
                        $newNotes = ($oldNotes === '' || $isTrialNote) ? $newNote : $oldNotes . ' | ' . $newNote;
                        $cardResId = !empty($card['reseller_id']) ? (int)$card['reseller_id'] : ($user['reseller_id'] ?? null);
                        $db->prepare("UPDATE users SET expire_date = ?, is_active = 1, blocked_reason = NULL, notes = ?, last_card_code = ?, last_activation_at = datetime('now'), total_paid_days = COALESCE(total_paid_days, 0) + ?, trial_used = 1, reseller_id = ?, updated_at = datetime('now'), last_seen = datetime('now') WHERE id = ?")
                           ->execute([$newExpire, $newNotes, $card_code, $duration, $cardResId, $user['id']]);
                    } else {
                        $cardResId = !empty($card['reseller_id']) ? (int)$card['reseller_id'] : null;
                        $safeName = mb_substr($device_name !== '' ? $device_name : 'جهاز جديد', 0, MAX_DEVICE_NAME_LEN);
                        $db->prepare("INSERT INTO users (device_id, device_name, expire_date, is_active, notes, trial_used, last_card_code, last_activation_at, total_paid_days, reseller_id, created_at, updated_at, registration_source, last_seen) VALUES (?, ?, ?, 1, ?, 1, ?, datetime('now'), ?, ?, datetime('now'), datetime('now'), 'card', datetime('now'))")
                           ->execute([$device_id, $safeName, $newExpire, "💳 تفعيل ببطاقة {$card_code} (+{$duration} يوم)", $card_code, $duration, $cardResId]);
                    }

                    $db->prepare("UPDATE card_redemptions SET card_id = ?, duration_days = ?, new_expire = ?, is_renewal = ? WHERE code = ? COLLATE NOCASE")
                       ->execute([$card['id'], $duration, $newExpire, $isRenewal ? 1 : 0, $card_code]);

                    $invalidate = $db->prepare("UPDATE activation_cards SET status = 'used', used_at = datetime('now'), used_by_device = ? WHERE id = ? AND status = 'unused' AND (used_at IS NULL OR used_at = '') AND (used_by_device IS NULL OR used_by_device = '')");
                    $invalidate->execute([$device_id, $card['id']]);
                    if ($invalidate->rowCount() !== 1) {
                        throw new RuntimeException('تعذر تثبيت حالة القسيمة؛ تم التراجع عن العملية');
                    }

                    logActivity($db, $device_id, 'card_activated', [
                        'card_code' => $card_code,
                        'duration' => $duration,
                        'new_expire' => $newExpire,
                        'is_renewal' => $isRenewal,
                        'reseller_id' => $card['reseller_id'] ?? null
                    ]);
                    $db->commit();
                    syncUserToSecondaryPortalDb($device_id, $device_name ?: 'جهاز مفعل', $newExpire, 1, "💳 تفعيل ببطاقة {$card_code} (+{$duration} يوم)");
                }
            }
        }
    } catch (Throwable $e) {
        if ($db->inTransaction()) $db->rollBack();
        throw $e;
    }

    if ($failure !== null) {
        recordBadAttempt($db, $device_id, $card_code, $failure['reason'] ?? 'فشل التفعيل', $device_name);
        unset($failure['reason']);
        return $failure;
    }

    return [
        'ok' => true,
        'message' => $isRenewal ? '✅ تم تجديد الاشتراك بنجاح' : '✅ تم تفعيل الاشتراك بنجاح',
        'new_expire' => $newExpire,
        'added_days' => $duration,
        'is_renewal' => $isRenewal,
        'card_status' => 'burned_permanently'
    ];
}

/* ============================================================
   🚀 التوجيه الرئيسي
   ============================================================ */
try {
    switch ($action) {

        case 'check_subscription':
            jsonOk(checkUserSubscription($_REQUEST['device_id'] ?? '', $_REQUEST['device_name'] ?? ''));
            break;

        // 🆕 فحص القسيمة بدون إحراق (للعرض في الواجهة)
        case 'verify_card':
            $rawVoucher = (string)($_REQUEST['card_code'] ?? $_REQUEST['code'] ?? $_REQUEST['voucher'] ?? '');
            $r = verifyCard($rawVoucher);
            if (!$r['ok']) jsonError($r['error'], $r['http'] ?? 400, $r['code'] ?? '');
            unset($r['ok']);
            jsonOk($r);
            break;

        // تفعيل القسيمة (يحرقها نهائياً)
        case 'activate_card':
            if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') { jsonError('Method Not Allowed', 405); }
            $rawDevice  = (string)($_REQUEST['device_id'] ?? '');
            $rawVoucher = (string)($_REQUEST['card_code'] ?? $_REQUEST['code'] ?? $_REQUEST['voucher'] ?? '');
            $rawDevName = (string)($_REQUEST['device_name'] ?? '');
            $normDevice = normalizeDeviceCodeApi($rawDevice);

            $result = activateCard($normDevice, $rawVoucher, $rawDevName);
            if (!$result['ok']) jsonError($result['error'], $result['http'] ?? 400, $result['code'] ?? '');
            unset($result['ok']);
            $result['code_formatted'] = normalizeVoucherCode($rawVoucher);
            $result['subscription'] = checkUserSubscription($normDevice, $rawDevName);
            jsonOk($result);
            break;

        case 'get_channels':
            $reqDeviceId = strtoupper(trim($_REQUEST['device_id'] ?? ''));
            $reqDeviceName = trim($_REQUEST['device_name'] ?? '');
            $sub = checkUserSubscription($reqDeviceId, $reqDeviceName);
            if (!$sub['active']) {
                http_response_code(403);
                echo json_encode(['success' => false, 'error' => $sub['message'], 'subscription' => $sub], JSON_UNESCAPED_UNICODE);
                exit;
            }
            $db = getDB();
            $xtreamLists = getDeviceXtreamLists($db, $reqDeviceId, false);
            $appUpdateCfg = getAppUpdateConfig($db);
            // القائمة الرئيسية (PORTAL_MAIN) لا تفتح ولا ترسل تلقائياً لجميع المستخدمين إلا إذا فعّلها الأدمن لهذا الـ device_id تحديداً
            if (empty($sub['portal_main_enabled'])) {
                jsonOk([
                    'channels'     => [],
                    'categories'   => [],
                    'xtream_lists' => $xtreamLists,
                    'subscription' => $sub,
                    'app_update'   => $appUpdateCfg,
                    'count'        => 0,
                    'catalog_hash' => 'PORTAL_DISABLED_FOR_DEVICE',
                    'is_unchanged' => false,
                    'server_time'  => time()
                ]);
            }
            $raw = $db->query("SELECT c.id, c.number, c.name, c.original_url AS encrypted_url, c.logo_url, c.category, c.sort_order FROM channels c LEFT JOIN categories cat ON c.category = cat.name WHERE c.is_active = 1 ORDER BY COALESCE(cat.sort_order, 9999) ASC, c.sort_order ASC, c.number ASC")->fetchAll();
            $channels = []; $counter = 1;
            foreach ($raw as $ch) {
                if (isAdultOr18PlusContent((string)($ch['name'] ?? ''), (string)($ch['category'] ?? ''))) continue;
                $ch['number'] = $counter++;
                $channels[] = $ch;
            }
            $rawCategories = $db->query("SELECT name, sort_order FROM categories ORDER BY sort_order ASC, name ASC")->fetchAll();
            $categories = array_values(array_filter($rawCategories, fn($catRow) => !isAdultOr18PlusContent('', (string)($catRow['name'] ?? ''))));
            $catalogHash = sha1(json_encode([$channels, $categories], JSON_UNESCAPED_UNICODE));
            $clientHash  = trim((string)($_REQUEST['client_hash'] ?? ''));
            if ($clientHash !== '' && hash_equals($catalogHash, $clientHash)) {
                jsonOk([
                    'channels'     => [],
                    'categories'   => $categories,
                    'xtream_lists' => $xtreamLists,
                    'subscription' => $sub,
                    'app_update'   => $appUpdateCfg,
                    'count'        => count($channels),
                    'catalog_hash' => $catalogHash,
                    'is_unchanged' => true,
                    'server_time'  => time()
                ]);
            }
            jsonOk([
                'channels'     => $channels,
                'categories'   => $categories,
                'xtream_lists' => $xtreamLists,
                'subscription' => $sub,
                'app_update'   => $appUpdateCfg,
                'count'        => count($channels),
                'catalog_hash' => $catalogHash,
                'is_unchanged' => false,
                'server_time'  => time()
            ]);
            break;

        case 'save_xtream':
            $reqDeviceId = strtoupper(trim($_REQUEST['device_id'] ?? ''));
            $reqDeviceName = trim($_REQUEST['device_name'] ?? '');
            $sub = checkUserSubscription($reqDeviceId, $reqDeviceName);
            if (!$sub['active']) {
                jsonError($sub['message'] ?? 'انتهت صلاحية المشغل. يرجى التجديد أولاً.', 403, 'SUBSCRIPTION_EXPIRED');
            }
            $db = getDB();
            $lists = saveDeviceXtreamList($db, $reqDeviceId, $_REQUEST);
            jsonOk([
                'message'      => '✅ تم حفظ القائمة في لوحة التحكم وفضاء الزبون ومزامنتها مع الشاشة',
                'subscription' => $sub,
                'xtream_lists' => $lists
            ]);
            break;

        case 'get_discovery_hosts':
            $db = getDB();
            jsonOk(['servers' => getAllKnownDiscoveryServers($db)]);
            break;

        case 'test_xtream_connection':
        case 'auto_discover':
            $db = getDB();
            $u  = trim((string)($_REQUEST['username'] ?? $_REQUEST['xtream_user'] ?? ''));
            $pw = trim((string)($_REQUEST['password'] ?? $_REQUEST['xtream_pass'] ?? ''));
            if ($u === '' || $pw === '') {
                jsonError('يرجى إدخال اسم المستخدم وكلمة المرور');
            }
            // البحث الفوري في القوائم المسجلة لاستعادة الحساب مع اسم السيرفر الحقيقي
            $dbs = [$db];
            $sec = getSecondaryPortalPdo();
            if ($sec !== null) $dbs[] = $sec;
            foreach ($dbs as $pdo) {
                try {
                    $st = $pdo->prepare("SELECT server_name, server_url, xtream_pass, exp_date FROM customer_xtream_lists WHERE LOWER(TRIM(xtream_user)) = LOWER(?) ORDER BY updated_at DESC, id DESC");
                    $st->execute([$u]);
                    while ($r = $st->fetch(PDO::FETCH_ASSOC)) {
                        $decPass = decryptXtreamSecret((string)($r['xtream_pass'] ?? ''));
                        if ($decPass !== '' && $decPass !== $pw) continue;
                        $decUrl = normalizeServerBaseUrlApi(decryptXtreamSecret((string)($r['server_url'] ?? '')));
                        if ($decUrl === '') continue;
                        $realName = resolveRealServerNameApi($db, (string)($r['server_name'] ?? ''), $decUrl);
                        learnServerToDataTxtApi($db, $realName, $decUrl);
                        jsonOk([
                            'ok'          => true,
                            'server_name' => $realName,
                            'server_url'  => $decUrl,
                            'exp_date'    => (string)($r['exp_date'] ?? ''),
                            'message'     => "✅ تم التعرّف على السيرفر واستعادته باسمه الحقيقي ({$realName})"
                        ]);
                    }
                } catch (Throwable $e) {}
            }
            jsonError('لم يتم العثور على سيرفر مطابق', 404);
            break;

        case 'get_xtream_lists':
            $reqDeviceId = normalizeDeviceCodeApi((string)($_REQUEST['device_id'] ?? ''));
            $reqDeviceName = trim($_REQUEST['device_name'] ?? '');
            $sub = checkUserSubscription($reqDeviceId, $reqDeviceName);
            if (!$sub['active']) {
                jsonError($sub['message'] ?? 'انتهت صلاحية المشغل.', 403, 'SUBSCRIPTION_EXPIRED');
            }
            $db = getDB();
            jsonOk([
                'device_id'         => $reqDeviceId,
                'subscription'      => $sub,
                'xtream_lists'      => getDeviceXtreamLists($db, $reqDeviceId, false),
                'known_servers'     => readDataTxtEntries(),
                'domain_migrations' => getApplicableDomainMigrations($db, $reqDeviceId),
                'app_update'        => getAppUpdateConfig($db)
            ]);
            break;

        case 'save_favorites':
            $reqDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
            if (preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                $reqDeviceId = 'DEV-' . $reqDeviceId;
            }
            if ($reqDeviceId === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                jsonError('device_id غير صالح', 400, 'INVALID_DEVICE');
            }
            $rawJson = (string)($_POST['favorites_json'] ?? $_REQUEST['favorites_json'] ?? '[]');
            $decoded = json_decode($rawJson, true);
            if (!is_array($decoded)) {
                jsonError('صيغة المفضلة غير صالحة', 400, 'INVALID_FAVORITES');
            }
            $deletedRaw = (string)($_POST['deleted_keys_json'] ?? $_REQUEST['deleted_keys_json'] ?? '[]');
            $deletedArr = json_decode($deletedRaw, true);
            $deletedLookup = [];
            if (is_array($deletedArr)) {
                foreach ($deletedArr as $dk) {
                    $kStr = trim((string)$dk);
                    if ($kStr !== '') $deletedLookup[$kStr] = true;
                }
            }
            $clean = [];
            $seen = [];
            foreach ($decoded as $item) {
                if (!is_array($item)) continue;
                $name = trim((string)($item['name'] ?? ''));
                $url  = trim((string)($item['url'] ?? ''));
                if ($name === '' || $url === '') continue;
                if (doesFavoriteItemMatchDeletedSet($item, $deletedLookup)) continue;
                $key = computeFavoriteItemIdentityKey($item);
                if (isset($seen[$key])) continue;
                $seen[$key] = true;
                $clean[] = $item;
                if (count($clean) >= 5000) break;
            }
            $storeJson = json_encode(array_values($clean), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            $db = getDB();
            syncFavoritesAcrossAllDbs($db, $reqDeviceId, $storeJson, count($clean));
            logActivity($db, $reqDeviceId, 'favorites_backup_replace', ['count' => count($clean)]);
            jsonOk([
                'ok'        => true,
                'message'   => 'تم تحديث واستبدال نسخة المفضلة بالكامل في قاعدة البيانات السحابية',
                'count'     => count($clean),
                'device_id' => $reqDeviceId,
                'replaced'  => true
            ]);
            break;

        case 'delete_favorite_item':
            $reqDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
            if (preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                $reqDeviceId = 'DEV-' . $reqDeviceId;
            }
            if ($reqDeviceId === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                jsonError('device_id غير صالح', 400, 'INVALID_DEVICE');
            }
            $deletedRaw = (string)($_POST['deleted_keys_json'] ?? $_REQUEST['deleted_keys_json'] ?? '[]');
            $deletedArr = json_decode($deletedRaw, true);
            $deletedLookup = [];
            if (is_array($deletedArr)) {
                foreach ($deletedArr as $dk) {
                    $kStr = trim((string)$dk);
                    if ($kStr !== '') $deletedLookup[$kStr] = true;
                }
            }
            $singleKey = trim((string)($_REQUEST['favorite_key'] ?? ''));
            if ($singleKey !== '') $deletedLookup[$singleKey] = true;
            $singleUrl = strtolower(trim((string)($_REQUEST['item_url'] ?? '')));
            if ($singleUrl !== '') $deletedLookup[$singleUrl] = true;

            $db = getDB();
            ensureDeviceFavoritesSchema($db);
            $st = $db->prepare("SELECT favorites_json FROM device_favorites WHERE UPPER(TRIM(device_id)) = ? LIMIT 1");
            $st->execute([$reqDeviceId]);
            $row = $st->fetch(PDO::FETCH_ASSOC);
            $existing = $row ? json_decode((string)$row['favorites_json'], true) : [];
            if (!is_array($existing)) $existing = [];
            $filtered = [];
            $seen = [];
            foreach ($existing as $item) {
                if (!is_array($item)) continue;
                if (doesFavoriteItemMatchDeletedSet($item, $deletedLookup)) continue;
                $key = computeFavoriteItemIdentityKey($item);
                if (isset($seen[$key])) continue;
                $seen[$key] = true;
                $filtered[] = $item;
            }
            $storeJson = json_encode(array_values($filtered), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            syncFavoritesAcrossAllDbs($db, $reqDeviceId, $storeJson, count($filtered));
            logActivity($db, $reqDeviceId, 'favorite_item_deleted', ['remaining_count' => count($filtered)]);
            jsonOk([
                'ok'        => true,
                'message'   => 'تم حذف العنصر من قاعدة البيانات السحابية نهائياً',
                'count'     => count($filtered),
                'device_id' => $reqDeviceId,
                'deleted'   => true
            ]);
            break;

        case 'get_favorites':
            $reqDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
            if (preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                $reqDeviceId = 'DEV-' . $reqDeviceId;
            }
            if ($reqDeviceId === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                jsonError('device_id غير صالح', 400, 'INVALID_DEVICE');
            }
            $db = getDB();
            ensureDeviceFavoritesSchema($db);
            $st = $db->prepare("SELECT favorites_json, items_count, updated_at FROM device_favorites WHERE UPPER(TRIM(device_id)) = ? ORDER BY updated_at DESC LIMIT 1");
            $st->execute([$reqDeviceId]);
            $row = $st->fetch(PDO::FETCH_ASSOC);
            if (!$row) {
                $sec = getSecondaryPortalPdo();
                if ($sec !== null) {
                    $stSec = $sec->prepare("SELECT favorites_json, items_count, updated_at FROM device_favorites WHERE UPPER(TRIM(device_id)) = ? ORDER BY updated_at DESC LIMIT 1");
                    $stSec->execute([$reqDeviceId]);
                    $row = $stSec->fetch(PDO::FETCH_ASSOC);
                }
            }
            if (!$row) {
                jsonOk(['found' => false, 'favorites' => [], 'count' => 0, 'device_id' => $reqDeviceId]);
            }
            $arr = json_decode((string)$row['favorites_json'], true);
            if (!is_array($arr)) $arr = [];
            jsonOk([
                'found'      => true,
                'favorites'  => array_values($arr),
                'count'      => count($arr),
                'updated_at' => (string)($row['updated_at'] ?? ''),
                'device_id'  => $reqDeviceId
            ]);
            break;

        case 'delete_xtream':
            $reqDeviceId = strtoupper(trim($_REQUEST['device_id'] ?? ''));
            $pid = trim((string)($_REQUEST['playlist_id'] ?? $_REQUEST['id'] ?? ''));
            $db = getDB();
            if ($reqDeviceId !== '' && $pid !== '') {
                $db->prepare("DELETE FROM customer_xtream_lists WHERE UPPER(device_id) = ? AND playlist_id = ?")
                   ->execute([$reqDeviceId, $pid]);
                logActivity($db, $reqDeviceId, 'xtream_deleted', ['playlist_id' => $pid]);
            }
            jsonOk([
                'message'      => '🗑️ تم حذف القائمة بنجاح',
                'xtream_lists' => getDeviceXtreamLists($db, $reqDeviceId, false)
            ]);
            break;

        case 'transfer_device':
            $oldDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
            $newDeviceId = strtoupper(trim((string)($_REQUEST['new_device_id'] ?? $_REQUEST['new_mac_address'] ?? '')));
            $db = getDB();
            $res = transferDeviceSubscriptionAndListsApi($db, $oldDeviceId, $newDeviceId);
            if (!$res['ok']) {
                jsonError($res['error'] ?? 'تعذر نقل الشاشة', 400, 'TRANSFER_FAILED');
            }
            jsonOk($res);
            break;

        case 'admin_login':
            $password = $_POST['password'] ?? '';
            if (empty($password)) jsonError('أدخل كلمة المرور');
            if (!password_verify($password, getSetting('admin_password_hash'))) { sleep(1); jsonError('كلمة مرور خاطئة', 401); }
            $db = getDB();
            $db->exec("DELETE FROM admin_sessions WHERE expires_at <= datetime('now')");
            $token = generateToken(64);
            $db->prepare("INSERT INTO admin_sessions (token, expires_at, ip_address) VALUES (?, datetime('now', '+' || ? || ' seconds'), ?)")->execute([$token, SESSION_DURATION, $_SERVER['REMOTE_ADDR'] ?? '']);
            jsonOk(['token' => $token, 'expires_in' => SESSION_DURATION]);
            break;

        case 'admin_logout':
            requireAdmin();
            getDB()->prepare("DELETE FROM admin_sessions WHERE token = ?")->execute([$_REQUEST['token'] ?? '']);
            jsonOk(['message' => 'تم تسجيل الخروج']);
            break;

        case 'admin_change_password':
            requireAdmin();
            $old = $_POST['old_password'] ?? ''; $new = $_POST['new_password'] ?? '';
            if (strlen($new) < 6) jsonError('كلمة المرور قصيرة جداً');
            if (!password_verify($old, getSetting('admin_password_hash'))) jsonError('كلمة المرور الحالية خاطئة');
            setSetting('admin_password_hash', password_hash($new, PASSWORD_DEFAULT));
            getDB()->prepare("DELETE FROM admin_sessions WHERE token != ?")->execute([$_REQUEST['token'] ?? '']);
            jsonOk(['message' => 'تم التغيير بنجاح']);
            break;

        case 'admin_stats':
            requireAdmin();
            $db = getDB();
            jsonOk([
                'total_channels' => (int)$db->query("SELECT COUNT(*) FROM channels")->fetchColumn(),
                'active_channels' => (int)$db->query("SELECT COUNT(*) FROM channels WHERE is_active = 1")->fetchColumn(),
                'total_users' => (int)$db->query("SELECT COUNT(*) FROM users")->fetchColumn(),
                'active_users' => (int)$db->query("SELECT COUNT(*) FROM users WHERE is_active = 1 AND expire_date >= date('now')")->fetchColumn(),
                'trial_users' => (int)$db->query("SELECT COUNT(*) FROM users WHERE is_active = 1 AND expire_date >= date('now') AND trial_used = 1 AND (last_card_code IS NULL OR last_card_code = '')")->fetchColumn(),
                'paid_users' => (int)$db->query("SELECT COUNT(*) FROM users WHERE is_active = 1 AND expire_date >= date('now') AND last_card_code IS NOT NULL AND last_card_code != ''")->fetchColumn(),
                'expired_users' => (int)$db->query("SELECT COUNT(*) FROM users WHERE expire_date < date('now')")->fetchColumn(),
                'disabled_users' => (int)$db->query("SELECT COUNT(*) FROM users WHERE is_active = 0")->fetchColumn(),
                'blocked_bad_card' => (int)$db->query("SELECT COUNT(*) FROM users WHERE is_active = 0 AND blocked_reason IS NOT NULL")->fetchColumn(),
                'online_today' => (int)$db->query("SELECT COUNT(*) FROM users WHERE last_seen >= datetime('now', '-1 day')")->fetchColumn(),
                'bad_attempts_24h' => (int)$db->query("SELECT COUNT(*) FROM activity_log WHERE action = 'bad_card_attempt' AND created_at >= datetime('now', '-1 day')")->fetchColumn(),
                'total_redemptions' => (int)$db->query("SELECT COUNT(*) FROM card_redemptions")->fetchColumn(),
                'total_resellers' => (int)$db->query("SELECT COUNT(*) FROM resellers")->fetchColumn(),
                'active_resellers' => (int)$db->query("SELECT COUNT(*) FROM resellers WHERE is_active = 1")->fetchColumn(),
                'total_reseller_points' => (int)$db->query("SELECT COALESCE(SUM(points), 0) FROM resellers")->fetchColumn(),
                'total_reseller_cards' => (int)$db->query("SELECT COALESCE(SUM(total_cards_generated), 0) FROM resellers")->fetchColumn(),
            ]);
            break;

        case 'admin_list_channels':
            requireAdmin();
            $stmt = getDB()->prepare("SELECT * FROM channels ORDER BY sort_order ASC, number ASC"); $stmt->execute();
            jsonOk(['channels' => $stmt->fetchAll()]);
            break;

        case 'admin_add_channel':
            requireAdmin();
            $name = trim($_POST['name'] ?? ''); $url = trim($_POST['url'] ?? '');
            $number = (int)($_POST['number'] ?? 0); $category = trim($_POST['category'] ?? 'عام') ?: 'عام';
            if ($name === '' || $url === '') jsonError('الاسم والرابط مطلوبان');
            // Preserve original HTTP or HTTPS protocol for universal server compatibility
            $db = getDB(); $db->beginTransaction();
            try {
                if ($number <= 0) $number = (int)$db->query("SELECT COALESCE(MAX(number), 0) + 1 FROM channels")->fetchColumn();
                $chk = $db->prepare("SELECT id FROM categories WHERE name = ?"); $chk->execute([$category]);
                if (!$chk->fetchColumn()) {
                    $maxSort = (int)$db->query("SELECT COALESCE(MAX(sort_order), -1) FROM categories")->fetchColumn();
                    $db->prepare("INSERT INTO categories (name, sort_order) VALUES (?, ?)")->execute([$category, $maxSort + 1]);
                }
                $nextSort = (int)$db->query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM channels WHERE category = " . $db->quote($category))->fetchColumn();
                $stmt = $db->prepare("INSERT INTO channels (number, name, encrypted_url, original_url, category, sort_order, is_active) VALUES (?, ?, ?, ?, ?, ?, 1)");
                $stmt->execute([$number, $name, encryptUrl($url), $url, $category, $nextSort]);
                $db->commit();
                jsonOk(['id' => (int)$db->lastInsertId(), 'message' => 'تمت الإضافة']);
            } catch (Exception $e) { $db->rollBack(); throw $e; }
            break;

        case 'admin_update_channel':
            requireAdmin();
            $id = (int)($_POST['id'] ?? 0); $name = trim($_POST['name'] ?? ''); $url = trim($_POST['url'] ?? '');
            $number = (int)($_POST['number'] ?? 0); $category = trim($_POST['category'] ?? 'عام') ?: 'عام'; $isActive = (int)($_POST['is_active'] ?? 1);
            if ($id <= 0 || $name === '') jsonError('بيانات غير صالحة');
            // Preserve original HTTP or HTTPS protocol for universal server compatibility
            $db = getDB();
            if ($url !== '') {
                $db->prepare("UPDATE channels SET name=?, number=?, encrypted_url=?, original_url=?, category=?, is_active=? WHERE id=?")->execute([$name, $number, encryptUrl($url), $url, $category, $isActive, $id]);
            } else {
                $db->prepare("UPDATE channels SET name=?, number=?, category=?, is_active=? WHERE id=?")->execute([$name, $number, $category, $isActive, $id]);
            }
            $chk = $db->prepare("SELECT id FROM categories WHERE name = ?"); $chk->execute([$category]);
            if (!$chk->fetchColumn()) {
                $maxSort = (int)$db->query("SELECT COALESCE(MAX(sort_order), -1) FROM categories")->fetchColumn();
                $db->prepare("INSERT INTO categories (name, sort_order) VALUES (?, ?)")->execute([$category, $maxSort + 1]);
            }
            jsonOk(['message' => 'تم التحديث']);
            break;

        case 'admin_delete_channel':
            requireAdmin();
            $id = (int)($_POST['id'] ?? 0);
            if ($id <= 0) jsonError('معرف غير صالح');
            getDB()->prepare("DELETE FROM channels WHERE id = ?")->execute([$id]);
            jsonOk(['message' => 'تم الحذف']);
            break;

        case 'admin_bulk_channels':
            requireAdmin();
            $type = $_POST['bulk_type'] ?? ''; $db = getDB();
            if ($type === 'delete_category') {
                $cat = trim($_POST['category'] ?? ''); if ($cat === '') jsonError('الباقة غير محددة');
                $stmt = $db->prepare("DELETE FROM channels WHERE category = ?"); $stmt->execute([$cat]);
                $affected = $stmt->rowCount();
                $db->prepare("DELETE FROM categories WHERE name = ?")->execute([$cat]);
                jsonOk(['affected' => $affected, 'message' => "تم حذف $affected قناة"]);
            }
            $ids = json_decode($_POST['ids'] ?? '[]', true);
            if (!is_array($ids) || empty($ids)) jsonError('لم يتم تحديد قنوات');
            $ids = array_values(array_unique(array_map('intval', $ids)));
            $placeholders = implode(',', array_fill(0, count($ids), '?'));
            if ($type === 'delete') {
                $stmt = $db->prepare("DELETE FROM channels WHERE id IN ($placeholders)"); $stmt->execute($ids);
                jsonOk(['affected' => $stmt->rowCount(), 'message' => "تم الحذف"]);
            } elseif ($type === 'activate' || $type === 'deactivate') {
                $active = $type === 'activate' ? 1 : 0;
                $stmt = $db->prepare("UPDATE channels SET is_active = ? WHERE id IN ($placeholders)");
                $stmt->execute(array_merge([$active], $ids));
                jsonOk(['affected' => $stmt->rowCount(), 'message' => 'تم التحديث']);
            } elseif ($type === 'move') {
                $target = trim($_POST['target_category'] ?? ''); if ($target === '') jsonError('الباقة مطلوبة');
                $chk = $db->prepare("SELECT id FROM categories WHERE name = ?"); $chk->execute([$target]);
                if (!$chk->fetchColumn()) {
                    $maxSort = (int)$db->query("SELECT COALESCE(MAX(sort_order), -1) FROM categories")->fetchColumn();
                    $db->prepare("INSERT INTO categories (name, sort_order) VALUES (?, ?)")->execute([$target, $maxSort + 1]);
                }
                $startSort = (int)$db->query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM channels WHERE category = " . $db->quote($target))->fetchColumn();
                $upd = $db->prepare("UPDATE channels SET category = ?, sort_order = ? WHERE id = ?");
                foreach ($ids as $i => $cid) $upd->execute([$target, $startSort + $i, $cid]);
                jsonOk(['affected' => count($ids), 'message' => 'تم النقل']);
            } else jsonError('عملية غير معروفة');
            break;

        case 'admin_get_categories':
            requireAdmin();
            jsonOk(['categories' => getDB()->query("SELECT c.id, c.name, c.sort_order, COUNT(ch.id) as channel_count FROM categories c LEFT JOIN channels ch ON ch.category = c.name GROUP BY c.id, c.name, c.sort_order ORDER BY c.sort_order ASC, c.name ASC")->fetchAll()]);
            break;

        case 'admin_reorder_categories':
            requireAdmin();
            $order = json_decode($_POST['order'] ?? '[]', true);
            if (!is_array($order) || empty($order)) jsonError('بيانات مطلوبة');
            $stmt = getDB()->prepare("UPDATE categories SET sort_order = ? WHERE name = ?");
            foreach ($order as $i => $name) $stmt->execute([$i, (string)$name]);
            jsonOk(['message' => 'تم حفظ الترتيب']);
            break;

        case 'admin_reorder_channels':
            requireAdmin();
            $order = json_decode($_POST['order'] ?? '[]', true);
            if (!is_array($order) || empty($order)) jsonError('بيانات مطلوبة');
            $stmt = getDB()->prepare("UPDATE channels SET sort_order = ? WHERE id = ?");
            foreach ($order as $i => $id) $stmt->execute([$i, (int)$id]);
            jsonOk(['message' => 'تم حفظ الترتيب']);
            break;

        case 'admin_preview_m3u':
            requireAdmin();
            $url = trim($_POST['url'] ?? '');
            if (!preg_match('#^https?://#i', $url)) jsonError('رابط غير صالح');
            $tmpFile = downloadRemotePlaylistToTempFile($url);
            if ($tmpFile === null) jsonError('تعذّر جلب ملف السيرفر (تأكد من الرابط أو الاتصال)');
            $channels = parseM3UStreamFromFile($tmpFile);
            @unlink($tmpFile);
            if (empty($channels)) jsonError('لم يتم العثور على قنوات في الرابط');
            $groups = [];
            foreach ($channels as $ch) {
                $cat = $ch['category'];
                if (!isset($groups[$cat])) $groups[$cat] = ['name' => $cat, 'count' => 0, 'order' => $ch['cat_sort']];
                $groups[$cat]['count']++;
            }
            usort($groups, function($a, $b) { return $a['order'] - $b['order']; });
            jsonOk(['total' => count($channels), 'groups' => array_values($groups)]);
            break;

        case 'admin_import_m3u':
            requireAdmin();
            $url = trim($_POST['url'] ?? ''); $groups = $_POST['groups'] ?? [];
            if (!is_array($groups)) $groups = [$groups];
            $tmpFile = downloadRemotePlaylistToTempFile($url);
            if ($tmpFile === null) jsonError('تعذّر جلب ملف السيرفر');
            $all = parseM3UStreamFromFile($tmpFile);
            @unlink($tmpFile);
            $selectedSet = !empty($groups) ? array_flip(array_map('strval', $groups)) : null;
            $db = getDB(); $db->beginTransaction();
            try {
                $checkCat = $db->prepare("SELECT id FROM categories WHERE name = ?");
                $updateCat = $db->prepare("UPDATE categories SET sort_order = ? WHERE id = ?");
                $insertCat = $db->prepare("INSERT INTO categories (name, sort_order) VALUES (?, ?)");
                $seenCats = [];
                foreach ($all as $ch) {
                    $cat = $ch['category'];
                    if ($selectedSet !== null && !isset($selectedSet[$cat])) continue;
                    if (isset($seenCats[$cat])) continue;
                    $checkCat->execute([$cat]); $eid = $checkCat->fetchColumn();
                    if ($eid) $updateCat->execute([$ch['cat_sort'], $eid]); else $insertCat->execute([$cat, $ch['cat_sort']]);
                    $seenCats[$cat] = true;
                }
                $dupCheck = $db->prepare("SELECT id FROM channels WHERE (name = ? AND category = ?) OR original_url = ? LIMIT 1");
                $insertCh = $db->prepare("INSERT INTO channels (name, number, category, sort_order, encrypted_url, original_url, logo_url, is_active) VALUES (?, ?, ?, ?, ?, ?, ?, 1)");
                $maxNum = (int)$db->query("SELECT COALESCE(MAX(number), 0) FROM channels")->fetchColumn();
                $inserted = 0; $skipped = 0;
                foreach ($all as $ch) {
                    $cat = $ch['category'];
                    if ($selectedSet !== null && !isset($selectedSet[$cat])) continue;
                    $name = trim($ch['name']); $rawUrl = trim($ch['url']);
                    if ($name === '' || $rawUrl === '') { $skipped++; continue; }
                    $dupCheck->execute([$name, $cat, $rawUrl]);
                    if ($dupCheck->fetchColumn()) { $skipped++; continue; }
                    // Preserve original HTTP or HTTPS protocol for universal server compatibility
                    $maxNum++;
                    $insertCh->execute([$name, $maxNum, $cat, $ch['ch_sort'], encryptUrl($rawUrl), $rawUrl, $ch['logo'] ?? '']);
                    $inserted++;
                }
                $db->commit();
                jsonOk(['inserted' => $inserted, 'skipped' => $skipped, 'message' => "✅ تم استيراد $inserted قناة"]);
            } catch (Exception $e) { $db->rollBack(); throw $e; }
            break;

        case 'admin_list_users':
            requireAdmin();
            $db = getDB();
            try { $db->exec("ALTER TABLE users ADD COLUMN portal_main_enabled INTEGER NOT NULL DEFAULT 0"); } catch (Throwable $e) {}
            $pinMap = getPinProtectedDevicesMap($db);
            $uRows = $db->query("SELECT * FROM users ORDER BY id DESC")->fetchAll(PDO::FETCH_ASSOC);
            foreach ($uRows as &$uRow) {
                $dKey = strtoupper(trim((string)($uRow['device_id'] ?? '')));
                $uRow['has_pin'] = isset($pinMap[$dKey]) ? 1 : 0;
                $uRow['portal_main_enabled'] = (int)($uRow['portal_main_enabled'] ?? 0);
            }
            unset($uRow);
            jsonOk(['users' => $uRows, 'pin_devices' => array_keys($pinMap)]);
            break;

        case 'admin_toggle_portal_main':
            requireAdmin();
            $db = getDB();
            try { $db->exec("ALTER TABLE users ADD COLUMN portal_main_enabled INTEGER NOT NULL DEFAULT 0"); } catch (Throwable $e) {}
            $devId = normalizeDeviceCodeApi((string)($_POST['device_id'] ?? ''));
            $enableFlag = !empty($_POST['portal_main_enabled']) ? 1 : 0;
            if ($devId === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $devId)) {
                jsonError('يرجى إدخال رمز الشاشة الصحيح (DEV-XXXX-XXXX)');
            }
            $st = $db->prepare("SELECT id FROM users WHERE UPPER(device_id) = ? LIMIT 1");
            $st->execute([$devId]);
            $existingUid = $st->fetchColumn();
            if ($existingUid) {
                $db->prepare("UPDATE users SET portal_main_enabled = ?, updated_at = datetime('now') WHERE id = ?")->execute([$enableFlag, $existingUid]);
            } else {
                $expDefault = date('Y-m-d', strtotime('+30 days'));
                $db->prepare("INSERT INTO users (device_id, device_name, expire_date, is_active, portal_main_enabled, trial_used, created_at, updated_at, registration_source) VALUES (?, 'جهاز مشترك', ?, 1, ?, 1, datetime('now'), datetime('now'), 'admin')")->execute([$devId, $expDefault, $enableFlag]);
            }
            $sec = getSecondaryPortalPdo();
            if ($sec !== null) {
                try {
                    $sec->exec("ALTER TABLE users ADD COLUMN portal_main_enabled INTEGER NOT NULL DEFAULT 0");
                    $sec->prepare("UPDATE users SET portal_main_enabled = ? WHERE UPPER(device_id) = ?")->execute([$enableFlag, $devId]);
                } catch (Throwable $e) {}
            }
            jsonOk([
                'device_id'           => $devId,
                'portal_main_enabled' => $enableFlag,
                'message'             => $enableFlag
                    ? "📡 تم تفعيل القائمة الرئيسية للجهاز ({$devId}) بنجاح!"
                    : "🚫 تم إيقاف وإخفاء القائمة الرئيسية عن الجهاز ({$devId}) بنجاح!"
            ]);
            break;

        case 'admin_remove_user_pin':
            requireAdmin();
            $db = getDB();
            $devId = normalizeDeviceCodeApi((string)($_POST['device_id'] ?? ''));
            $uid   = (int)($_POST['id'] ?? 0);
            if ($devId === '' && $uid > 0) {
                $st = $db->prepare("SELECT device_id FROM users WHERE id = ? LIMIT 1");
                $st->execute([$uid]);
                $devId = normalizeDeviceCodeApi((string)$st->fetchColumn());
            }
            if ($devId === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $devId)) {
                jsonError('يرجى إدخال رمز الشاشة الصحيح (DEV-XXXX-XXXX) لإلغاء قفل الـ PIN');
            }
            $removed = removeDevicePinAcrossAllDbs($db, $devId);
            logActivity($db, $devId, 'admin_remove_user_pin', ['removed_rows' => $removed]);
            jsonOk([
                'device_id' => $devId,
                'removed'   => $removed,
                'message'   => "🔓 تم نزع وإلغاء تشفير قفل الـ PIN للقوائم الخاصة بالشاشة ({$devId}) بنجاح!"
            ]);
            break;

        case 'admin_add_user':
            requireAdmin();
            $device_id = strtoupper(trim($_POST['device_id'] ?? '')); $expire = trim($_POST['expire_date'] ?? '');
            if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $device_id)) jsonError('صيغة الرمز خاطئة');
            getDB()->prepare("INSERT INTO users (device_id, expire_date, device_name, notes, is_active, trial_used, created_at, updated_at, registration_source) VALUES (?, ?, ?, ?, 1, 1, datetime('now'), datetime('now'), 'admin') ON CONFLICT(device_id) DO UPDATE SET expire_date = excluded.expire_date, is_active = 1, updated_at = datetime('now')")
               ->execute([$device_id, $expire, trim($_POST['device_name'] ?? ''), trim($_POST['notes'] ?? '')]);
            jsonOk(['message' => 'تم الحفظ']);
            break;

        case 'admin_update_user':
            requireAdmin();
            $id = (int)($_POST['id'] ?? 0);
            getDB()->prepare("UPDATE users SET expire_date=?, is_active=?, notes=?, updated_at=datetime('now') WHERE id=?")->execute([trim($_POST['expire_date'] ?? ''), (int)($_POST['is_active'] ?? 1), trim($_POST['notes'] ?? ''), $id]);
            jsonOk(['message' => 'تم التحديث']);
            break;

        case 'admin_delete_user':
            requireAdmin();
            getDB()->prepare("DELETE FROM users WHERE id = ?")->execute([(int)($_POST['id'] ?? 0)]);
            jsonOk(['message' => 'تم الحذف']);
            break;

        case 'admin_reset_trial':
            requireAdmin();
            $id = (int)($_POST['id'] ?? 0);
            if ($id <= 0) jsonError('معرف غير صالح');
            getDB()->prepare("UPDATE users SET trial_used = 0, bad_attempts = 0, updated_at = datetime('now') WHERE id = ?")->execute([$id]);
            logActivity(getDB(), '', 'admin_reset_trial', ['user_id' => $id]);
            jsonOk(['message' => 'تم إعادة تعيين التجربة']);
            break;

        case 'admin_list_active_domains':
            requireAdmin();
            jsonOk(['domains' => collectActiveDomainsSummary(getDB())]);
            break;

        case 'admin_bulk_replace_domain':
            requireAdmin();
            $oldDomain = trim((string)($_POST['old_domain'] ?? ''));
            $newDomain = trim((string)($_POST['new_domain'] ?? ''));
            if ($oldDomain === '' || $newDomain === '') {
                jsonError('يرجى إدخال الدومين القديم المستهدف والدومين الجديد المقترح');
            }
            if (strcasecmp(rtrim($oldDomain, '/'), rtrim($newDomain, '/')) === 0) {
                jsonError('الدومين الجديد مطابق للدومين القديم');
            }

            $db = getDB();
            $resPrimary = bulkReplaceDomainInPdo($db, $oldDomain, $newDomain, true);
            $resSec = ['playlists' => 0, 'devices' => [], 'channels' => 0, 'favorites' => 0];
            $secDb = getSecondaryPortalPdo();
            if ($secDb !== null) {
                try {
                    $resSec = bulkReplaceDomainInPdo($secDb, $oldDomain, $newDomain, false);
                } catch (Throwable $e) {}
            }

            // تحديث ملف data.txt تلقائياً وتسجيل التحويل في domain_migrations ليتم تحديث قاعدة البيانات المحلية للتطبيق عند كل الزبائن فور إعادة إقلاع التطبيق
            $dataTxtUpdated = updateDataTxtDomainAndName($oldDomain, $newDomain);
            recordDomainMigration($db, $oldDomain, $newDomain, '', 'global', null);

            $totalPlaylists = max($resPrimary['playlists'], $resSec['playlists']);
            $allDevices = array_values(array_unique(array_merge($resPrimary['devices'], $resSec['devices'])));
            $totalDevices = count($allDevices);
            $totalChannels = $resPrimary['channels'];
            $totalFavorites = max($resPrimary['favorites'], $resSec['favorites']);

            logActivity($db, '', 'admin_bulk_replace_domain', [
                'old_domain'        => $oldDomain,
                'new_domain'        => $newDomain,
                'updated_playlists' => $totalPlaylists,
                'updated_devices'   => $totalDevices,
                'updated_channels'  => $totalChannels,
                'updated_favorites' => $totalFavorites,
                'data_txt_updated'  => $dataTxtUpdated
            ]);

            jsonOk([
                'old_domain'        => $oldDomain,
                'new_domain'        => $newDomain,
                'updated_playlists' => $totalPlaylists,
                'updated_devices'   => $totalDevices,
                'updated_channels'  => $totalChannels,
                'updated_favorites' => $totalFavorites,
                'data_txt_updated'  => $dataTxtUpdated,
                'domains'           => collectActiveDomainsSummary($db),
                'data_txt_entries'  => readDataTxtEntries(),
                'message'           => "✅ تم استبدال الدومين بنجاح في قاعدة البيانات وفي ملف data.txt! (تحديث {$totalPlaylists} قائمة لـ {$totalDevices} مشترك، و{$dataTxtUpdated} سيرفر في data.txt) — سيتحدث تلقائياً في قاعدة البيانات المحلية للتطبيق عند جميع الزبائن فور إعادة إقلاع التطبيق."
            ]);
            break;


        case 'admin_get_app_update':
            requireAdmin();
            $db = getDB();
            jsonOk(['app_update' => getAppUpdateConfig($db)]);
            break;

        case 'admin_save_app_update','admin_toggle_portal_main':
            requireAdmin();
            $db = getDB();
            $apkUrl = trim((string)($_POST['apk_url'] ?? ''));
            $enabled = !empty($_POST['update_enabled']) ? 1 : 0;
            if ($enabled && ($apkUrl === '' || !preg_match('~^https?://~i', $apkUrl))) {
                jsonError('⚠️ عند تفعيل إشعار التحديث، يرجى إدخال رابط مباشر صالح لملف الـ APK يبدأ بـ http:// أو https://');
            }
            $saved = saveAppUpdateConfigAcrossDbs($db, $_POST);
            logActivity($db, '', 'admin_save_app_update','admin_toggle_portal_main', $saved);
            jsonOk([
                'app_update' => $saved,
                'message'    => $saved['enabled']
                    ? "🚀 تم تفعيل إشعار التحديث للإصدار (v{$saved['version_name']}) بنجاح! سينبثق تلقائياً لجميع الزبائن عند فتح التطبيق لتحميله وتثبيته."
                    : "✅ تم حفظ إعدادات التحديث (إشعار التحديث متوقف حالياً)"
            ]);
            break;

        case 'admin_get_data_txt':
            requireAdmin();
            $db = getDB();
            $entries = readDataTxtEntries();
            $activeDomains = collectActiveDomainsSummary($db);
            $domainCountsMap = [];
            foreach ($activeDomains as $ad) {
                $domainCountsMap[strtolower($ad['domain'])] = (int)($ad['playlists_count'] ?? 0);
            }
            foreach ($entries as &$e) {
                $domKey = strtolower($e['domain']);
                $e['playlists_count'] = $domainCountsMap[$domKey] ?? 0;
            }
            unset($e);
            $rawContent = (string)@file_get_contents(getDataTxtFilePath());
            jsonOk([
                'entries'     => $entries,
                'raw_content' => $rawContent
            ]);
            break;

        case 'admin_save_data_txt_entry':
            requireAdmin();
            $mode    = strtolower(trim((string)($_POST['mode'] ?? 'edit'))); // add | edit
            $oldName = trim((string)($_POST['old_name'] ?? ''));
            $oldUrl  = trim((string)($_POST['old_url'] ?? ''));
            $newName = trim((string)($_POST['new_name'] ?? ''));
            $newUrl  = normalizeServerBaseUrlApi((string)($_POST['new_url'] ?? ''));

            if ($newName === '' || $newUrl === '' || extractDomainAuthority($newUrl) === '') {
                jsonError('⚠️ يرجى إدخال اسم السيرفر ورابط الدومين / الهوست بشكل صحيح (مثال: http://host.com:8080)');
            }

            $entries = readDataTxtEntries();
            $found = false;
            if ($mode === 'edit') {
                foreach ($entries as &$e) {
                    if (($oldName !== '' && strcasecmp($e['name'], $oldName) === 0) ||
                        ($oldUrl !== '' && strcasecmp($e['url'], normalizeServerBaseUrlApi($oldUrl)) === 0)) {
                        $e['name']   = $newName;
                        $e['url']    = $newUrl;
                        $e['domain'] = extractDomainAuthority($newUrl);
                        $found = true;
                        break;
                    }
                }
                unset($e);
            }
            if (!$found) {
                $entries[] = [
                    'index'  => count($entries),
                    'name'   => $newName,
                    'url'    => $newUrl,
                    'domain' => extractDomainAuthority($newUrl)
                ];
            }
            writeDataTxtEntries($entries);

            $db = getDB();
            $resPrimary = bulkRenameAndReplaceServerInPdo($db, $oldName ?: $newName, $newName, $oldUrl, $newUrl);
            $secDb = getSecondaryPortalPdo();
            $resSec = ['playlists' => 0, 'devices' => []];
            if ($secDb !== null) {
                try {
                    $resSec = bulkRenameAndReplaceServerInPdo($secDb, $oldName ?: $newName, $newName, $oldUrl, $newUrl);
                } catch (Throwable $e) {}
            }
            if ($oldUrl !== '' && strcasecmp(extractDomainAuthority($oldUrl), extractDomainAuthority($newUrl)) !== 0) {
                recordDomainMigration($db, $oldUrl, $newUrl, $newName, 'global', null);
            }

            $updatedPlaylists = max($resPrimary['playlists'], $resSec['playlists']);
            logActivity($db, '', 'admin_save_data_txt_entry', [
                'mode' => $mode, 'old_name' => $oldName, 'new_name' => $newName,
                'old_url' => $oldUrl, 'new_url' => $newUrl, 'updated_playlists' => $updatedPlaylists
            ]);

            jsonOk([
                'entries'           => readDataTxtEntries(),
                'raw_content'       => (string)@file_get_contents(getDataTxtFilePath()),
                'updated_playlists' => $updatedPlaylists,
                'domains'           => collectActiveDomainsSummary($db),
                'message'           => $mode === 'add'
                    ? "✅ تمت إضافة السيرفر ({$newName} — {$newUrl}) إلى ملف data.txt بنجاح!"
                    : "✅ تم تحديث السيرفر ({$newName}) والدومين ({$newUrl}) في data.txt وتحديث ({$updatedPlaylists}) قائمة مشترك في قاعدة البيانات!"
            ]);
            break;

        case 'admin_delete_data_txt_entry':
            requireAdmin();
            $targetName = trim((string)($_POST['name'] ?? ''));
            $targetUrl  = normalizeServerBaseUrlApi((string)($_POST['url'] ?? ''));
            $entries = readDataTxtEntries();
            $filtered = [];
            $deleted = 0;
            foreach ($entries as $e) {
                $matchName = ($targetName !== '' && strcasecmp($e['name'], $targetName) === 0);
                $matchUrl  = ($targetUrl !== '' && strcasecmp($e['url'], $targetUrl) === 0);
                if (($matchName && $matchUrl) || ($targetUrl === '' && $matchName) || ($targetName === '' && $matchUrl)) {
                    $deleted++;
                    continue;
                }
                $filtered[] = $e;
            }
            writeDataTxtEntries($filtered);
            jsonOk([
                'entries'     => readDataTxtEntries(),
                'raw_content' => (string)@file_get_contents(getDataTxtFilePath()),
                'message'     => "🗑 تم حذف السيرفر ({$targetName}) من ملف data.txt بنجاح"
            ]);
            break;

        case 'admin_save_data_txt_raw':
            requireAdmin();
            $rawContent = (string)($_POST['raw_content'] ?? '');
            if (trim($rawContent) === '') {
                jsonError('⚠️ محتوى ملف data.txt لا يمكن أن يكون فارغاً');
            }
            $oldEntries = readDataTxtEntries();
            $newEntries = parseDataTxtLinesToEntries($rawContent);
            if (empty($newEntries)) {
                jsonError('⚠️ لم يتم العثور على أي سطر صالح بصيغة (اسم السيرفر|http://domain:port) داخل النص المدخل');
            }

            // حفظ النص في data.txt
            @file_put_contents(getDataTxtFilePath(), trim($rawContent) . "\n");

            // مقارنة الدومينات القديمة بالجديدة لنفس اسم السيرفر أو نفس الترتيب وتحديث قواعد البيانات تلقائياً!
            $oldByName = [];
            foreach ($oldEntries as $oe) {
                $oldByName[strtolower(trim($oe['name']))] = $oe;
            }
            $db = getDB();
            $secDb = getSecondaryPortalPdo();
            $totalPlaylistsUpdated = 0;
            $migrationsCount = 0;

            foreach ($newEntries as $idx => $ne) {
                $keyName = strtolower(trim($ne['name']));
                $matchedOld = $oldByName[$keyName] ?? ($oldEntries[$idx] ?? null);
                if ($matchedOld !== null) {
                    $oldU = $matchedOld['url'];
                    $newU = $ne['url'];
                    $oldN = $matchedOld['name'];
                    $newN = $ne['name'];
                    if (strcasecmp(extractDomainAuthority($oldU), extractDomainAuthority($newU)) !== 0 || strcasecmp($oldN, $newN) !== 0) {
                        $r1 = bulkRenameAndReplaceServerInPdo($db, $oldN, $newN, $oldU, $newU);
                        $r2 = ['playlists' => 0];
                        if ($secDb !== null) {
                            try { $r2 = bulkRenameAndReplaceServerInPdo($secDb, $oldN, $newN, $oldU, $newU); } catch (Throwable $e) {}
                        }
                        $totalPlaylistsUpdated += max($r1['playlists'], $r2['playlists']);
                        if (strcasecmp(extractDomainAuthority($oldU), extractDomainAuthority($newU)) !== 0) {
                            recordDomainMigration($db, $oldU, $newU, $newN, 'global', null);
                            $migrationsCount++;
                        }
                    }
                }
            }

            logActivity($db, '', 'admin_save_data_txt_raw', [
                'servers_count'     => count($newEntries),
                'migrations_count'  => $migrationsCount,
                'updated_playlists' => $totalPlaylistsUpdated
            ]);

            jsonOk([
                'entries'           => readDataTxtEntries(),
                'raw_content'       => (string)@file_get_contents(getDataTxtFilePath()),
                'updated_playlists' => $totalPlaylistsUpdated,
                'migrations_count'  => $migrationsCount,
                'domains'           => collectActiveDomainsSummary($db),
                'message'           => "✅ تم حفظ ملف data.txt بنجاح (" . count($newEntries) . " سيرفر)" . ($migrationsCount > 0 ? " وتعميم ({$migrationsCount}) دومين جديد على ({$totalPlaylistsUpdated}) قائمة مشترك في قاعدة البيانات!" : "!")
            ]);
            break;

        case 'admin_bulk_renew':
            requireAdmin();
            $days = (int)($_POST['days'] ?? 0); $filter = $_POST['filter'] ?? 'all';
            $where = "";
            if ($filter === 'active') $where = "WHERE is_active = 1 AND expire_date >= date('now')";
            else if ($filter === 'expired') $where = "WHERE expire_date < date('now')";
            else if ($filter === 'expiring7') $where = "WHERE expire_date BETWEEN date('now') AND date('now', '+7 days')";
            else if ($filter === 'expiring30') $where = "WHERE expire_date BETWEEN date('now') AND date('now', '+30 days')";
            $sql = "UPDATE users SET expire_date = date(CASE WHEN expire_date < date('now') THEN date('now') ELSE expire_date END, '+' || ? || ' days'), is_active = 1, updated_at = datetime('now') $where";
            $stmt = getDB()->prepare($sql); $stmt->execute([$days]);
            jsonOk(['updated' => $stmt->rowCount(), 'message' => "تم التمديد"]);
            break;

        case 'admin_card_stats':
            requireAdmin();
            jsonOk([
                'unused' => (int)getDB()->query("SELECT COUNT(*) FROM activation_cards WHERE status = 'unused'")->fetchColumn(),
                'used' => (int)getDB()->query("SELECT COUNT(*) FROM activation_cards WHERE status = 'used'")->fetchColumn(),
                'expired' => (int)getDB()->query("SELECT COUNT(*) FROM activation_cards WHERE status = 'unused' AND expires_at IS NOT NULL AND expires_at < datetime('now')")->fetchColumn(),
                'invalid_duplicate' => (int)getDB()->query("SELECT COUNT(*) FROM activation_cards WHERE status = 'invalid_duplicate'")->fetchColumn(),
                'total_redemptions' => (int)getDB()->query("SELECT COUNT(*) FROM card_redemptions")->fetchColumn(),
            ]);
            break;

        case 'admin_list_cards':
            requireAdmin();
            jsonOk(['cards' => getDB()->query("
                SELECT c.id, c.code AS code_formatted, c.card_type AS type_label, c.duration_days, c.batch_id, c.status,
                       c.created_at, c.expires_at, c.used_at, c.used_by_device, c.attempts, c.last_attempt_at, c.notes,
                       c.reseller_id, c.reseller_name, c.points_cost,
                       r.redeemed_at, r.redeemed_by_device AS redemption_device
                FROM activation_cards c
                LEFT JOIN card_redemptions r ON r.code = c.code COLLATE NOCASE
                ORDER BY c.id DESC LIMIT 300
            ")->fetchAll()]);
            break;

        case 'admin_generate_cards':
            requireAdmin();
            $type = $_POST['card_type'] ?? '1month'; $count = min(1000, max(1, (int)($_POST['count'] ?? 10)));
            $daysMap = ['trial' => 3, '1month' => 30, '3months' => 90, '6months' => 180, '1year' => 365, 'custom' => (int)($_POST['custom_days'] ?? 30)];
            $days = $daysMap[$type] ?? 30; $batch_id = trim($_POST['batch_name'] ?? '') ?: 'BATCH-' . date('YmdHis');
            $expires_at = trim($_POST['expires_at'] ?? '') ?: null;
            $db = getDB();
            $stmt = $db->prepare("INSERT INTO activation_cards (code, card_type, duration_days, batch_id, expires_at, status, created_at) VALUES (?, ?, ?, ?, ?, 'unused', datetime('now'))");
            $generated = []; $maxTries = $count * 10; $attempts = 0;
            while (count($generated) < $count && $attempts < $maxTries) {
                $attempts++;
                $formatted = substr(strtoupper(bin2hex(random_bytes(4))), 0, 4) . '-' . substr(strtoupper(bin2hex(random_bytes(4))), 0, 4);
                // تأكد أن الرمز غير محروق في السجل الدائم
                $check = $db->prepare("SELECT 1 FROM card_redemptions WHERE code = ? COLLATE NOCASE");
                $check->execute([$formatted]);
                if ($check->fetch()) continue;
                try { $stmt->execute([$formatted, $type, $days, $batch_id, $expires_at]); $generated[] = $formatted; } catch (Throwable $e) {}
            }
            jsonOk(['count' => count($generated), 'card_label' => $type, 'batch_id' => $batch_id, 'cards' => $generated]);
            break;

        case 'admin_delete_card':
            requireAdmin();
            getDB()->prepare("DELETE FROM activation_cards WHERE id = ?")->execute([(int)($_POST['id'] ?? 0)]);
            jsonOk(['message' => 'تم الحذف']);
            break;

        case 'admin_list_batches':
            requireAdmin();
            jsonOk(['batches' => getDB()->query("SELECT batch_id, card_type AS type_label, COUNT(*) as total, SUM(CASE WHEN status='unused' THEN 1 ELSE 0 END) as unused, SUM(CASE WHEN status='used' THEN 1 ELSE 0 END) as used FROM activation_cards GROUP BY batch_id, card_type ORDER BY MIN(id) DESC")->fetchAll()]);
            break;

        case 'admin_delete_batch':
            requireAdmin();
            getDB()->prepare("DELETE FROM activation_cards WHERE batch_id = ? AND status = 'unused'")->execute([trim($_POST['batch_id'] ?? '')]);
            jsonOk(['message' => 'تم الحذف']);
            break;

        case 'admin_cleanup_categories':
            requireAdmin();
            $stmt = getDB()->query("DELETE FROM categories WHERE name NOT IN (SELECT DISTINCT category FROM channels WHERE category IS NOT NULL AND category != '')");
            jsonOk(['deleted' => $stmt->rowCount(), 'message' => 'تم التنظيف']);
            break;

        case 'admin_list_activity':
            requireAdmin();
            $limit = min(500, max(1, (int)($_REQUEST['limit'] ?? 100)));
            $filter = trim($_REQUEST['filter'] ?? '');
            $sql = "SELECT * FROM activity_log"; $params = [];
            if ($filter !== '') { $sql .= " WHERE action = ?"; $params[] = $filter; }
            $sql .= " ORDER BY id DESC LIMIT $limit";
            $stmt = getDB()->prepare($sql); $stmt->execute($params);
            jsonOk(['activities' => $stmt->fetchAll()]);
            break;

        case 'admin_list_bad_attempts':
            requireAdmin();
            $limit = min(500, max(1, (int)($_REQUEST['limit'] ?? 100)));
            $stmt = getDB()->prepare("SELECT * FROM activity_log WHERE action = 'bad_card_attempt' ORDER BY id DESC LIMIT $limit"); $stmt->execute();
            jsonOk(['attempts' => $stmt->fetchAll()]);
            break;

        case 'admin_clear_activity_log':
            requireAdmin();
            $days = max(1, (int)($_POST['days'] ?? 30));
            $stmt = getDB()->prepare("DELETE FROM activity_log WHERE created_at < datetime('now', '-' || ? || ' days')"); $stmt->execute([$days]);
            jsonOk(['deleted' => $stmt->rowCount(), 'message' => "تم حذف السجلات الأقدم من {$days} يوم"]);
            break;

        case 'admin_user_details':
            requireAdmin();
            $device_id = strtoupper(trim($_REQUEST['device_id'] ?? ''));
            if ($device_id === '') jsonError('device_id مطلوب');
            $db = getDB();
            $stmt = $db->prepare("SELECT * FROM users WHERE device_id = ?"); $stmt->execute([$device_id]);
            $user = $stmt->fetch();
            if (!$user) jsonError('المستخدم غير موجود', 404);
            $stmt = $db->prepare("SELECT * FROM activity_log WHERE device_id = ? ORDER BY id DESC LIMIT 50"); $stmt->execute([$device_id]);
            $history = $stmt->fetchAll();
            $stmt = $db->prepare("SELECT * FROM card_redemptions WHERE redeemed_by_device = ? ORDER BY redeemed_at DESC"); $stmt->execute([$device_id]);
            $redeemed = $stmt->fetchAll();
            $xtreamLists = getDeviceXtreamLists($db, $device_id, false);
            jsonOk(['user' => $user, 'activity' => $history, 'redeemed_cards' => $redeemed, 'xtream_lists' => $xtreamLists]);
            break;

        // 🆕 قائمة كل الاستهلاكات (سجل دائم غير قابل للتعديل)
        case 'admin_list_redemptions':
            requireAdmin();
            $limit = min(1000, max(1, (int)($_REQUEST['limit'] ?? 200)));
            $filter_device = trim($_REQUEST['device_id'] ?? '');
            $sql = "SELECT * FROM card_redemptions"; $params = [];
            if ($filter_device !== '') { $sql .= " WHERE redeemed_by_device = ?"; $params[] = strtoupper($filter_device); }
            $sql .= " ORDER BY id DESC LIMIT $limit";
            $stmt = getDB()->prepare($sql); $stmt->execute($params);
            jsonOk(['redemptions' => $stmt->fetchAll()]);
            break;

        // 🆕 إحراق قسيمة يدوياً (بدون تفعيل) — لأغراض إدارية
        case 'admin_burn_card':
            if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') { jsonError('Method Not Allowed', 405); }

            requireAdmin();
            $code = strtoupper(trim($_POST['code'] ?? ''));
            $reason = trim($_POST['reason'] ?? 'إحراق إداري');
            if ($code === '') jsonError('رمز البطاقة مطلوب');
            $db = getDB();
            $db->beginTransaction();
            try {
                $stmt = $db->prepare("SELECT * FROM activation_cards WHERE code = ? COLLATE NOCASE AND status = 'unused' ORDER BY id ASC LIMIT 1");
                $stmt->execute([$code]);
                $card = $stmt->fetch();

                if (!$card) {
                    $db->rollBack();
                    jsonError('البطاقة غير موجودة أو مستعملة مسبقاً', 410, 'CARD_UNAVAILABLE');
                }

                // إحراق نهائي
                $db->prepare("INSERT INTO card_redemptions (code, redeemed_by_device, redeemed_at, card_id, duration_days, ip_address) VALUES (?, 'ADMIN_BURN', datetime('now'), ?, ?, ?)")
                   ->execute([$code, $card['id'], (int)$card['duration_days'], $_SERVER['REMOTE_ADDR'] ?? null]);

                $db->prepare("UPDATE activation_cards SET status = 'used', used_at = datetime('now'), used_by_device = 'ADMIN_BURN', notes = COALESCE(notes,'') || ' | 🔥 ' || ? WHERE id = ?")
                   ->execute([$reason, $card['id']]);

                logActivity($db, '', 'admin_burn_card', ['card_code' => $code, 'reason' => $reason]);
                $db->commit();
                jsonOk(['message' => 'تم إحراق البطاقة نهائياً', 'code' => $code]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'admin_block_user':
            requireAdmin();
            $id = (int)($_POST['id'] ?? 0); $reason = trim($_POST['reason'] ?? 'حجب إداري');
            if ($id <= 0) jsonError('معرف غير صالح');
            getDB()->prepare("UPDATE users SET is_active = 0, blocked_reason = ?, updated_at = datetime('now') WHERE id = ?")->execute([$reason, $id]);
            logActivity(getDB(), '', 'admin_block_user', ['user_id' => $id, 'reason' => $reason]);
            jsonOk(['message' => 'تم الحجب']);
            break;

        case 'admin_unblock_user':
            requireAdmin();
            $id = (int)($_POST['id'] ?? 0); $extend_days = (int)($_POST['extend_days'] ?? 0);
            if ($id <= 0) jsonError('معرف غير صالح');
            $db = getDB();
            if ($extend_days > 0) {
                $db->prepare("UPDATE users SET is_active = 1, blocked_reason = NULL, expire_date = date(CASE WHEN expire_date < date('now') THEN date('now') ELSE expire_date END, '+' || ? || ' days'), updated_at = datetime('now') WHERE id = ?")->execute([$extend_days, $id]);
            } else {
                $db->prepare("UPDATE users SET is_active = 1, blocked_reason = NULL, updated_at = datetime('now') WHERE id = ?")->execute([$id]);
            }
            logActivity($db, '', 'admin_unblock_user', ['user_id' => $id, 'extend_days' => $extend_days]);
            jsonOk(['message' => 'تم رفع الحجب']);
            break;

        case 'admin_fix_card':
            requireAdmin();
            $code = strtoupper(trim($_POST['code'] ?? ''));
            if ($code === '') jsonError('رمز البطاقة مطلوب');
            $db = getDB();
            $db->beginTransaction();
            try {
                // ابحث عن القسيمة
                $stmt = $db->prepare("SELECT * FROM activation_cards WHERE code = ? COLLATE NOCASE ORDER BY id ASC LIMIT 1");
                $stmt->execute([$code]);
                $card = $stmt->fetch();
                // املأ سجل الاستهلاك (إن لم يكن موجوداً)
                $db->prepare("INSERT OR IGNORE INTO card_redemptions (code, redeemed_by_device, redeemed_at, card_id, duration_days) VALUES (?, 'ADMIN_FIX', COALESCE(?, datetime('now')), ?, ?)")
                   ->execute([$code, $card['used_at'] ?? null, $card['id'] ?? null, (int)($card['duration_days'] ?? 0)]);
                // حدّث القسيمة
                $db->prepare("UPDATE activation_cards SET status = 'used', used_at = COALESCE(used_at, datetime('now')), expires_at = COALESCE(expires_at, datetime('now')) WHERE code = ? COLLATE NOCASE")
                   ->execute([$code]);
                $db->commit();
                jsonOk(['message' => 'تم إصلاح البطاقة نهائياً']);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        /* ============================================================
           💼 إدارة الموزعين من لوحة التحكم (Admin Reseller Endpoints)
           ============================================================ */
        case 'admin_list_resellers':
            requireAdmin();
            $db = getDB();
            $resellers = $db->query("
                SELECT r.*,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'unused') AS unused_cards_count,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'used') AS used_cards_count
                FROM resellers r
                ORDER BY r.id DESC
            ")->fetchAll(PDO::FETCH_ASSOC);
            jsonOk([
                'resellers' => $resellers,
                'summary'   => [
                    'total_resellers'  => count($resellers),
                    'active_resellers' => count(array_filter($resellers, fn($x) => (int)$x['is_active'] === 1)),
                    'total_balance'    => array_sum(array_map(fn($x) => (int)$x['points'], $resellers)),
                    'total_spent'      => array_sum(array_map(fn($x) => (int)$x['total_points_spent'], $resellers)),
                    'total_generated'  => array_sum(array_map(fn($x) => (int)$x['total_cards_generated'], $resellers)),
                ]
            ]);
            break;

        case 'admin_add_reseller':
            requireAdmin();
            $username    = strtolower(trim((string)($_POST['username'] ?? '')));
            $password    = trim((string)($_POST['password'] ?? ''));
            $displayName = trim((string)($_POST['display_name'] ?? ''));
            $phone       = trim((string)($_POST['phone'] ?? ''));
            $initPoints  = max(0, (int)($_POST['points'] ?? 0));
            $notes       = trim((string)($_POST['notes'] ?? ''));

            if ($username === '' || !preg_match('/^[a-zA-Z0-9_.\-]{3,32}$/', $username)) {
                jsonError('اسم المستخدم يجب أن يتكون من 3 إلى 32 حرفاً أو رقماً باللغة الإنجليزية بدون مسافات');
            }
            if (strlen($password) < 4) {
                jsonError('كلمة المرور يجب أن تكون 4 أحرف أو أرقام على الأقل');
            }
            if ($displayName === '') {
                $displayName = $username;
            }

            $db = getDB();
            $chk = $db->prepare("SELECT id FROM resellers WHERE username = ? COLLATE NOCASE LIMIT 1");
            $chk->execute([$username]);
            if ($chk->fetchColumn()) {
                jsonError('اسم المستخدم مسجل لموزع آخر مسبقاً');
            }

            $db->beginTransaction();
            try {
                $hash = password_hash($password, PASSWORD_DEFAULT);
                $stmt = $db->prepare("
                    INSERT INTO resellers (username, password_hash, password_plain, display_name, phone, points, total_points_received, total_points_spent, total_cards_generated, is_active, notes, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 1, ?, datetime('now'), datetime('now'))
                ");
                $stmt->execute([$username, $hash, $password, $displayName, $phone, $initPoints, $initPoints, $notes]);
                $resellerId = (int)$db->lastInsertId();

                if ($initPoints > 0) {
                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'credit_add', ?, ?, 'رصيد ابتدائي عند فتح الحساب من الإدارة', datetime('now'))
                    ")->execute([$resellerId, $initPoints, $initPoints]);
                }

                logActivity($db, '', 'admin_add_reseller', ['reseller_id' => $resellerId, 'username' => $username, 'initial_points' => $initPoints]);
                $db->commit();
                jsonOk(['id' => $resellerId, 'message' => "✅ تم فتح حساب الموزع ({$displayName}) بنجاح برصيد {$initPoints} نقطة"]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'admin_update_reseller':
            requireAdmin();
            $id          = (int)($_POST['id'] ?? 0);
            $displayName = trim((string)($_POST['display_name'] ?? ''));
            $username    = strtolower(trim((string)($_POST['username'] ?? '')));
            $phone       = trim((string)($_POST['phone'] ?? ''));
            $password    = trim((string)($_POST['password'] ?? ''));
            $isActive    = (int)($_POST['is_active'] ?? 1);
            $notes       = trim((string)($_POST['notes'] ?? ''));

            if ($id <= 0) jsonError('معرف الموزع غير صالح');
            $db = getDB();
            $curStmt = $db->prepare("SELECT * FROM resellers WHERE id = ? LIMIT 1");
            $curStmt->execute([$id]);
            $cur = $curStmt->fetch(PDO::FETCH_ASSOC);
            if (!$cur) jsonError('الموزع غير موجود', 404);

            if ($username !== '' && strtolower($username) !== strtolower($cur['username'])) {
                if (!preg_match('/^[a-zA-Z0-9_.\-]{3,32}$/', $username)) {
                    jsonError('اسم المستخدم غير صالح');
                }
                $dup = $db->prepare("SELECT id FROM resellers WHERE username = ? COLLATE NOCASE AND id != ? LIMIT 1");
                $dup->execute([$username, $id]);
                if ($dup->fetchColumn()) jsonError('اسم المستخدم مستعمل مسبقاً');
            } else {
                $username = $cur['username'];
            }

            if ($displayName === '') $displayName = $cur['display_name'];

            if ($password !== '') {
                if (strlen($password) < 4) jsonError('كلمة المرور قصيرة جداً');
                $hash = password_hash($password, PASSWORD_DEFAULT);
                $db->prepare("UPDATE resellers SET username=?, display_name=?, phone=?, password_hash=?, password_plain=?, is_active=?, notes=?, updated_at=datetime('now') WHERE id=?")
                   ->execute([$username, $displayName, $phone, $hash, $password, $isActive, $notes, $id]);
            } else {
                $db->prepare("UPDATE resellers SET username=?, display_name=?, phone=?, is_active=?, notes=?, updated_at=datetime('now') WHERE id=?")
                   ->execute([$username, $displayName, $phone, $isActive, $notes, $id]);
            }

            if ($isActive === 0) {
                $db->prepare("DELETE FROM reseller_sessions WHERE reseller_id = ?")->execute([$id]);
            }
            jsonOk(['message' => '✅ تم تحديث بيانات الموزع بنجاح']);
            break;

        case 'admin_add_reseller_points':
            requireAdmin();
            $id     = (int)($_POST['reseller_id'] ?? $_POST['id'] ?? 0);
            $amount = (int)($_POST['points'] ?? 0);
            $op     = trim((string)($_POST['operation'] ?? 'add')); // 'add' or 'deduct' or 'set'
            $notes  = trim((string)($_POST['notes'] ?? ''));

            if ($id <= 0) jsonError('يرجى تحديد الموزع');
            if ($amount <= 0 && $op !== 'set') jsonError('يرجى إدخال عدد نقاط صحيح أكبر من الصفر');

            $db = getDB();
            $db->beginTransaction();
            try {
                $stmt = $db->prepare("SELECT * FROM resellers WHERE id = ? LIMIT 1");
                $stmt->execute([$id]);
                $reseller = $stmt->fetch(PDO::FETCH_ASSOC);
                if (!$reseller) {
                    $db->rollBack();
                    jsonError('الموزع غير موجود', 404);
                }

                $currentPoints = (int)$reseller['points'];
                if ($op === 'deduct') {
                    if ($currentPoints < $amount) {
                        $db->rollBack();
                        jsonError("رصيد الموزع الحالي ({$currentPoints} نقطة) أقل من المراد خصمه ({$amount} نقطة)");
                    }
                    $delta = -$amount;
                    $newBalance = $currentPoints - $amount;
                    $txType = 'credit_deduct';
                    $defaultNote = "خصم نقاط من الإدارة (-{$amount} نقطة)";
                    $db->prepare("UPDATE resellers SET points = ?, updated_at = datetime('now') WHERE id = ?")
                       ->execute([$newBalance, $id]);
                } else {
                    $delta = $amount;
                    $newBalance = $currentPoints + $amount;
                    $txType = 'credit_add';
                    $defaultNote = "شحن نقاط من الإدارة (+{$amount} نقطة)";
                    $db->prepare("UPDATE resellers SET points = ?, total_points_received = total_points_received + ?, updated_at = datetime('now') WHERE id = ?")
                       ->execute([$newBalance, $amount, $id]);
                }

                $finalNote = $notes !== '' ? $notes : $defaultNote;
                $db->prepare("
                    INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                    VALUES (?, ?, ?, ?, ?, datetime('now'))
                ")->execute([$id, $txType, $delta, $newBalance, $finalNote]);

                logActivity($db, '', 'admin_reseller_points', [
                    'reseller_id'   => $id,
                    'reseller_name' => $reseller['display_name'],
                    'delta'         => $delta,
                    'balance_after' => $newBalance,
                    'notes'         => $finalNote
                ]);
                $db->commit();

                jsonOk([
                    'reseller_id'   => $id,
                    'new_balance'   => $newBalance,
                    'points_delta'  => $delta,
                    'message'       => $delta > 0
                        ? "✅ تم إرسال {$amount} نقطة إلى الموزع ({$reseller['display_name']}). الرصيد الجديد: {$newBalance} نقطة"
                        : "✅ تم خصم {$amount} نقطة من الموزع ({$reseller['display_name']}). الرصيد الجديد: {$newBalance} نقطة"
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'admin_delete_reseller':
            requireAdmin();
            $id = (int)($_POST['id'] ?? 0);
            if ($id <= 0) jsonError('معرف الموزع غير صالح');
            $db = getDB();
            $db->prepare("DELETE FROM reseller_sessions WHERE reseller_id = ?")->execute([$id]);
            $db->prepare("DELETE FROM resellers WHERE id = ?")->execute([$id]);
            jsonOk(['message' => '🗑️ تم حذف حساب الموزع بنجاح']);
            break;

        case 'admin_reseller_details':
            requireAdmin();
            $id = (int)($_REQUEST['id'] ?? $_REQUEST['reseller_id'] ?? 0);
            if ($id <= 0) jsonError('معرف الموزع غير صالح');
            $db = getDB();
            $st = $db->prepare("SELECT * FROM resellers WHERE id = ? LIMIT 1");
            $st->execute([$id]);
            $reseller = $st->fetch(PDO::FETCH_ASSOC);
            if (!$reseller) jsonError('الموزع غير موجود', 404);

            $txSt = $db->prepare("SELECT * FROM reseller_transactions WHERE reseller_id = ? ORDER BY id DESC LIMIT 150");
            $txSt->execute([$id]);
            $transactions = $txSt->fetchAll(PDO::FETCH_ASSOC);

            $cardsSt = $db->prepare("
                SELECT c.*, r.redeemed_at, r.redeemed_by_device AS redemption_device
                FROM activation_cards c
                LEFT JOIN card_redemptions r ON r.code = c.code COLLATE NOCASE
                WHERE c.reseller_id = ?
                ORDER BY c.id DESC LIMIT 200
            ");
            $cardsSt->execute([$id]);
            $cards = $cardsSt->fetchAll(PDO::FETCH_ASSOC);

            jsonOk([
                'reseller'     => $reseller,
                'transactions' => $transactions,
                'cards'        => $cards
            ]);
            break;

        /* ============================================================
           🏪 بوابة فضاء الموزعين الاحترافية (Reseller Portal Endpoints)
           ============================================================ */
        case 'reseller_login':
            $username = strtolower(trim((string)($_POST['username'] ?? '')));
            $password = trim((string)($_POST['password'] ?? ''));
            if ($username === '' || $password === '') {
                jsonError('يرجى إدخال اسم المستخدم وكلمة المرور');
            }
            $db = getDB();
            $stmt = $db->prepare("SELECT * FROM resellers WHERE username = ? COLLATE NOCASE LIMIT 1");
            $stmt->execute([$username]);
            $reseller = $stmt->fetch(PDO::FETCH_ASSOC);
            if (!$reseller || !password_verify($password, (string)$reseller['password_hash'])) {
                usleep(400000);
                jsonError('❌ اسم المستخدم أو كلمة المرور غير صحيحة', 401, 'INVALID_CREDENTIALS');
            }
            if ((int)($reseller['is_active'] ?? 1) !== 1) {
                jsonError('⛔ حساب الموزع الخاص بك موقوف حالياً. يرجى التواصل مع الإدارة.', 403, 'RESELLER_DISABLED');
            }

            $db->exec("DELETE FROM reseller_sessions WHERE expires_at <= datetime('now')");
            $token = generateToken(64);
            $ttl   = defined('SESSION_DURATION') ? SESSION_DURATION : 86400 * 7;
            $db->prepare("INSERT INTO reseller_sessions (reseller_id, token, expires_at, ip_address, created_at) VALUES (?, ?, datetime('now', '+' || ? || ' seconds'), ?, datetime('now'))")
               ->execute([$reseller['id'], $token, $ttl, $_SERVER['REMOTE_ADDR'] ?? '']);
            $db->prepare("UPDATE resellers SET last_login_at = datetime('now') WHERE id = ?")->execute([$reseller['id']]);

            unset($reseller['password_hash'], $reseller['password_plain']);
            jsonOk([
                'token'    => $token,
                'reseller' => $reseller,
                'packages' => array_values(getResellerPackagesCatalog()),
                'message'  => '✅ مرحباً بك في فضاء الموزعين'
            ]);
            break;

        case 'reseller_logout':
            $token = trim((string)($_REQUEST['reseller_token'] ?? $_REQUEST['token'] ?? ''));
            if ($token !== '') {
                getDB()->prepare("DELETE FROM reseller_sessions WHERE token = ?")->execute([$token]);
            }
            jsonOk(['message' => 'تم تسجيل الخروج بنجاح']);
            break;

        case 'reseller_dashboard':
            $reseller = requireReseller();
            $db = getDB();
            $rid = (int)$reseller['id'];

            $cardsSt = $db->prepare("
                SELECT c.id, c.code AS code_formatted, c.card_type, c.duration_days, c.points_cost,
                       c.status, c.created_at, c.used_at, c.used_by_device, c.notes,
                       r.redeemed_at, r.redeemed_by_device AS redemption_device
                FROM activation_cards c
                LEFT JOIN card_redemptions r ON r.code = c.code COLLATE NOCASE
                WHERE c.reseller_id = ?
                ORDER BY c.id DESC LIMIT 300
            ");
            $cardsSt->execute([$rid]);
            $cards = $cardsSt->fetchAll(PDO::FETCH_ASSOC);

            $txSt = $db->prepare("SELECT * FROM reseller_transactions WHERE reseller_id = ? ORDER BY id DESC LIMIT 150");
            $txSt->execute([$rid]);
            $transactions = $txSt->fetchAll(PDO::FETCH_ASSOC);

            $unusedCount = (int)$db->query("SELECT COUNT(*) FROM activation_cards WHERE reseller_id = $rid AND status = 'unused'")->fetchColumn();
            $usedCount   = (int)$db->query("SELECT COUNT(*) FROM activation_cards WHERE reseller_id = $rid AND status = 'used'")->fetchColumn();

            unset($reseller['password_hash'], $reseller['password_plain'], $reseller['session_token']);
            jsonOk([
                'reseller'     => $reseller,
                'stats'        => [
                    'points_balance'        => (int)$reseller['points'],
                    'total_points_received' => (int)$reseller['total_points_received'],
                    'total_points_spent'    => (int)$reseller['total_points_spent'],
                    'total_cards_generated' => (int)$reseller['total_cards_generated'],
                    'unused_cards'          => $unusedCount,
                    'used_cards'            => $usedCount,
                ],
                'packages'     => array_values(getResellerPackagesCatalog()),
                'cards'        => $cards,
                'transactions' => $transactions
            ]);
            break;

        case 'reseller_generate_cards':
            $reseller = requireReseller();
            $rid      = (int)$reseller['id'];
            $pkgKey   = trim((string)($_POST['package_type'] ?? $_POST['card_type'] ?? '1month'));
            $quantity = min(50, max(1, (int)($_POST['count'] ?? $_POST['quantity'] ?? 1)));
            $custNote = trim((string)($_POST['notes'] ?? $_POST['customer_note'] ?? ''));

            $catalog = getResellerPackagesCatalog();
            if (!isset($catalog[$pkgKey])) {
                jsonError('الباقة المختارة غير صالحة. الباقات المتاحة: شهر (1 نقطة)، 3 أشهر (3 نقاط)، 6 أشهر (6 نقاط)، سنة (12 نقطة).');
            }
            $pkg = $catalog[$pkgKey];
            $unitCost  = (int)$pkg['points'];
            $totalCost = $unitCost * $quantity;
            $days      = (int)$pkg['days'];

            $db = getDB();
            $db->beginTransaction();
            try {
                // قراءة الرصيد الفعلي داخل المعاملة الذرية لمنع السحب المزدوج
                $st = $db->prepare("SELECT points, display_name, username, is_active FROM resellers WHERE id = ? LIMIT 1");
                $st->execute([$rid]);
                $fresh = $st->fetch(PDO::FETCH_ASSOC);
                if (!$fresh || (int)$fresh['is_active'] !== 1) {
                    $db->rollBack();
                    jsonError('حساب الموزع غير متاح حالياً', 403);
                }
                $currentBalance = (int)$fresh['points'];
                if ($currentBalance < $totalCost) {
                    $db->rollBack();
                    jsonError("⛔ رصيد النقاط غير كافٍ! الرصيد الحالي: ({$currentBalance} نقطة)، والتكلفة المطلوبة: ({$totalCost} نقطة). يرجى التواصل مع الإدارة لشحن حسابك.", 400, 'INSUFFICIENT_POINTS');
                }

                $newBalance = $currentBalance - $totalCost;
                $batchId    = 'RES-' . strtoupper($fresh['username']) . '-' . date('YmdHis');
                $noteText   = $custNote !== '' ? "موزع: {$fresh['display_name']} | {$custNote}" : "موزع: {$fresh['display_name']} ({$pkg['short']})";

                $insCard = $db->prepare("
                    INSERT INTO activation_cards (code, card_type, duration_days, batch_id, status, notes, created_by, reseller_id, reseller_name, points_cost, created_at)
                    VALUES (?, ?, ?, ?, 'unused', ?, ?, ?, ?, ?, datetime('now'))
                ");
                $insTx = $db->prepare("
                    INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, package_type, duration_days, card_code, notes, created_at)
                    VALUES (?, 'generate_card', ?, ?, ?, ?, ?, ?, datetime('now'))
                ");

                $generated = [];
                $runningBalance = $currentBalance;
                for ($i = 0; $i < $quantity; $i++) {
                    $code = generateUniqueVoucherCode($db);
                    $insCard->execute([
                        $code,
                        $pkg['type'],
                        $days,
                        $batchId,
                        $noteText,
                        'reseller:' . $fresh['username'],
                        $rid,
                        $fresh['display_name'],
                        $unitCost
                    ]);
                    $runningBalance -= $unitCost;
                    $insTx->execute([
                        $rid,
                        -$unitCost,
                        $runningBalance,
                        $pkg['type'],
                        $days,
                        $code,
                        "استخراج قسيمة {$pkg['label']}" . ($custNote !== '' ? " — {$custNote}" : '')
                    ]);
                    $generated[] = $code;
                }

                $db->prepare("
                    UPDATE resellers
                    SET points = ?,
                        total_points_spent = total_points_spent + ?,
                        total_cards_generated = total_cards_generated + ?,
                        updated_at = datetime('now')
                    WHERE id = ?
                ")->execute([$newBalance, $totalCost, count($generated), $rid]);

                logActivity($db, '', 'reseller_generate_cards', [
                    'reseller_id'   => $rid,
                    'reseller_name' => $fresh['display_name'],
                    'package'       => $pkg['type'],
                    'quantity'      => count($generated),
                    'points_spent'  => $totalCost,
                    'balance_after' => $newBalance,
                    'cards'         => $generated
                ]);

                $db->commit();
                jsonOk([
                    'cards'         => $generated,
                    'count'         => count($generated),
                    'package_type'  => $pkg['type'],
                    'package_label' => $pkg['label'],
                    'duration_days' => $days,
                    'points_deducted' => $totalCost,
                    'new_balance'   => $newBalance,
                    'message'       => "✅ تم استخراج " . count($generated) . " قسيمة ({$pkg['label']}) بنجاح وخصم {$totalCost} نقطة من رصيدك!"
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'reseller_direct_activate':
            $reseller   = requireReseller();
            $rid        = (int)$reseller['id'];
            $pkgKey     = trim((string)($_POST['package_type'] ?? '1month'));
            $deviceId   = normalizeDeviceCodeApi((string)($_POST['device_id'] ?? ''));
            $custName   = trim((string)($_POST['customer_name'] ?? $_POST['notes'] ?? ''));

            if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $deviceId)) {
                jsonError('⚠️ صيغة رمز الشاشة غير صحيحة. يرجى كتابته بالصيغة DEV-XXXX-XXXX');
            }
            $catalog = getResellerPackagesCatalog();
            if (!isset($catalog[$pkgKey])) {
                jsonError('الباقة المختارة غير صالحة');
            }
            $pkg      = $catalog[$pkgKey];
            $unitCost = (int)$pkg['points'];
            $days     = (int)$pkg['days'];

            $db = getDB();
            $db->beginTransaction();
            try {
                $st = $db->prepare("SELECT points, display_name, username, is_active FROM resellers WHERE id = ? LIMIT 1");
                $st->execute([$rid]);
                $fresh = $st->fetch(PDO::FETCH_ASSOC);
                if (!$fresh || (int)$fresh['is_active'] !== 1) {
                    $db->rollBack();
                    jsonError('حساب الموزع غير متاح حالياً', 403);
                }
                $currentBalance = (int)$fresh['points'];
                if ($currentBalance < $unitCost) {
                    $db->rollBack();
                    jsonError("⛔ رصيد النقاط غير كافٍ! الرصيد الحالي: ({$currentBalance} نقطة)، والتكلفة المطلوبة: ({$unitCost} نقطة).", 400, 'INSUFFICIENT_POINTS');
                }

                $newBalance = $currentBalance - $unitCost;
                $code       = generateUniqueVoucherCode($db);
                $batchId    = 'DIRECT-' . strtoupper($fresh['username']) . '-' . date('YmdHis');
                $noteText   = "تفعيل مباشر من الموزع {$fresh['display_name']} ({$pkg['short']})" . ($custName !== '' ? " | {$custName}" : "");

                // 1) إنشاء البطاقة بحالة used مباشرة
                $db->prepare("
                    INSERT INTO activation_cards (code, card_type, duration_days, batch_id, status, used_at, used_by_device, notes, created_by, reseller_id, reseller_name, points_cost, created_at)
                    VALUES (?, ?, ?, ?, 'used', datetime('now'), ?, ?, ?, ?, ?, ?, datetime('now'))
                ")->execute([
                    $code, $pkg['type'], $days, $batchId, $deviceId, $noteText,
                    'reseller:' . $fresh['username'], $rid, $fresh['display_name'], $unitCost
                ]);
                $cardId = (int)$db->lastInsertId();

                // 2) تفعيل/تمديد الشاشة في جدول users
                $uStmt = $db->prepare("SELECT * FROM users WHERE UPPER(device_id) = ? LIMIT 1");
                $uStmt->execute([$deviceId]);
                $user = $uStmt->fetch(PDO::FETCH_ASSOC);

                $today = date('Y-m-d');
                $todayTs = strtotime($today . ' 00:00:00');
                $baseDate = $today;
                $isRenewal = false;
                if ($user && !empty($user['expire_date'])) {
                    $currentTs = strtotime($user['expire_date'] . ' 23:59:59');
                    if ($currentTs !== false && $currentTs > $todayTs) {
                        $baseDate = $user['expire_date'];
                        $isRenewal = true;
                    }
                }
                $newExpire = date('Y-m-d', strtotime($baseDate . ' +' . $days . ' days'));

                if ($user) {
                    $oldNotes = trim((string)($user['notes'] ?? ''));
                    $newNotes = $oldNotes === '' ? $noteText : ($oldNotes . ' | ' . $noteText);
                    $devNameFinal = $custName !== '' ? $custName : ($user['device_name'] ?: 'شاشة زبون');
                    $db->prepare("
                        UPDATE users
                        SET expire_date = ?, device_name = ?, is_active = 1, blocked_reason = NULL,
                            notes = ?, last_card_code = ?, last_activation_at = datetime('now'),
                            total_paid_days = COALESCE(total_paid_days, 0) + ?, trial_used = 1,
                            updated_at = datetime('now'), last_seen = datetime('now')
                        WHERE id = ?
                    ")->execute([$newExpire, $devNameFinal, $newNotes, $code, $days, $user['id']]);
                } else {
                    $devNameFinal = $custName !== '' ? $custName : 'شاشة زبون';
                    $db->prepare("
                        INSERT INTO users (device_id, device_name, expire_date, is_active, notes, trial_used, last_card_code, last_activation_at, total_paid_days, created_at, updated_at, registration_source, last_seen)
                        VALUES (?, ?, ?, 1, ?, 1, ?, datetime('now'), ?, datetime('now'), datetime('now'), 'reseller', datetime('now'))
                    ")->execute([$deviceId, $devNameFinal, $newExpire, $noteText, $code, $days]);
                }

                // 3) تسجيل في card_redemptions
                $db->prepare("
                    INSERT INTO card_redemptions (code, redeemed_by_device, redeemed_at, card_id, duration_days, new_expire, is_renewal, ip_address)
                    VALUES (?, ?, datetime('now'), ?, ?, ?, ?, ?)
                ")->execute([$code, $deviceId, $cardId, $days, $newExpire, $isRenewal ? 1 : 0, $_SERVER['REMOTE_ADDR'] ?? null]);

                // 4) خصم النقاط وتسجيل العملية في reseller_transactions
                $db->prepare("
                    UPDATE resellers
                    SET points = ?,
                        total_points_spent = total_points_spent + ?,
                        total_cards_generated = total_cards_generated + 1,
                        updated_at = datetime('now')
                    WHERE id = ?
                ")->execute([$newBalance, $unitCost, $rid]);

                $db->prepare("
                    INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, package_type, duration_days, card_code, target_device_id, notes, created_at)
                    VALUES (?, 'direct_activate', ?, ?, ?, ?, ?, ?, ?, datetime('now'))
                ")->execute([
                    $rid, -$unitCost, $newBalance, $pkg['type'], $days, $code, $deviceId,
                    "تفعيل مباشر للشاشة {$deviceId} ({$pkg['label']})" . ($custName !== '' ? " — {$custName}" : '')
                ]);

                logActivity($db, $deviceId, 'reseller_direct_activate', [
                    'reseller_id'   => $rid,
                    'reseller_name' => $fresh['display_name'],
                    'card_code'     => $code,
                    'duration_days' => $days,
                    'points_spent'  => $unitCost,
                    'new_expire'    => $newExpire
                ]);

                $db->commit();
                syncUserToSecondaryPortalDb($deviceId, $custName ?: 'شاشة زبون', $newExpire, 1, $noteText);

                jsonOk([
                    'device_id'       => $deviceId,
                    'card_code'       => $code,
                    'package_label'   => $pkg['label'],
                    'added_days'      => $days,
                    'new_expire'      => $newExpire,
                    'points_deducted' => $unitCost,
                    'new_balance'     => $newBalance,
                    'is_renewal'      => $isRenewal,
                    'message'         => "✅ تم تفعيل/تجديد الشاشة ({$deviceId}) لمدة {$pkg['label']} بنجاح! تنتهي الصلاحية في: {$newExpire}"
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'reseller_change_password':
            $reseller = requireReseller();
            $rid      = (int)$reseller['id'];
            $oldPass  = trim((string)($_POST['old_password'] ?? ''));
            $newPass  = trim((string)($_POST['new_password'] ?? ''));
            if (strlen($newPass) < 4) jsonError('كلمة المرور الجديدة يجب أن تكون 4 أحرف أو أرقام على الأقل');
            if (!password_verify($oldPass, (string)$reseller['password_hash'])) {
                jsonError('كلمة المرور الحالية غير صحيحة');
            }
            $hash = password_hash($newPass, PASSWORD_DEFAULT);
            getDB()->prepare("UPDATE resellers SET password_hash = ?, password_plain = ?, updated_at = datetime('now') WHERE id = ?")
                   ->execute([$hash, $newPass, $rid]);
            jsonOk(['message' => '✅ تم تغيير كلمة المرور بنجاح']);
            break;

        case 'reseller_list_sub_resellers':
            $reseller = requireReseller();
            $rid = (int)$reseller['id'];
            $db = getDB();
            $st = $db->prepare("
                SELECT r.*,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'unused') AS unused_cards_count,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'used') AS used_cards_count
                FROM resellers r
                WHERE r.parent_reseller_id = ?
                ORDER BY r.id DESC
            ");
            $st->execute([$rid]);
            jsonOk(['sub_resellers' => $st->fetchAll(PDO::FETCH_ASSOC)]);
            break;

        case 'reseller_add_sub_reseller':
            $reseller    = requireReseller();
            $rid         = (int)$reseller['id'];
            $username    = strtolower(trim((string)($_POST['username'] ?? '')));
            $password    = trim((string)($_POST['password'] ?? ''));
            $displayName = trim((string)($_POST['display_name'] ?? ''));
            $phone       = trim((string)($_POST['phone'] ?? ''));
            $initPoints  = max(0, (int)($_POST['points'] ?? 0));
            $notes       = trim((string)($_POST['notes'] ?? ''));

            if ($username === '' || !preg_match('/^[a-zA-Z0-9_.\-]{3,32}$/', $username)) {
                jsonError('اسم المستخدم يجب أن يتكون من 3 إلى 32 حرفاً أو رقماً باللغة الإنجليزية بدون مسافات');
            }
            if (strlen($password) < 4) {
                jsonError('كلمة المرور يجب أن تكون 4 أحرف أو أرقام على الأقل');
            }
            if ($displayName === '') $displayName = $username;

            $db = getDB();
            $chk = $db->prepare("SELECT id FROM resellers WHERE username = ? COLLATE NOCASE LIMIT 1");
            $chk->execute([$username]);
            if ($chk->fetchColumn()) {
                jsonError('اسم المستخدم مسجل مسبقاً، اختر اسم مستخدم آخر');
            }

            $db->beginTransaction();
            try {
                $pSt = $db->prepare("SELECT points, display_name, username FROM resellers WHERE id = ? LIMIT 1");
                $pSt->execute([$rid]);
                $parentFresh = $pSt->fetch(PDO::FETCH_ASSOC);
                $parentPts = (int)($parentFresh['points'] ?? 0);
                if ($initPoints > $parentPts) {
                    $db->rollBack();
                    jsonError("⛔ رصيد نقاطك الحالي ({$parentPts} نقطة) غير كافٍ لتحويل ({$initPoints} نقطة) للموزع الفرعي");
                }

                $newParentBalance = $parentPts - $initPoints;
                if ($initPoints > 0) {
                    $db->prepare("UPDATE resellers SET points = ?, total_points_spent = total_points_spent + ?, updated_at = datetime('now') WHERE id = ?")
                       ->execute([$newParentBalance, $initPoints, $rid]);
                }

                $hash = password_hash($password, PASSWORD_DEFAULT);
                $db->prepare("
                    INSERT INTO resellers (username, password_hash, password_plain, display_name, phone, points, total_points_received, total_points_spent, total_cards_generated, is_active, notes, parent_reseller_id, parent_reseller_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 1, ?, ?, ?, datetime('now'), datetime('now'))
                ")->execute([$username, $hash, $password, $displayName, $phone, $initPoints, $initPoints, $notes, $rid, $parentFresh['display_name']]);
                $subId = (int)$db->lastInsertId();

                if ($initPoints > 0) {
                    $db->prepare("INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at) VALUES (?, 'sub_reseller_transfer', ?, ?, ?, datetime('now'))")
                       ->execute([$rid, -$initPoints, $newParentBalance, "تحويل رصيد ابتدائي لفتح حساب الموزع الفرعي: {$displayName} (@{$username})"]);
                    $db->prepare("INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at) VALUES (?, 'credit_add', ?, ?, ?, datetime('now'))")
                       ->execute([$subId, $initPoints, $initPoints, "رصيد ابتدائي محول من الموزع: {$parentFresh['display_name']}"]);
                }

                $db->commit();
                jsonOk([
                    'sub_reseller_id' => $subId,
                    'new_balance'     => $newParentBalance,
                    'message'         => "✅ تم فتح حساب الموزع الفرعي ({$displayName}) بنجاح" . ($initPoints > 0 ? " وتحويل {$initPoints} نقطة من رصيدك!" : "!")
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'reseller_transfer_sub_points':
            $reseller = requireReseller();
            $rid      = (int)$reseller['id'];
            $subId    = (int)($_POST['sub_reseller_id'] ?? 0);
            $amount   = (int)($_POST['points'] ?? 0);
            $op       = trim((string)($_POST['operation'] ?? 'add'));
            $notes    = trim((string)($_POST['notes'] ?? ''));

            if ($subId <= 0 || $amount <= 0) {
                jsonError('يرجى تحديد الموزع الفرعي وعدد النقاط بشكل صحيح');
            }

            $db = getDB();
            $db->beginTransaction();
            try {
                $pSt = $db->prepare("SELECT * FROM resellers WHERE id = ? LIMIT 1");
                $pSt->execute([$rid]);
                $parentRow = $pSt->fetch(PDO::FETCH_ASSOC);

                $sSt = $db->prepare("SELECT * FROM resellers WHERE id = ? AND parent_reseller_id = ? LIMIT 1");
                $sSt->execute([$subId, $rid]);
                $subRow = $sSt->fetch(PDO::FETCH_ASSOC);
                if (!$subRow) {
                    $db->rollBack();
                    jsonError('الموزع الفرعي غير موجود أو لا يتبع لحسابك', 403);
                }

                $parentPts = (int)$parentRow['points'];
                $subPts    = (int)$subRow['points'];

                if ($op === 'deduct') {
                    if ($subPts < $amount) {
                        $db->rollBack();
                        jsonError("رصيد الموزع الفرعي الحالي ({$subPts} نقطة) أقل من المراد استرجاعه ({$amount} نقطة)");
                    }
                    $newSubPts    = $subPts - $amount;
                    $newParentPts = $parentPts + $amount;
                    $db->prepare("UPDATE resellers SET points = ?, updated_at = datetime('now') WHERE id = ?")->execute([$newSubPts, $subId]);
                    $db->prepare("UPDATE resellers SET points = ?, updated_at = datetime('now') WHERE id = ?")->execute([$newParentPts, $rid]);

                    $db->prepare("INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at) VALUES (?, 'credit_deduct', ?, ?, ?, datetime('now'))")
                       ->execute([$subId, -$amount, $newSubPts, $notes ?: "سحب نقاط بواسطة الموزع الرئيسي ({$parentRow['display_name']})"]);
                    $db->prepare("INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at) VALUES (?, 'sub_reseller_reclaim', ?, ?, ?, datetime('now'))")
                       ->execute([$rid, $amount, $newParentPts, $notes ?: "استرجاع نقاط من الموزع الفرعي ({$subRow['display_name']})"]);
                } else {
                    if ($parentPts < $amount) {
                        $db->rollBack();
                        jsonError("⛔ رصيدك الحالي ({$parentPts} نقطة) غير كافٍ لتحويل ({$amount} نقطة) للموزع الفرعي");
                    }
                    $newParentPts = $parentPts - $amount;
                    $newSubPts    = $subPts + $amount;
                    $db->prepare("UPDATE resellers SET points = ?, total_points_spent = total_points_spent + ?, updated_at = datetime('now') WHERE id = ?")->execute([$newParentPts, $amount, $rid]);
                    $db->prepare("UPDATE resellers SET points = ?, total_points_received = total_points_received + ?, updated_at = datetime('now') WHERE id = ?")->execute([$newSubPts, $amount, $subId]);

                    $db->prepare("INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at) VALUES (?, 'sub_reseller_transfer', ?, ?, ?, datetime('now'))")
                       ->execute([$rid, -$amount, $newParentPts, $notes ?: "تحويل نقاط إلى الموزع الفرعي ({$subRow['display_name']})"]);
                    $db->prepare("INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at) VALUES (?, 'credit_add', ?, ?, ?, datetime('now'))")
                       ->execute([$subId, $amount, $newSubPts, $notes ?: "شحن نقاط من الموزع الرئيسي ({$parentRow['display_name']})"]);
                }

                $db->commit();
                jsonOk([
                    'new_parent_balance' => $newParentPts,
                    'new_sub_balance'    => $newSubPts,
                    'message'            => $op === 'deduct'
                        ? "✅ تم استرجاع {$amount} نقطة من الموزع الفرعي ({$subRow['display_name']}) إلى رصيدك بنجاح!"
                        : "✅ تم تحويل {$amount} نقطة إلى الموزع الفرعي ({$subRow['display_name']}) بنجاح!"
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'reseller_update_sub_reseller':
            $reseller    = requireReseller();
            $rid         = (int)$reseller['id'];
            $subId       = (int)($_POST['id'] ?? 0);
            $displayName = trim((string)($_POST['display_name'] ?? ''));
            $phone       = trim((string)($_POST['phone'] ?? ''));
            $password    = trim((string)($_POST['password'] ?? ''));
            $isActive    = (int)($_POST['is_active'] ?? 1);
            $notes       = trim((string)($_POST['notes'] ?? ''));

            $db = getDB();
            $sSt = $db->prepare("SELECT * FROM resellers WHERE id = ? AND parent_reseller_id = ? LIMIT 1");
            $sSt->execute([$subId, $rid]);
            $subRow = $sSt->fetch(PDO::FETCH_ASSOC);
            if (!$subRow) jsonError('الموزع الفرعي غير موجود أو لا يتبع لحسابك', 403);

            if ($displayName === '') $displayName = $subRow['display_name'];
            if ($password !== '') {
                if (strlen($password) < 4) jsonError('كلمة المرور قصيرة جداً');
                $hash = password_hash($password, PASSWORD_DEFAULT);
                $db->prepare("UPDATE resellers SET display_name=?, phone=?, password_hash=?, password_plain=?, is_active=?, notes=?, updated_at=datetime('now') WHERE id=? AND parent_reseller_id=?")
                   ->execute([$displayName, $phone, $hash, $password, $isActive, $notes, $subId, $rid]);
            } else {
                $db->prepare("UPDATE resellers SET display_name=?, phone=?, is_active=?, notes=?, updated_at=datetime('now') WHERE id=? AND parent_reseller_id=?")
                   ->execute([$displayName, $phone, $isActive, $notes, $subId, $rid]);
            }
            if ($isActive === 0) {
                $db->prepare("DELETE FROM reseller_sessions WHERE reseller_id = ?")->execute([$subId]);
            }
            jsonOk(['message' => '✅ تم تحديث بيانات الموزع الفرعي بنجاح']);
            break;

        case 'reseller_delete_sub_reseller':
            $reseller = requireReseller();
            $rid      = (int)$reseller['id'];
            $subId    = (int)($_POST['id'] ?? 0);
            $db       = getDB();
            $sSt = $db->prepare("SELECT * FROM resellers WHERE id = ? AND parent_reseller_id = ? LIMIT 1");
            $sSt->execute([$subId, $rid]);
            $subRow = $sSt->fetch(PDO::FETCH_ASSOC);
            if (!$subRow) jsonError('الموزع الفرعي غير موجود أو لا يتبع لحسابك', 403);

            $db->beginTransaction();
            try {
                // إذا كان لدى الموزع الفرعي نقاط متبقية غير مستخدمة، تعود تلقائياً لرصيد الموزع الرئيسي
                $remPts = max(0, (int)($subRow['points'] ?? 0));
                if ($remPts > 0) {
                    $db->prepare("UPDATE resellers SET points = points + ?, updated_at = datetime('now') WHERE id = ?")->execute([$remPts, $rid]);
                    $newParBal = (int)$db->query("SELECT points FROM resellers WHERE id = $rid")->fetchColumn();
                    $db->prepare("INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at) VALUES (?, 'sub_reseller_reclaim', ?, ?, ?, datetime('now'))")
                       ->execute([$rid, $remPts, $newParBal, "استرداد النقاط المتبقية عند حذف الموزع الفرعي ({$subRow['display_name']})"]);
                }
                $db->prepare("DELETE FROM reseller_sessions WHERE reseller_id = ?")->execute([$subId]);
                $db->prepare("DELETE FROM resellers WHERE id = ? AND parent_reseller_id = ?")->execute([$subId, $rid]);
                $db->commit();
                jsonOk(['message' => '🗑️ تم حذف الموزع الفرعي بنجاح' . ($remPts > 0 ? " واسترجاع {$remPts} نقطة متبقية لرصيدك" : "")]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                throw $e;
            }
            break;

        case 'reseller_list_my_domains':
            $reseller = requireReseller();
            $rid = (int)$reseller['id'];
            $db = getDB();
            $ownedDevices = getResellerOwnedDeviceIds($db, $rid);
            jsonOk([
                'owned_devices_count' => count($ownedDevices),
                'domains'             => collectResellerActiveDomainsSummary($db, $ownedDevices)
            ]);
            break;

        case 'reseller_bulk_replace_domain':
            $reseller  = requireReseller();
            $rid       = (int)$reseller['id'];
            $oldDomain = trim((string)($_POST['old_domain'] ?? ''));
            $newDomain = trim((string)($_POST['new_domain'] ?? ''));
            if ($oldDomain === '' || $newDomain === '') {
                jsonError('يرجى إدخال الدومين القديم المستهدف والدومين الجديد المقترح');
            }
            if (strcasecmp(rtrim($oldDomain, '/'), rtrim($newDomain, '/')) === 0) {
                jsonError('الدومين الجديد مطابق للدومين القديم');
            }
            $db = getDB();
            $ownedDevices = getResellerOwnedDeviceIds($db, $rid);
            if (empty($ownedDevices)) {
                jsonError('⚠️ لا توجد شاشات مفعّلة تابعة لحسابك حتى الآن لتغيير الدومين عليها.');
            }
            $resPrimary = bulkReplaceDomainForOwnedDevicesInPdo($db, $ownedDevices, $oldDomain, $newDomain);
            $resSec = ['playlists' => 0, 'devices' => [], 'favorites' => 0];
            $secDb = getSecondaryPortalPdo();
            if ($secDb !== null) {
                try {
                    $resSec = bulkReplaceDomainForOwnedDevicesInPdo($secDb, $ownedDevices, $oldDomain, $newDomain);
                } catch (Throwable $e) {}
            }
            // تحديث ملف data.txt وتسجيل تحويل الدومين ليتم تحديث قاعدة البيانات المحلية للتطبيق عند الزبائن فور إعادة إقلاع التطبيق
            $dataTxtUpdated = updateDataTxtDomainAndName($oldDomain, $newDomain);
            recordDomainMigration($db, $oldDomain, $newDomain, '', 'reseller', $rid);

            $totalPlaylists = max($resPrimary['playlists'], $resSec['playlists']);
            $allUpdatedDevs = array_values(array_unique(array_merge($resPrimary['devices'], $resSec['devices'])));
            $totalDevs      = count($allUpdatedDevs);
            $totalFavs      = max($resPrimary['favorites'], $resSec['favorites']);

            if ($totalPlaylists === 0 && $totalFavs === 0 && $dataTxtUpdated === 0) {
                jsonError("⚠️ لم يتم العثور على أي قائمة تستخدم الدومين ({$oldDomain}) ضمن الشاشات التابعة لك (" . count($ownedDevices) . " شاشة).");
            }

            logActivity($db, '', 'reseller_bulk_replace_domain', [
                'reseller_id'       => $rid,
                'reseller_name'     => $reseller['display_name'],
                'old_domain'        => $oldDomain,
                'new_domain'        => $newDomain,
                'updated_playlists' => $totalPlaylists,
                'updated_devices'   => $totalDevs
            ]);

            jsonOk([
                'old_domain'        => $oldDomain,
                'new_domain'        => $newDomain,
                'updated_playlists' => $totalPlaylists,
                'updated_devices'   => $totalDevs,
                'updated_favorites' => $totalFavs,
                'domains'           => collectResellerActiveDomainsSummary($db, $ownedDevices),
                'message'           => "✅ تم استبدال الدومين بنجاح لجميع شاشاتك! تم تحديث ({$totalPlaylists}) قائمة على ({$totalDevs}) شاشة تابعة لك."
            ]);
            break;

        default:
            jsonError('إجراء غير معروف: ' . htmlspecialchars($action), 404);
    }
} catch (Throwable $e) {
    error_log('API Error [' . $action . ']: ' . $e->getMessage());
    jsonError('حدث خطأ داخلي. يرجى مراجعة سجل الخادم.', 500, 'INTERNAL_ERROR');
}
