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

    // 🆕 v3.2: ترحيل القسائم المستعملة سابقاً إلى سجل الاستهلاك
    try {
        if (getSetting('migration_v3_2_redemptions') !== '1') {
            $used = $dbCheck->query("SELECT id, code, used_by_device, used_at, duration_days, total_paid_days FROM activation_cards WHERE status = 'used' AND code IS NOT NULL AND code != ''")->fetchAll(PDO::FETCH_ASSOC);
            $ins = $dbCheck->prepare("INSERT OR IGNORE INTO card_redemptions (code, redeemed_by_device, redeemed_at, card_id, duration_days) VALUES (?, ?, ?, ?, ?)");
            foreach ($used as $u) {
                $ins->execute([
                    strtoupper($u['code']),
                    $u['used_by_device'] ?: 'migrated',
                    $u['used_at'] ?: date('Y-m-d H:i:s'),
                    $u['id'],
                    (int)($u['duration_days'] ?? 0)
                ]);
            }
            setSetting('migration_v3_2_redemptions', '1');
        }
    } catch (Throwable $e) {}

    try { $dbCheck->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_cards_code_unique ON activation_cards(code) WHERE status != 'invalid_duplicate'"); } catch (Throwable $e) {}
} catch (Throwable $e) {}

$action = trim((string)(($_SERVER['REQUEST_METHOD'] === 'POST'
    ? ($_POST['action'] ?? '')
    : ($_GET['action'] ?? '')) ?: ($_REQUEST['action'] ?? '')));
// PRODUCTION: mutating actions must be POST
$_mutatingApi = ['activate_card','save_xtream','delete_xtream','admin_burn_card','admin_create_cards','admin_delete_device','transfer_device'];
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
    $db->prepare("UPDATE users SET last_seen = datetime('now') WHERE id = ?")->execute([$user['id']]);
    $days = (int)floor(($expireTimestamp - time()) / 86400);
    $isTrial = (bool)$user['trial_used'] && empty($user['last_card_code']);
    return ['active' => true, 'reason' => $isTrial ? 'trial' : 'ok', 'expire' => $user['expire_date'], 'days_left' => $days, 'device_name' => $user['device_name'], 'is_trial' => $isTrial, 'trial_used' => (bool)$user['trial_used'], 'bad_attempts' => (int)$user['bad_attempts']];
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
    if ($stored === '' || !str_starts_with($stored, 'ENCv9:')) return $stored;
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

function getDeviceXtreamLists(PDO $db, string $device_id, bool $maskPassword = false): array {
    $device_id = strtoupper(trim($device_id));
    if ($device_id === '') return [];
    try {
        $stmt = $db->prepare("SELECT * FROM customer_xtream_lists WHERE UPPER(device_id) = ? ORDER BY order_index ASC, id ASC");
        $stmt->execute([$device_id]);
        $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
        $lists = [];
        foreach ($rows as $r) {
            $decPass = decryptXtreamSecret((string)$r['xtream_pass']);
            $lists[] = [
                'id'          => (string)$r['playlist_id'],
                'playlist_id' => (string)$r['playlist_id'],
                'device_id'   => (string)$r['device_id'],
                'server_name' => (string)$r['server_name'],
                'server_url'  => (string)$r['server_url'],
                'username'    => (string)$r['xtream_user'],
                'password'    => $maskPassword ? '••••••••' : $decPass,
                'm3u_url'     => (string)($r['m3u_url'] ?? ''),
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
    $card_code = strtoupper(trim($card_code));
    if (!preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $card_code)) {
        return ['ok' => false, 'error' => 'صيغة رمز البطاقة غير صحيحة', 'code' => 'CODE_INVALID', 'http' => 400];
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
    $device_id = strtoupper(trim($device_id));
    $card_code = strtoupper(trim($card_code));
    $device_name = trim($device_name);

    if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $device_id)) {
        return ['ok' => false, 'error' => 'صيغة رمز الجهاز غير صحيحة', 'code' => 'DEVICE_INVALID', 'http' => 400];
    }
    if ($card_code === '') {
        return ['ok' => false, 'error' => 'أدخل رمز البطاقة', 'code' => 'CODE_EMPTY', 'http' => 400];
    }
    if (!preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $card_code)) {
        return ['ok' => false, 'error' => 'صيغة رمز البطاقة غير صحيحة', 'code' => 'CODE_INVALID', 'http' => 400];
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
                        $db->prepare("UPDATE users SET expire_date = ?, is_active = 1, blocked_reason = NULL, notes = ?, last_card_code = ?, last_activation_at = datetime('now'), total_paid_days = COALESCE(total_paid_days, 0) + ?, trial_used = 1, updated_at = datetime('now'), last_seen = datetime('now') WHERE id = ?")
                           ->execute([$newExpire, $newNotes, $card_code, $duration, $user['id']]);
                    } else {
                        $safeName = mb_substr($device_name !== '' ? $device_name : 'جهاز جديد', 0, MAX_DEVICE_NAME_LEN);
                        $db->prepare("INSERT INTO users (device_id, device_name, expire_date, is_active, notes, trial_used, last_card_code, last_activation_at, total_paid_days, created_at, updated_at, registration_source, last_seen) VALUES (?, ?, ?, 1, ?, 1, ?, datetime('now'), ?, datetime('now'), datetime('now'), 'card', datetime('now'))")
                           ->execute([$device_id, $safeName, $newExpire, "💳 تفعيل ببطاقة {$card_code} (+{$duration} يوم)", $card_code, $duration]);
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
                        'is_renewal' => $isRenewal
                    ]);
                    $db->commit();
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
            $r = verifyCard($_REQUEST['card_code'] ?? '');
            if (!$r['ok']) jsonError($r['error'], $r['http'] ?? 400, $r['code'] ?? '');
            unset($r['ok']);
            jsonOk($r);
            break;

        // تفعيل القسيمة (يحرقها نهائياً)
        case 'activate_card':
            if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') { jsonError('Method Not Allowed', 405); }

            $result = activateCard($_REQUEST['device_id'] ?? '', $_REQUEST['card_code'] ?? '', $_REQUEST['device_name'] ?? '');
            if (!$result['ok']) jsonError($result['error'], $result['http'] ?? 400, $result['code'] ?? '');
            unset($result['ok']);
            $result['subscription'] = checkUserSubscription($_REQUEST['device_id'], $_REQUEST['device_name'] ?? '');
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
            $raw = $db->query("SELECT c.id, c.number, c.name, c.original_url AS encrypted_url, c.logo_url, c.category, c.sort_order FROM channels c LEFT JOIN categories cat ON c.category = cat.name WHERE c.is_active = 1 ORDER BY COALESCE(cat.sort_order, 9999) ASC, c.sort_order ASC, c.number ASC")->fetchAll();
            $channels = []; $counter = 1;
            foreach ($raw as $ch) {
                if (isAdultOr18PlusContent((string)($ch['name'] ?? ''), (string)($ch['category'] ?? ''))) continue;
                $ch['number'] = $counter++;
                $channels[] = $ch;
            }
            $rawCategories = $db->query("SELECT name, sort_order FROM categories ORDER BY sort_order ASC, name ASC")->fetchAll();
            $categories = array_values(array_filter($rawCategories, fn($catRow) => !isAdultOr18PlusContent('', (string)($catRow['name'] ?? ''))));
            $xtreamLists = getDeviceXtreamLists($db, $reqDeviceId, false);
            $catalogHash = sha1(json_encode([$channels, $categories], JSON_UNESCAPED_UNICODE));
            $clientHash  = trim((string)($_REQUEST['client_hash'] ?? ''));
            if ($clientHash !== '' && hash_equals($catalogHash, $clientHash)) {
                jsonOk([
                    'channels'     => [],
                    'categories'   => $categories,
                    'xtream_lists' => $xtreamLists,
                    'subscription' => $sub,
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
            $servers = [];
            $dataFile = __DIR__ . '/data.txt';
            if (is_file($dataFile)) {
                $lines = @file($dataFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [];
                foreach ($lines as $line) {
                    $line = trim($line);
                    if ($line === '' || $line[0] === '#') continue;
                    if (strpos($line, '|') !== false) {
                        [$nm, $u] = array_map('trim', explode('|', $line, 2));
                    } else {
                        $u = $line;
                        $nm = 'VIP Server';
                    }
                    $u = rtrim($u, '/');
                    if ($u !== '' && !preg_match('#^https?://#i', $u)) $u = 'http://' . $u;
                    if ($u !== '') $servers[$u] = ['name' => $nm ?: 'VIP Server', 'url' => $u];
                }
            }
            jsonOk(['servers' => array_values($servers)]);
            break;

        case 'get_xtream_lists':
            $reqDeviceId = strtoupper(trim($_REQUEST['device_id'] ?? ''));
            $reqDeviceName = trim($_REQUEST['device_name'] ?? '');
            $sub = checkUserSubscription($reqDeviceId, $reqDeviceName);
            if (!$sub['active']) {
                jsonError($sub['message'] ?? 'انتهت صلاحية المشغل.', 403, 'SUBSCRIPTION_EXPIRED');
            }
            $db = getDB();
            jsonOk([
                'device_id'    => $reqDeviceId,
                'subscription' => $sub,
                'xtream_lists' => getDeviceXtreamLists($db, $reqDeviceId, false)
            ]);
            break;

        case 'save_favorites':
            $reqDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
            if ($reqDeviceId === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                jsonError('device_id غير صالح', 400, 'INVALID_DEVICE');
            }
            $rawJson = (string)($_POST['favorites_json'] ?? $_REQUEST['favorites_json'] ?? '[]');
            $decoded = json_decode($rawJson, true);
            if (!is_array($decoded)) {
                jsonError('صيغة المفضلة غير صالحة', 400, 'INVALID_FAVORITES');
            }
            // Cap & sanitize
            $clean = [];
            $seen = [];
            foreach ($decoded as $item) {
                if (!is_array($item)) continue;
                $name = trim((string)($item['name'] ?? ''));
                $url  = trim((string)($item['url'] ?? ''));
                if ($name === '' || $url === '') continue;
                $ctype = trim((string)($item['contentType'] ?? 'LIVE_TV'));
                $pid = trim((string)($item['playlistId'] ?? 'PORTAL_MAIN'));
                $sid = (int)($item['seriesId'] ?? 0);
                $key = ($ctype === 'SERIES' && $sid > 0) ? ("S|$pid|$sid") : ($url . '|' . $name . '|' . $ctype);
                if (isset($seen[$key])) continue;
                $seen[$key] = true;
                $clean[] = $item;
                if (count($clean) >= 5000) break;
            }
            $storeJson = json_encode($clean, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            $db = getDB();
            $db->prepare("INSERT INTO device_favorites (device_id, favorites_json, items_count, updated_at) VALUES (?, ?, ?, datetime('now'))
                ON CONFLICT(device_id) DO UPDATE SET favorites_json = excluded.favorites_json, items_count = excluded.items_count, updated_at = datetime('now')")
               ->execute([$reqDeviceId, $storeJson, count($clean)]);
            logActivity($db, $reqDeviceId, 'favorites_backup', ['count' => count($clean)]);
            jsonOk(['message' => 'تم حفظ نسخة المفضلة', 'count' => count($clean), 'device_id' => $reqDeviceId]);
            break;

        case 'get_favorites':
            $reqDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
            if ($reqDeviceId === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $reqDeviceId)) {
                jsonError('device_id غير صالح', 400, 'INVALID_DEVICE');
            }
            $db = getDB();
            $st = $db->prepare("SELECT favorites_json, items_count, updated_at FROM device_favorites WHERE UPPER(device_id) = ? LIMIT 1");
            $st->execute([$reqDeviceId]);
            $row = $st->fetch(PDO::FETCH_ASSOC);
            if (!$row) {
                jsonOk(['found' => false, 'favorites' => [], 'count' => 0, 'device_id' => $reqDeviceId]);
            }
            $arr = json_decode((string)$row['favorites_json'], true);
            if (!is_array($arr)) $arr = [];
            jsonOk([
                'found' => true,
                'favorites' => $arr,
                'count' => (int)($row['items_count'] ?? count($arr)),
                'updated_at' => (string)($row['updated_at'] ?? ''),
                'device_id' => $reqDeviceId
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
            jsonOk(['users' => getDB()->query("SELECT * FROM users ORDER BY id DESC")->fetchAll()]);
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
                       r.redeemed_at, r.redeemed_by_device AS redemption_device
                FROM activation_cards c
                LEFT JOIN card_redemptions r ON r.code = c.code COLLATE NOCASE
                ORDER BY c.id DESC LIMIT 200
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

        default:
            jsonError('إجراء غير معروف: ' . htmlspecialchars($action), 404);
    }
} catch (Throwable $e) {
    error_log('API Error [' . $action . ']: ' . $e->getMessage());
    jsonError('حدث خطأ داخلي. يرجى مراجعة سجل الخادم.', 500, 'INTERNAL_ERROR');
}
