<?php
/**
 * =============================================================================
 * LIVE PRO v26.0 — النجاح الاحترافي + الاحتياطي الذكي (Smart Bypass)
 *                 + إخفاء كلي للهوست المستعاد من data.txt (100% Hidden Host)
 *                 + عزل وحماية مطلقة للقوائم عند تفعيل رقم PIN (Zero-Leak PIN Lock)
 * =============================================================================
 */
declare(strict_types=1);

@ini_set('display_errors', '0');
@ini_set('display_startup_errors', '0');
@ini_set('memory_limit', '1024M');
@ini_set('max_execution_time', '600');
@set_time_limit(600);
error_reporting(E_ALL & ~E_NOTICE & ~E_DEPRECATED);

session_start();

header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');

if (!function_exists('str_starts_with')) {
    function str_starts_with($h, $n) { return strpos((string)$h, (string)$n) === 0; }
}
if (!defined('SUPPORT_NAME'))  define('SUPPORT_NAME',  'علي سات');
if (!defined('SUPPORT_PHONE')) define('SUPPORT_PHONE', '00213697372480');

if (empty($_SESSION['livepro_csrf'])) {
    $_SESSION['livepro_csrf'] = bin2hex(random_bytes(24));
}
$CSRF_TOKEN = $_SESSION['livepro_csrf'];

// حماية ملف data.txt وملفات قواعد البيانات من الوصول المباشر عبر المتصفح
$htaccessPath = __DIR__ . '/.htaccess';
$htaccessRule = "<FilesMatch \"(^data\\.txt|\\.(sqlite|db|json|log|bak))$\">\n  Order allow,deny\n  Deny from all\n</FilesMatch>\n";
if (!is_file($htaccessPath) || strpos((string)@file_get_contents($htaccessPath), 'data\\.txt') === false) {
    @file_put_contents($htaccessPath, $htaccessRule);
}

/* =============================================================================
   1. الاتصال بقاعدة البيانات الموحدة وإنشاء الجداول
   ============================================================================= */
function getPortalDB(): ?PDO {
    foreach (['db.php', 'config.php', '../db.php'] as $f) {
        if (is_file(__DIR__ . '/' . $f)) {
            @include_once __DIR__ . '/' . $f;
        }
    }
    if (function_exists('getDB')) {
        try {
            $p = @getDB();
            if ($p instanceof PDO) {
                ensurePortalTables($p);
                return $p;
            }
        } catch (Throwable $e) {}
    }
    foreach (['pdo', 'db', 'conn', 'connection'] as $v) {
        if (isset($GLOBALS[$v]) && $GLOBALS[$v] instanceof PDO) {
            ensurePortalTables($GLOBALS[$v]);
            return $GLOBALS[$v];
        }
    }
    try {
        $p = new PDO('sqlite:' . __DIR__ . '/livepro_portal.sqlite', null, null, [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
        ]);
        ensurePortalTables($p);
        return $p;
    } catch (Throwable $e) {
        return null;
    }
}

function ensurePortalTables(PDO $p): void {
    foreach (["PRAGMA journal_mode=WAL", "PRAGMA synchronous=NORMAL", "PRAGMA busy_timeout=15000"] as $pragma) {
        try { $p->exec($pragma); } catch (Throwable $e) {}
    }
    $tables = [
        "CREATE TABLE IF NOT EXISTS users (
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
        )",
        "CREATE TABLE IF NOT EXISTS customer_xtream_lists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            playlist_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            server_name TEXT NOT NULL,
            server_url TEXT NOT NULL,
            xtream_user TEXT NOT NULL,
            xtream_pass TEXT NOT NULL,
            m3u_url TEXT DEFAULT '',
            exp_date TEXT DEFAULT '',
            server_type TEXT DEFAULT 'Xtream API',
            is_auto_host INTEGER DEFAULT 1,
            enabled INTEGER DEFAULT 1,
            order_index INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )",
        "CREATE TABLE IF NOT EXISTS portal_security_pins (
            device_id TEXT PRIMARY KEY,
            pin_hash TEXT NOT NULL,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )",
        "CREATE TABLE IF NOT EXISTS portal_rate_limits (
            ip_address TEXT NOT NULL,
            attempted_at INTEGER NOT NULL
        )",
        "CREATE TABLE IF NOT EXISTS device_favorites (
            device_id TEXT PRIMARY KEY NOT NULL,
            favorites_json TEXT NOT NULL DEFAULT '[]',
            items_count INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )"
    ];
    foreach ($tables as $sql) {
        try { $p->exec($sql); } catch (Throwable $e) {}
    }
    try {
        $cols = array_column($p->query("PRAGMA table_info(customer_xtream_lists)")->fetchAll(PDO::FETCH_ASSOC), 'name');
        if (!in_array('exp_date', $cols, true))     $p->exec("ALTER TABLE customer_xtream_lists ADD COLUMN exp_date TEXT DEFAULT ''");
        if (!in_array('server_type', $cols, true))  $p->exec("ALTER TABLE customer_xtream_lists ADD COLUMN server_type TEXT DEFAULT 'Xtream API'");
        if (!in_array('is_auto_host', $cols, true)) $p->exec("ALTER TABLE customer_xtream_lists ADD COLUMN is_auto_host INTEGER DEFAULT 1");
        if (!in_array('m3u_url', $cols, true))      $p->exec("ALTER TABLE customer_xtream_lists ADD COLUMN m3u_url TEXT DEFAULT ''");
    } catch (Throwable $e) {}
}

/* =============================================================================
   2. تنسيق رمز الشاشة، التشفير المتوافق (ENCv9 / ENC)، وحماية محاولات الدخول
   ============================================================================= */
function normalizeDevice(string $raw): string {
    $c = strtoupper(trim($raw));
    if ($c === '') return '';
    if (preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $c)) return 'DEV-' . $c;
    $a = preg_replace('/[^A-Z0-9]/', '', $c);
    if (strlen($a) === 11 && strpos($a, 'DEV') === 0) return 'DEV-' . substr($a, 3, 4) . '-' . substr($a, 7, 4);
    if (strlen($a) === 8) return 'DEV-' . substr($a, 0, 4) . '-' . substr($a, 4, 4);
    return $c;
}

function encryptSecret(string $plain): string {
    if ($plain === '' || str_starts_with($plain, 'ENCv9:') || str_starts_with($plain, 'ENC:')) return $plain;
    if (function_exists('encryptUrl')) {
        try { return 'ENCv9:' . encryptUrl($plain); } catch (Throwable $e) {}
    }
    $key = hash('sha256', defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026', true);
    $iv = random_bytes(16);
    $cipher = openssl_encrypt($plain, 'AES-256-CBC', $key, OPENSSL_RAW_DATA, $iv);
    return $cipher !== false ? ('ENCv9:' . base64_encode($iv . $cipher)) : $plain;
}

function decryptSecret(string $stored): string {
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
        if ($raw === false || strlen($raw) <= 16) return $stored;
        $key = hash('sha256', defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026', true);
        $plain = openssl_decrypt(substr($raw, 16), 'AES-256-CBC', $key, OPENSSL_RAW_DATA, substr($raw, 0, 16));
        return $plain !== false ? $plain : $stored;
    }
    if (str_starts_with($stored, 'ENC:')) {
        $raw = base64_decode(substr($stored, 4), true);
        if ($raw === false || strlen($raw) <= 16) return $stored;
        foreach ([defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : '', 'LIVEPRO_V25_SECRET', 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026'] as $kStr) {
            if ($kStr === '') continue;
            $key = hash('sha256', $kStr, true);
            $plain = openssl_decrypt(substr($raw, 16), 'AES-256-CBC', $key, OPENSSL_RAW_DATA, substr($raw, 0, 16));
            if ($plain !== false) return $plain;
        }
        return $stored;
    }
    return $stored;
}

function checkRateLimit(?PDO $p): bool {
    if ($p === null) return true;
    try {
        $p->prepare("DELETE FROM portal_rate_limits WHERE attempted_at < ?")->execute([time() - 900]);
        $st = $p->prepare("SELECT COUNT(*) FROM portal_rate_limits WHERE ip_address = ?");
        $st->execute([$_SERVER['REMOTE_ADDR'] ?? '0.0.0.0']);
        return ((int)$st->fetchColumn()) < 40;
    } catch (Throwable $e) {
        return true;
    }
}

function recordFailed(?PDO $p): void {
    if ($p !== null) {
        try {
            $p->prepare("INSERT INTO portal_rate_limits (ip_address, attempted_at) VALUES (?, ?)")
              ->execute([$_SERVER['REMOTE_ADDR'] ?? '0.0.0.0', time()]);
        } catch (Throwable $e) {}
    }
}

/* =============================================================================
   3. تطهير الهوست، تفكيك روابط M3U، والفحص الذكي المتوازي (Smart Bypass)
   ============================================================================= */
function xte_sanitize_redirect_url(string $raw): string {
    $u = trim($raw);
    if ($u === '') return '';
    for ($i = 0; $i < 3; $i++) {
        if (preg_match('#^https?://(?:www\.)?google\.[^/]+/search\?(?:.*&)?q=([^&]+)#i', $u, $m)) {
            $u = rawurldecode($m[1]);
        } else {
            break;
        }
    }
    return trim($u);
}

function xte_normalize_url(string $url): string {
    $url = xte_sanitize_redirect_url($url);
    if ($url === '') return '';
    if (!preg_match('#^https?://#i', $url)) $url = 'http://' . $url;
    foreach (['/player_api.php', '/get.php', '/panel_api.php', '/xmltv.php'] as $ep) {
        $pos = stripos($url, $ep);
        if ($pos !== false) $url = substr($url, 0, $pos);
    }
    $p = @parse_url($url);
    if (!is_array($p) || empty($p['host'])) return rtrim($url, '/');
    if (stripos($p['host'], 'google.') !== false) return '';
    return strtolower($p['scheme'] ?? 'http') . '://' . strtolower($p['host']) . (!empty($p['port']) ? ':' . (int)$p['port'] : '');
}

function xte_decompress(string $raw): string {
    if ($raw === '' || strlen($raw) < 2) return $raw;
    if (ord($raw[0]) === 0x1f && ord($raw[1]) === 0x8b) {
        $d = @gzdecode($raw);
        if ($d !== false) return $d;
    }
    return $raw;
}

function xte_parse_m3u_link(string $raw): ?array {
    $raw = xte_sanitize_redirect_url($raw);
    if ($raw === '' || !preg_match('#(https?://[^\s"\'<>]+)#i', $raw, $m)) return null;
    $url = xte_sanitize_redirect_url($m[1]);
    $p = @parse_url($url);
    if (!is_array($p) || empty($p['host'])) return null;
    $base = strtolower($p['scheme'] ?? 'http') . '://' . strtolower($p['host']) . (!empty($p['port']) ? ':' . $p['port'] : '');
    if (!empty($p['query'])) {
        parse_str($p['query'], $q);
        $u  = trim((string)($q['username'] ?? $q['user'] ?? ''));
        $pw = trim((string)($q['password'] ?? $q['pass'] ?? ''));
        if ($u !== '' && $pw !== '') {
            return ['server_url' => $base, 'username' => $u, 'password' => $pw];
        }
    }
    if (!empty($p['path']) && preg_match('#/(?:live|movie|series)/([^/]+)/([^/]+)/#i', $p['path'], $mm)) {
        return ['server_url' => $base, 'username' => urldecode($mm[1]), 'password' => urldecode($mm[2])];
    }
    return null;
}

function xte_parse_json(string $raw): ?array {
    $raw = preg_replace('/^\xEF\xBB\xBF/', '', trim($raw));
    if ($raw === '') return null;
    $fc = strpos($raw, '{');
    $fb = strpos($raw, '[');
    if ($fc !== false && ($fb === false || $fc < $fb)) {
        $lc = strrpos($raw, '}');
        if ($lc !== false && $lc > $fc) {
            $raw = substr($raw, $fc, $lc - $fc + 1);
        }
    }
    $json = @json_decode($raw, true);
    if (!is_array($json)) return null;

    $uInfo = null;
    foreach ([
        $json['user_info'] ?? null,
        $json['data']['user_info'] ?? null,
        $json['result']['user_info'] ?? null,
        $json['user'] ?? null,
        (isset($json[0]) && is_array($json[0])) ? ($json[0]['user_info'] ?? $json[0]) : null
    ] as $c) {
        if (is_array($c)) { $uInfo = $c; break; }
    }
    if ($uInfo === null && (isset($json['auth']) || isset($json['username']) || isset($json['status']))) {
        $uInfo = $json;
    }
    if (!is_array($uInfo)) return null;

    $rawAuth = $uInfo['auth'] ?? null;
    $status  = strtolower(trim((string)($uInfo['status'] ?? $uInfo['account_status'] ?? '')));
    $authOk  = ($rawAuth === 1 || $rawAuth === '1' || $rawAuth === true || strtolower((string)$rawAuth) === 'true' || in_array($status, ['active', 'trial', 'enabled', 'ok', 'valid'], true));
    $statusBad = in_array($status, ['expired', 'banned', 'disabled', 'blocked', 'invalid', 'suspended'], true);

    $expDate = '';
    $expLabel = 'غير محدود';
    $isExpired = false;
    $rawExp = $uInfo['exp_date'] ?? $uInfo['expire_date'] ?? $uInfo['expiration_date'] ?? '';

    if (is_numeric($rawExp) && (int)$rawExp > 100000000) {
        $expTs = (int)$rawExp;
        if ($expTs > 4102444800) $expTs = (int)floor($expTs / 1000);
        $expDate = date('Y-m-d', $expTs);
        $expLabel = $expDate;
        if ($expTs < time() - 86400) $isExpired = true;
    } elseif (is_string($rawExp) && trim($rawExp) !== '' && !in_array(strtolower(trim($rawExp)), ['null', 'unlimited', '0', 'never'], true)) {
        $ts = strtotime(trim($rawExp));
        if ($ts !== false && $ts > 100000000) {
            $expDate = date('Y-m-d', $ts);
            $expLabel = $expDate;
            if ($ts < time() - 86400) $isExpired = true;
        }
    }

    $valid = $authOk && !$statusBad && !$isExpired;
    return [
        'ok'          => $valid,
        'status'      => $status ?: 'Active',
        'exp_date'    => $expDate,
        'exp_label'   => $expLabel,
        'server_type' => 'Xtream Codes',
        'message'     => $valid ? "✅ نشط • الانتهاء: {$expLabel}" : "⚠️ الحساب غير نشط أو منتهي"
    ];
}

function xte_probe_single(string $serverUrl, string $user, string $pass, bool $allowSmartBypass = true): array {
    $serverUrl = xte_normalize_url($serverUrl);
    $user = trim($user);
    $pass = trim($pass);
    $fail = ['ok' => false, 'message' => 'تعذّر الاتصال بالسيرفر أو البيانات غير صحيحة'];
    if ($serverUrl === '' || $user === '' || $pass === '') {
        return ['ok' => false, 'message' => 'بيانات غير مكتملة'];
    }

    $userAgents = [
        'IBOPlayer/1.0 (Linux; Android 9; MI 8 Lite) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36',
        'IPTVSmartersPlayer/6.0 (Android; Mobile)',
        'TiviMate/4.7.0 (Android 13; TV)'
    ];

    $lastHttpCode = 0;
    foreach ($userAgents as $ua) {
        $ch = curl_init($serverUrl . '/player_api.php?username=' . rawurlencode($user) . '&password=' . rawurlencode($pass));
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT        => 16,
            CURLOPT_CONNECTTIMEOUT => 8,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => 0,
            CURLOPT_ENCODING       => 'identity',
            CURLOPT_USERAGENT      => $ua,
            CURLOPT_HTTPHEADER     => [
                'Accept: application/json, text/plain, */*',
                'Accept-Encoding: identity',
                'Connection: keep-alive',
                'Origin: ' . $serverUrl,
                'Referer: ' . $serverUrl . '/'
            ]
        ]);
        $raw = curl_exec($ch);
        $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);
        if ($httpCode > 0) $lastHttpCode = $httpCode;

        if (is_string($raw) && $raw !== '' && stripos($raw, 'you have been blocked') === false) {
            $raw = xte_decompress($raw);
            $parsed = xte_parse_json($raw);
            if ($parsed !== null) {
                if ($parsed['ok']) return $parsed;
                // إذا رد السيرفر صراحة بأن الحساب غير صحيح (auth=0)، نتوقف فوراً
                return ['ok' => false, 'message' => '❌ اسم المستخدم أو كلمة المرور غير صحيحة على هذا السيرفر'];
            }
        }
    }

    // الاحتياطي الذكي (Smart Bypass): إذا كان السيرفر يحظر IP الاستضافة ولكنه يستجيب بـ 200 أو 461، نقبله ليعمل مباشرة على شاشة الزبون
    if ($allowSmartBypass && ($lastHttpCode === 200 || $lastHttpCode === 461)) {
        return [
            'ok'          => true,
            'status'      => 'Active',
            'exp_date'    => '',
            'exp_label'   => 'تحقق في الشاشة',
            'server_type' => 'Xtream Codes',
            'message'     => '✅ السيرفر يستجيب (سيتم التحقق المباشر من الشاشة)'
        ];
    }

    return $fail;
}

function xte_probe_multi(string $user, string $pass, array $servers): array {
    $user = trim($user);
    $pass = trim($pass);
    if (empty($servers) || $user === '' || $pass === '') return [];

    $matched = [];
    $slowKeywords = ['king 365', 'king365', 'temu', 'tmasta', 'iron'];
    $slowServers = [];
    $fastServers = [];

    foreach ($servers as $srv) {
        $name = mb_strtolower((string)($srv['name'] ?? ''), 'UTF-8');
        $isSlow = false;
        foreach ($slowKeywords as $kw) {
            if (mb_strpos($name, $kw) !== false) { $isSlow = true; break; }
        }
        if ($isSlow) $slowServers[] = $srv;
        else $fastServers[] = $srv;
    }

    if (!empty($fastServers)) {
        $chunks = array_chunk($fastServers, 10);
        foreach ($chunks as $chunk) {
            $mh = curl_multi_init();
            $handles = [];
            foreach ($chunk as $srv) {
                $base = xte_normalize_url($srv['url']);
                if ($base === '') continue;
                $ch = curl_init($base . '/player_api.php?username=' . rawurlencode($user) . '&password=' . rawurlencode($pass));
                curl_setopt_array($ch, [
                    CURLOPT_RETURNTRANSFER => true,
                    CURLOPT_FOLLOWLOCATION => true,
                    CURLOPT_TIMEOUT        => 12,
                    CURLOPT_CONNECTTIMEOUT => 6,
                    CURLOPT_SSL_VERIFYPEER => false,
                    CURLOPT_SSL_VERIFYHOST => 0,
                    CURLOPT_ENCODING       => 'identity',
                    CURLOPT_USERAGENT      => 'IBOPlayer/1.0 (Linux; Android 9; MI 8 Lite) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36',
                    CURLOPT_HTTPHEADER     => [
                        'Accept: application/json, text/plain, */*',
                        'Accept-Encoding: identity',
                        'Connection: keep-alive',
                        'Origin: ' . $base,
                        'Referer: ' . $base . '/'
                    ]
                ]);
                curl_multi_add_handle($mh, $ch);
                $handles[] = ['ch' => $ch, 'srv' => $srv];
            }
            $running = null;
            do {
                $status = curl_multi_exec($mh, $running);
                if ($running > 0 && $status === CURLM_OK) {
                    curl_multi_select($mh, 0.4);
                }
            } while ($running > 0 && $status === CURLM_OK);

            foreach ($handles as $item) {
                $raw = (string)curl_multi_getcontent($item['ch']);
                curl_multi_remove_handle($mh, $item['ch']);
                curl_close($item['ch']);
                if ($raw !== '' && stripos($raw, 'you have been blocked') === false) {
                    $raw = xte_decompress($raw);
                    $parsed = xte_parse_json($raw);
                    if ($parsed !== null && $parsed['ok']) {
                        $matched[] = array_merge($item['srv'], $parsed);
                    }
                }
            }
            curl_multi_close($mh);
        }
    }

    foreach ($slowServers as $srv) {
        $res = xte_probe_single($srv['url'], $user, $pass, false);
        if (!empty($res['ok'])) {
            $matched[] = array_merge($srv, $res);
        }
    }

    return $matched;
}

/* =============================================================================
   4. تحميل قاعدة الهوستات السرية من data.txt وإخفاء الروابط كلياً عن الواجهة
   ============================================================================= */
function loadKnownServers(): array {
    $defaults = [
        ['name' => 'Temu VIP',        'url' => 'http://tmasta.com'],
        ['name' => 'King 365',        'url' => 'http://179.61.217.72'],
        ['name' => 'Nexon',           'url' => 'http://serine-tv.xyz'],
        ['name' => 'LIVE PRO VIP 4K', 'url' => 'http://tvflux4k.com:8080'],
        ['name' => 'IRON PRO MAX',    'url' => 'http://line.iron-ott.com:80'],
        ['name' => 'IRON TV PRO',     'url' => 'http://irontvpro.com:8080'],
        ['name' => 'KING 365 4K',     'url' => 'http://line.king365tv.com:8080'],
        ['name' => 'TREX 4K OTT',     'url' => 'http://line.trex-ott.com:80'],
        ['name' => 'DINO IPTV',       'url' => 'http://line.dino-iptv.com:8080'],
        ['name' => 'STRONG 8K UHD',   'url' => 'http://line.strong8k.com:8080'],
        ['name' => 'MEGA OTT PRO',    'url' => 'http://line.megaott.net:80'],
        ['name' => 'CRYSTAL OTT',     'url' => 'http://line.crystal-ott.com:8080'],
        ['name' => 'ATLAS PRO ONTV',  'url' => 'http://atlas-ontv.com:8080'],
        ['name' => 'SANSAT PRO',      'url' => 'http://sansat-ott.com:8080'],
        ['name' => 'STAR SHARE 4K',   'url' => 'http://starshare.live:8080']
    ];
    $byUrl = [];
    $file = __DIR__ . '/data.txt';
    if (is_file($file)) {
        $lines = @file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [];
        foreach ($lines as $line) {
            $line = trim($line);
            if ($line === '' || $line[0] === '#') continue;
            if (strpos($line, '|') !== false) {
                [$name, $url] = array_map('trim', explode('|', $line, 2));
            } else {
                $url = $line;
                $name = 'VIP Server';
            }
            $nu = xte_normalize_url($url);
            if ($nu !== '') {
                $byUrl[$nu] = ['name' => $name ?: 'VIP Server', 'url' => $nu];
            }
        }
    }
    foreach ($defaults as $d) {
        $nu = xte_normalize_url($d['url']);
        if ($nu !== '' && !isset($byUrl[$nu])) {
            $byUrl[$nu] = ['name' => $d['name'], 'url' => $nu];
        }
    }
    return array_values($byUrl);
}

/**
 * يستبدل أي اسم يفضح رابط الهوست أو الـ IP باسم العلامة التجارية من data.txt أو اسم مموه أنيق
 */
function resolveSafeServerDisplayName(string $rawName, string $serverUrl): string {
    $normUrl = xte_normalize_url($serverUrl);
    // Official brands only — ignore data.txt labels that may have been written by another customer
    static $official = [
        ['name' => 'Temu VIP', 'url' => 'http://tmasta.com'],
        ['name' => 'King 365', 'url' => 'http://179.61.217.72'],
        ['name' => 'Nexon', 'url' => 'http://serine-tv.xyz'],
        ['name' => 'LIVE PRO VIP 4K', 'url' => 'http://tvflux4k.com:8080'],
        ['name' => 'IRON PRO MAX', 'url' => 'http://line.iron-ott.com:80'],
        ['name' => 'IRON TV PRO', 'url' => 'http://irontvpro.com:8080'],
        ['name' => 'KING 365 4K', 'url' => 'http://line.king365tv.com:8080'],
        ['name' => 'TREX 4K OTT', 'url' => 'http://line.trex-ott.com:80'],
        ['name' => 'DINO IPTV', 'url' => 'http://line.dino-iptv.com:8080'],
        ['name' => 'STRONG 8K UHD', 'url' => 'http://line.strong8k.com:8080'],
        ['name' => 'MEGA OTT PRO', 'url' => 'http://line.megaott.net:80'],
        ['name' => 'CRYSTAL OTT', 'url' => 'http://line.crystal-ott.com:8080'],
        ['name' => 'ATLAS PRO ONTV', 'url' => 'http://atlas-ontv.com:8080'],
        ['name' => 'SANSAT PRO', 'url' => 'http://sansat-ott.com:8080'],
        ['name' => 'STAR SHARE 4K', 'url' => 'http://starshare.live:8080'],
    ];
    foreach ($official as $k) {
        if (xte_normalize_url($k['url']) === $normUrl && !empty($k['name'])) {
            return $k['name'];
        }
    }
    $cleanName = trim($rawName);
    $hostPart = (string)(@parse_url($normUrl, PHP_URL_HOST) ?: '');
    // Hide host/IP-looking labels; keep only the *current user* custom label (stored per device_id row)
    if ($cleanName === '' ||
        stripos($cleanName, 'http') !== false ||
        ($hostPart !== '' && strcasecmp($cleanName, $hostPart) === 0) ||
        preg_match('/^\d{1,3}(\.\d{1,3}){3}$/', $cleanName) ||
        preg_match('/\.(com|net|org|xyz|tv|cc|me|live|pro|info|biz)$/i', $cleanName)) {
        return 'XTREAM VIP';
    }
    return $cleanName;
}

function learnWorkingServerToDataTxt(string $serverName, string $serverUrl): void {
    // ISOLATION: never write customer-chosen server labels into shared data.txt.
    // Host discovery stays admin-catalog only so Customer A name never leaks to Customer B.
    // Per-device names remain only in customer_xtream_lists for that device_id.
    return;
}


/* =============================================================================
   5. إدارة القوائم مع إخفاء الهوست وكلمة المرور كلياً عن المتصفح
   ============================================================================= */
function getPlaylists(string $deviceId, ?PDO $p, bool $forBrowserUi = true): array {
    $id = normalizeDevice($deviceId);
    if ($id === '' || $p === null) return [];
    try {
        $st = $p->prepare("SELECT * FROM customer_xtream_lists WHERE UPPER(device_id) = ? ORDER BY order_index ASC, id ASC");
        $st->execute([$id]);
        $out = [];
        foreach ($st->fetchAll() as $idx => $r) {
            $decUrl  = decryptSecret((string)$r['server_url']);
            $decPass = decryptSecret((string)$r['xtream_pass']);
            $safeName = resolveSafeServerDisplayName((string)$r['server_name'], $decUrl);

            if ($forBrowserUi) {
                // في واجهة المتصفح: الهوست وكلمة المرور مخفيان كلياً ولا يُرسلان إلى المتصفح نهائياً!
                $out[] = [
                    'id'          => (string)$r['playlist_id'],
                    'playlist_id' => (string)$r['playlist_id'],
                    'server_name' => $safeName,
                    'server_url'  => '', // مخفي كلياً 100%
                    'username'    => (string)$r['xtream_user'],
                    'password'    => '••••••••',
                    'exp_date'    => (string)($r['exp_date'] ?? ''),
                    'server_type' => (string)($r['server_type'] ?? 'Xtream API'),
                    'enabled'     => ((int)($r['enabled'] ?? 1)) === 1
                ];
            } else {
                // لتطبيق الشاشة (Android TV API Sync) فقط
                $out[] = [
                    'id'          => (string)$r['playlist_id'],
                    'playlist_id' => (string)$r['playlist_id'],
                    'device_id'   => (string)$r['device_id'],
                    'server_name' => $safeName,
                    'server_url'  => $decUrl,
                    'username'    => (string)$r['xtream_user'],
                    'password'    => $decPass,
                    'm3u_url'     => (string)($r['m3u_url'] ?? ''),
                    'exp_date'    => (string)($r['exp_date'] ?? ''),
                    'server_type' => (string)($r['server_type'] ?? 'Xtream API'),
                    'enabled'     => ((int)($r['enabled'] ?? 1)) === 1,
                    'order_index' => (int)($r['order_index'] ?? $idx)
                ];
            }
        }
        return $out;
    } catch (Throwable $e) {
        return [];
    }
}

function savePlaylist(string $deviceId, array $item, ?PDO $p): bool {
    if ($p === null) return false;
    try {
        $id = normalizeDevice($deviceId);
        $pid = trim((string)($item['id'] ?? $item['playlist_id'] ?? ''));
        if ($pid === '') {
            $pid = 'PL_' . strtoupper(substr(bin2hex(random_bytes(4)), 0, 8));
        }

        $rawUrlInput = trim((string)($item['server_url'] ?? ''));
        $user = trim((string)($item['username'] ?? $item['xtream_user'] ?? ''));
        $pass = trim((string)($item['password'] ?? $item['xtream_pass'] ?? ''));
        $exp  = trim((string)($item['exp_date'] ?? ''));
        $type = 'Xtream API';
        $isAutoHost = isset($item['is_auto_host']) ? (int)$item['is_auto_host'] : 1;

        $chk = $p->prepare("SELECT id, server_name, server_url, xtream_pass, exp_date, is_auto_host FROM customer_xtream_lists WHERE UPPER(device_id) = ? AND playlist_id = ? LIMIT 1");
        $chk->execute([$id, $pid]);
        $ex = $chk->fetch();

        if ($ex) {
            $existingUrl = decryptSecret((string)$ex['server_url']);
            $url = ($rawUrlInput === '' || $rawUrlInput === '••••••••' || stripos($rawUrlInput, 'مخفي') !== false)
                ? $existingUrl
                : xte_normalize_url($rawUrlInput);
            if ($url === '') $url = $existingUrl;

            $encPass = ($pass === '' || $pass === '••••••••')
                ? (string)$ex['xtream_pass']
                : encryptSecret($pass);

            $rawName = trim((string)($item['server_name'] ?? ''));
            $name = $rawName !== '' ? resolveSafeServerDisplayName($rawName, $url) : resolveSafeServerDisplayName((string)$ex['server_name'], $url);
            if ($exp === '') $exp = (string)($ex['exp_date'] ?? '');

            $p->prepare("
                UPDATE customer_xtream_lists
                SET server_name = ?, server_url = ?, xtream_user = ?, xtream_pass = ?, exp_date = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
            ")->execute([$name, $url, $user, $encPass, $exp, $ex['id']]);
        } else {
            $url = xte_normalize_url($rawUrlInput);
            if ($url === '') return false;
            $name = resolveSafeServerDisplayName((string)($item['server_name'] ?? ''), $url);

            // منع تكرار نفس السيرفر ونفس اليوزر لنفس الشاشة
            $dupChk = $p->prepare("SELECT id FROM customer_xtream_lists WHERE UPPER(device_id) = ? AND LOWER(server_url) = LOWER(?) AND LOWER(xtream_user) = LOWER(?) LIMIT 1");
            $dupChk->execute([$id, $url, $user]);
            $dupRow = $dupChk->fetch();

            $encPass = encryptSecret($pass);

            if ($dupRow) {
                $p->prepare("
                    UPDATE customer_xtream_lists
                    SET server_name = ?, xtream_pass = ?, exp_date = ?, is_auto_host = ?, enabled = 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                ")->execute([$name, $encPass, $exp, $isAutoHost, $dupRow['id']]);
            } else {
                $maxOrd = (int)$p->query("SELECT COALESCE(MAX(order_index), -1) + 1 FROM customer_xtream_lists WHERE UPPER(device_id) = " . $p->quote($id))->fetchColumn();
                $p->prepare("
                    INSERT INTO customer_xtream_lists
                    (playlist_id, device_id, server_name, server_url, xtream_user, xtream_pass, exp_date, server_type, is_auto_host, enabled, order_index, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP)
                ")->execute([$pid, $id, $name, $url, $user, $encPass, $exp, $type, $isAutoHost, $maxOrd]);
            }
        }
        return true;
    } catch (Throwable $e) {
        return false;
    }
}

function deletePlaylist(string $deviceId, string $pid, ?PDO $p): bool {
    if ($p === null) return false;
    try {
        $p->prepare("DELETE FROM customer_xtream_lists WHERE UPPER(device_id) = ? AND playlist_id = ?")
          ->execute([normalizeDevice($deviceId), trim($pid)]);
        return true;
    } catch (Throwable $e) {
        return false;
    }
}

/* =============================================================================
   6. فحص صلاحية الشاشة، النقل الذري بين الأجهزة، وحماية الـ PIN الصارمة
   ============================================================================= */
function verifySubscription(string $deviceId, ?PDO $p): array {
    $id = normalizeDevice($deviceId);
    if ($id === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $id)) {
        return [
            'active'      => false,
            'registered'  => false,
            'reason'      => 'invalid_format',
            'days_left'   => 0,
            'expire_date' => '',
            'message'     => '❌ رمز الشاشة خاطئ! يرجى التأكد من كتابة رمز الشاشة المكون من 8 أحرف وأرقام بالصيغة الصحيحة (مثال: DEV-A1B2-C3D4).'
        ];
    }
    if ($p === null) {
        return [
            'active'      => false,
            'registered'  => false,
            'reason'      => 'db_error',
            'days_left'   => 0,
            'expire_date' => '',
            'message'     => '⛔ تعذّر الاتصال بقاعدة بيانات الاشتراكات للتحقق من الرمز.'
        ];
    }
    try {
        $st = $p->prepare("SELECT * FROM users WHERE UPPER(device_id) = ? LIMIT 1");
        $st->execute([$id]);
        $row = $st->fetch();
        if (!$row) {
            return [
                'active'      => false,
                'registered'  => false,
                'reason'      => 'not_registered',
                'days_left'   => 0,
                'expire_date' => '',
                'message'     => "⛔ رمز الشاشة ({$id}) غير مسجل مسبقاً في النظام!\nيرجى فتح تطبيق LIVE PRO على التلفاز أو الهاتف أولاً لتسجيل الشاشة، ثم إدخال الرمز الصحيح."
            ];
        }
        $expRaw = trim((string)($row['expire_date'] ?? ''));
        $expTs  = $expRaw !== '' ? strtotime($expRaw . ' 23:59:59') : 0;
        $days   = $expTs > 0 ? (int)ceil(($expTs - time()) / 86400) : 0;
        if (((int)($row['is_active'] ?? 1)) === 0) {
            return [
                'active'      => false,
                'registered'  => true,
                'reason'      => 'disabled',
                'days_left'   => 0,
                'expire_date' => $expRaw,
                'message'     => "⛔ اشتراك هذه الشاشة ({$id}) معطّل أو موقوف.\nيرجى التواصل مع: " . SUPPORT_NAME . " (" . SUPPORT_PHONE . ")"
            ];
        }
        if ($expRaw === '' || $expTs <= 0 || $expTs < time()) {
            return [
                'active'      => false,
                'registered'  => true,
                'reason'      => 'expired',
                'days_left'   => 0,
                'expire_date' => $expRaw,
                'message'     => "⛔ انتهت صلاحية اشتراك هذه الشاشة ({$id})" . ($expRaw !== '' ? " بتاريخ ({$expRaw})" : "") . ".\nلا يمكن الدخول إلى فضاء الزبون إلا بعد تجديد الاشتراك عبر التواصل مع: " . SUPPORT_NAME . " (" . SUPPORT_PHONE . ")"
            ];
        }
        return [
            'active'      => true,
            'registered'  => true,
            'reason'      => 'ok',
            'days_left'   => max(1, $days),
            'expire_date' => $expRaw,
            'message'     => ''
        ];
    } catch (Throwable $e) {
        return [
            'active'      => false,
            'registered'  => false,
            'reason'      => 'db_error',
            'days_left'   => 0,
            'expire_date' => '',
            'message'     => '⛔ تعذّر التحقق من صلاحية رمز الشاشة حالياً.'
        ];
    }
}

function transferDevice(?PDO $p, string $oldId, string $newId): array {
    $oldId = normalizeDevice($oldId);
    $newId = normalizeDevice($newId);
    if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $newId)) {
        return ['ok' => false, 'message' => '⚠️ صيغة رمز الشاشة الجديد غير صحيحة (مثال: DEV-A1B2-C3D4)'];
    }
    if ($oldId === $newId) {
        return ['ok' => false, 'message' => '⚠️ رمز الشاشة الجديد مطابق للرمز الحالي'];
    }
    if ($p === null) {
        return ['ok' => false, 'message' => 'تعذّر الاتصال بقاعدة البيانات'];
    }
    try {
        $p->beginTransaction();
        $st = $p->prepare("SELECT * FROM users WHERE UPPER(device_id) = ? LIMIT 1");
        $st->execute([$oldId]);
        $oldUser = $st->fetch();
        $st->execute([$newId]);
        $newUser = $st->fetch();

        $expMoved = $oldUser ? (string)$oldUser['expire_date'] : date('Y-m-d', strtotime('+3 days'));
        $days = $expMoved ? max(0, (int)ceil((strtotime($expMoved . ' 23:59:59') - time()) / 86400)) : 0;

        if ($oldUser) {
            if ($newUser) {
                $p->prepare("UPDATE users SET expire_date=?, is_active=?, trial_used=1, updated_at=CURRENT_TIMESTAMP WHERE id=?")
                  ->execute([$expMoved, (int)($oldUser['is_active'] ?? 1), $newUser['id']]);
                $p->prepare("DELETE FROM users WHERE id=?")->execute([$oldUser['id']]);
            } else {
                $p->prepare("UPDATE users SET device_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")
                  ->execute([$newId, $oldUser['id']]);
            }
        }

        $moved = $p->prepare("UPDATE customer_xtream_lists SET device_id=?, updated_at=CURRENT_TIMESTAMP WHERE UPPER(device_id)=?");
        $moved->execute([$newId, $oldId]);
        $count = $moved->rowCount();

        try {
            $favOld = $p->prepare("SELECT favorites_json, items_count FROM device_favorites WHERE UPPER(device_id) = ? LIMIT 1");
            $favOld->execute([$oldId]);
            $favRow = $favOld->fetch(PDO::FETCH_ASSOC);
            if ($favRow) {
                $p->prepare("INSERT INTO device_favorites (device_id, favorites_json, items_count, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT(device_id) DO UPDATE SET favorites_json = excluded.favorites_json, items_count = excluded.items_count, updated_at = CURRENT_TIMESTAMP")
                   ->execute([$newId, $favRow['favorites_json'], (int)$favRow['items_count']]);
                $p->prepare("DELETE FROM device_favorites WHERE UPPER(device_id) = ?")->execute([$oldId]);
            }
        } catch (Throwable $e) {}

        // نقل قفل الـ PIN أيضاً إلى الجهاز الجديد
        $pinSt = $p->prepare("SELECT pin_hash FROM portal_security_pins WHERE UPPER(device_id) = ?");
        $pinSt->execute([$oldId]);
        $oldPin = (string)($pinSt->fetchColumn() ?: '');
        if ($oldPin !== '') {
            $p->prepare("INSERT INTO portal_security_pins (device_id, pin_hash, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) ON CONFLICT(device_id) DO UPDATE SET pin_hash = excluded.pin_hash, updated_at = CURRENT_TIMESTAMP")
              ->execute([$newId, $oldPin]);
            $p->prepare("DELETE FROM portal_security_pins WHERE UPPER(device_id) = ?")->execute([$oldId]);
        }

        $p->commit();
        return [
            'ok'          => true,
            'new_device'  => $newId,
            'days_left'   => $days,
            'moved_lists' => $count,
            'message'     => "✅ تم نقل الشاشة بنجاح ({$count} قائمة، {$days} يوم متبقٍ)"
        ];
    } catch (Throwable $e) {
        if ($p->inTransaction()) $p->rollBack();
        return ['ok' => false, 'message' => 'خطأ أثناء النقل: ' . $e->getMessage()];
    }
}

function getPinHash(string $deviceId, ?PDO $p): string {
    if ($p === null) return '';
    try {
        $st = $p->prepare("SELECT pin_hash FROM portal_security_pins WHERE UPPER(device_id) = ?");
        $st->execute([normalizeDevice($deviceId)]);
        return (string)($st->fetchColumn() ?: '');
    } catch (Throwable $e) {
        return '';
    }
}

/* =============================================================================
   7. التوجيه ومعالجة طلبات تطبيق الشاشة والواجهة (Routing & API)
   ============================================================================= */
try {
    $pdo = getPortalDB();
    $action = trim((string)(($_SERVER['REQUEST_METHOD'] === 'POST'
        ? ($_POST['action'] ?? '')
        : ($_GET['action'] ?? '')) ?: ($_REQUEST['action'] ?? '')));

    // أ) نقاط النهاية الخاصة بمزامنة تطبيق الشاشة (Android TV App API)
    if (in_array($action, ['get_xtream_lists', 'save_xtream', 'delete_xtream', 'check_status', 'transfer_device', 'test_xtream_connection', 'get_discovery_hosts', 'save_favorites', 'get_favorites'], true)) {
        header('Content-Type: application/json; charset=utf-8');
        // PRODUCTION: write operations require POST
        if (in_array($action, ['save_xtream', 'delete_xtream', 'transfer_device', 'test_xtream_connection', 'save_favorites'], true)
            && ($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
            http_response_code(405);
            echo json_encode(['success' => false, 'error' => 'Method Not Allowed — use POST'], JSON_UNESCAPED_UNICODE);
            exit;
        }
        $reqDevice = normalizeDevice((string)(($_POST['device_id'] ?? $_GET['device_id'] ?? $_REQUEST['device_id'] ?? '')));
        $subStatus = verifySubscription($reqDevice, $pdo);
        $uaHeader  = (string)($_SERVER['HTTP_USER_AGENT'] ?? '');
        $isOfficialTvApp = (stripos($uaHeader, 'LIVE-PRO/') !== false || stripos($uaHeader, 'okhttp') !== false);

        // إذا تم استدعاء الرابط من متصفح عادي والجهاز محمي بـ PIN غير مفتوح، نمنع كشف أي بيانات نهائياً
        $apiPinHash = getPinHash($reqDevice, $pdo);
        if (!$isOfficialTvApp && $apiPinHash !== '' && empty($_SESSION['pin_ok_' . $reqDevice])) {
            http_response_code(403);
            echo json_encode([
                'success'      => false,
                'error'        => '🔒 هذه الشاشة محمية برمز سري (PIN). لا يمكن عرض أي بيانات قبل فك الحماية.',
                'xtream_lists' => []
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }

        if ($action === 'get_discovery_hosts') {
            echo json_encode([
                'success' => true,
                'ok'      => true,
                'servers' => loadKnownServers()
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }

        if ($action === 'save_favorites') {
            if (!$subStatus['active']) {
                echo json_encode(['success' => false, 'ok' => false, 'error' => $subStatus['message'] ?? 'اشتراك غير نشط'], JSON_UNESCAPED_UNICODE);
                exit;
            }
            $rawJson = (string)($_POST['favorites_json'] ?? $_REQUEST['favorites_json'] ?? '[]');
            $decoded = json_decode($rawJson, true);
            if (!is_array($decoded)) {
                echo json_encode(['success' => false, 'ok' => false, 'error' => 'صيغة المفضلة غير صالحة'], JSON_UNESCAPED_UNICODE);
                exit;
            }
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
            try {
                if ($pdo !== null) {
                    $pdo->prepare("INSERT INTO device_favorites (device_id, favorites_json, items_count, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT(device_id) DO UPDATE SET favorites_json = excluded.favorites_json, items_count = excluded.items_count, updated_at = CURRENT_TIMESTAMP")
                       ->execute([$reqDevice, $storeJson, count($clean)]);
                }
                echo json_encode(['success' => true, 'ok' => true, 'count' => count($clean), 'message' => 'تم حفظ نسخة المفضلة'], JSON_UNESCAPED_UNICODE);
            } catch (Throwable $e) {
                echo json_encode(['success' => false, 'ok' => false, 'error' => 'تعذر حفظ المفضلة'], JSON_UNESCAPED_UNICODE);
            }
            exit;
        }

        if ($action === 'get_favorites') {
            try {
                if ($pdo === null) {
                    echo json_encode(['success' => true, 'ok' => true, 'found' => false, 'favorites' => [], 'count' => 0], JSON_UNESCAPED_UNICODE);
                    exit;
                }
                $st = $pdo->prepare("SELECT favorites_json, items_count, updated_at FROM device_favorites WHERE UPPER(device_id) = ? LIMIT 1");
                $st->execute([$reqDevice]);
                $row = $st->fetch(PDO::FETCH_ASSOC);
                if (!$row) {
                    echo json_encode(['success' => true, 'ok' => true, 'found' => false, 'favorites' => [], 'count' => 0, 'device_id' => $reqDevice], JSON_UNESCAPED_UNICODE);
                    exit;
                }
                $arr = json_decode((string)$row['favorites_json'], true);
                if (!is_array($arr)) $arr = [];
                echo json_encode([
                    'success' => true,
                    'ok' => true,
                    'found' => true,
                    'favorites' => $arr,
                    'count' => (int)($row['items_count'] ?? count($arr)),
                    'updated_at' => (string)($row['updated_at'] ?? ''),
                    'device_id' => $reqDevice
                ], JSON_UNESCAPED_UNICODE);
            } catch (Throwable $e) {
                echo json_encode(['success' => false, 'ok' => false, 'found' => false, 'favorites' => [], 'error' => 'تعذر قراءة المفضلة'], JSON_UNESCAPED_UNICODE);
            }
            exit;
        }

        if ($action === 'transfer_device') {
            if (!$subStatus['active']) {
                echo json_encode(['success' => false, 'ok' => false, 'message' => $subStatus['message']], JSON_UNESCAPED_UNICODE);
                exit;
            }
            $newDev = normalizeDevice((string)($_REQUEST['new_device_id'] ?? ''));
            $res = transferDevice($pdo, $reqDevice, $newDev);
            echo json_encode([
                'success' => $res['ok'],
                'ok'      => $res['ok'],
                'message' => $res['message']
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }

        if ($action === 'test_xtream_connection') {
            $u  = trim((string)($_REQUEST['username'] ?? ''));
            $pw = trim((string)($_REQUEST['password'] ?? ''));
            $matches = xte_probe_multi($u, $pw, loadKnownServers());
            if (!empty($matches)) {
                $first = $matches[0];
                $safeBrand = resolveSafeServerDisplayName((string)$first['name'], (string)$first['url']);
                echo json_encode([
                    'ok'          => true,
                    'server_name' => $safeBrand,
                    'server_url'  => $isOfficialTvApp ? $first['url'] : '',
                    'exp_date'    => (string)($first['exp_date'] ?? ''),
                    'message'     => $first['message'] ?? '✅ متصل'
                ], JSON_UNESCAPED_UNICODE);
            } else {
                echo json_encode(['ok' => false, 'message' => 'لم يتم العثور على سيرفر مطابق'], JSON_UNESCAPED_UNICODE);
            }
            exit;
        }

        if ($action === 'get_xtream_lists' || $action === 'check_status') {
            // المتصفح لا يحصل أبداً على الهوست أو الباسورد؛ فقط تطبيق الشاشة الرسمي يحصل عليها للتشغيل
            $maskForCaller = !$isOfficialTvApp;
            echo json_encode([
                'success'       => $subStatus['active'],
                'device_id'     => $reqDevice,
                'subscription'  => $subStatus,
                'xtream_lists'  => $subStatus['active'] ? getPlaylists($reqDevice, $pdo, $maskForCaller) : [],
                'contact_name'  => SUPPORT_NAME,
                'contact_phone' => SUPPORT_PHONE
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
        if ($action === 'save_xtream') {
            if (!$subStatus['active']) {
                echo json_encode(['success' => false, 'error' => $subStatus['message'], 'subscription' => $subStatus], JSON_UNESCAPED_UNICODE);
                exit;
            }
            $sUrl  = trim((string)($_REQUEST['server_url'] ?? ''));
            $sUser = trim((string)($_REQUEST['xtream_user'] ?? $_REQUEST['username'] ?? ''));
            $sPass = trim((string)($_REQUEST['xtream_pass'] ?? $_REQUEST['password'] ?? ''));
            $sName = trim((string)($_REQUEST['server_name'] ?? ''));
            $isAutoFlag = ($sUrl === '') ? 1 : 0;
            if ($sUrl === '' && $sUser !== '' && $sPass !== '' && !empty($_REQUEST['auto_discover'])) {
                $matches = xte_probe_multi($sUser, $sPass, loadKnownServers());
                if (!empty($matches)) {
                    $sUrl  = $matches[0]['url'];
                    $sName = $matches[0]['name'];
                }
            }
            if ($sUrl !== '') {
                $safeName = resolveSafeServerDisplayName($sName, $sUrl);
                learnWorkingServerToDataTxt($safeName, $sUrl);
                $savedOk = savePlaylist($reqDevice, [
                    'id'           => $_REQUEST['playlist_id'] ?? '',
                    'server_name'  => $safeName,
                    'server_url'   => $sUrl,
                    'username'     => $sUser,
                    'password'     => $sPass,
                    'exp_date'     => $_REQUEST['exp_date'] ?? '',
                    'is_auto_host' => $isAutoFlag
                ], $pdo);
                if (!$savedOk) {
                    http_response_code(500);
                    echo json_encode(['success' => false, 'message' => 'فشل حفظ القائمة في قاعدة البيانات.'], JSON_UNESCAPED_UNICODE);
                    exit;
                }
            } else {
                http_response_code(400);
                echo json_encode(['success' => false, 'message' => 'بيانات القائمة غير مكتملة.'], JSON_UNESCAPED_UNICODE);
                exit;
            }
            echo json_encode([
                'success'      => true,
                'message'      => 'تم حفظ القائمة ومزامنتها مع الشاشة',
                'subscription' => $subStatus,
                'xtream_lists' => getPlaylists($reqDevice, $pdo, false)
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
        if ($action === 'delete_xtream') {
            if (!$subStatus['active']) {
                echo json_encode(['success' => false, 'error' => $subStatus['message']], JSON_UNESCAPED_UNICODE);
                exit;
            }
            $deletedOk = deletePlaylist($reqDevice, (string)($_REQUEST['playlist_id'] ?? $_REQUEST['id'] ?? ''), $pdo);
            if (!$deletedOk) {
                http_response_code(500);
                echo json_encode(['success' => false, 'message' => 'تعذر حذف القائمة من قاعدة البيانات.'], JSON_UNESCAPED_UNICODE);
                exit;
            }
            echo json_encode([
                'success'      => true,
                'message'      => 'تم حذف القائمة',
                'xtream_lists' => getPlaylists($reqDevice, $pdo, false)
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
    }

    // ب) الفحص التلقائي الذكي عبر AJAX (مع إخفاء تام للهوست وحماية PIN)
    if ($action === 'auto_discover') {
        header('Content-Type: application/json; charset=utf-8');
        $u        = trim((string)($_REQUEST['username'] ?? ''));
        $pw       = trim((string)($_REQUEST['password'] ?? ''));
        $deviceId = normalizeDevice((string)($_REQUEST['device_id'] ?? ''));

        $subStatus = verifySubscription($deviceId, $pdo);
        if (!$subStatus['active']) {
            echo json_encode(['success' => false, 'message' => $subStatus['message']], JSON_UNESCAPED_UNICODE);
            exit;
        }

        // التحقق الصارم من قفل PIN قبل السماح بأي فحص أو إضافة
        $pinHashCheck = getPinHash($deviceId, $pdo);
        if ($pinHashCheck !== '' && empty($_SESSION['pin_ok_' . $deviceId])) {
            echo json_encode(['success' => false, 'message' => '🔒 القوائم محمية برمز PIN. يرجى فك الحماية أولاً.'], JSON_UNESCAPED_UNICODE);
            exit;
        }

        // دعم ذكي: إذا لصق الزبون رابط M3U كامل في حقل اسم المستخدم بالوضع التلقائي
        $splitAuto = xte_parse_m3u_link($u) ?: xte_parse_m3u_link($pw);
        if ($splitAuto !== null) {
            $probeM3u = xte_probe_single($splitAuto['server_url'], $splitAuto['username'], $splitAuto['password']);
            if (!empty($probeM3u['ok'])) {
                $safeBrand = resolveSafeServerDisplayName('', $splitAuto['server_url']);
                $savedOk = savePlaylist($deviceId, [
                    'server_name'  => $safeBrand,
                    'server_url'   => $splitAuto['server_url'],
                    'username'     => $splitAuto['username'],
                    'password'     => $splitAuto['password'],
                    'exp_date'     => (string)($probeM3u['exp_date'] ?? ''),
                    'is_auto_host' => 1
                ], $pdo);
                if (!$savedOk) {
                    http_response_code(500);
                    echo json_encode(['success' => false, 'message' => 'تم التعرف على السيرفر لكن فشل حفظ القائمة.'], JSON_UNESCAPED_UNICODE);
                    exit;
                }
                // لا نُرجع أي رابط هوست إطلاقاً في استجابة JSON
                echo json_encode([
                    'success' => true,
                    'message' => "✅ تم التعرف على السيرفر ({$safeBrand}) وحفظه بنجاح!"
                ], JSON_UNESCAPED_UNICODE);
                exit;
            }
        }

        $known   = loadKnownServers();
        $matches = xte_probe_multi($u, $pw, $known);

        if (!empty($matches)) {
            $added = 0;
            $matchedNames = [];
            foreach ($matches as $m) {
                $safeBrand = resolveSafeServerDisplayName((string)($m['name'] ?? ''), (string)($m['url'] ?? ''));
                $savedOk = savePlaylist($deviceId, [
                    'server_name'  => $safeBrand,
                    'server_url'   => $m['url'],
                    'username'     => $u,
                    'password'     => $pw,
                    'exp_date'     => (string)($m['exp_date'] ?? ''),
                    'is_auto_host' => 1
                ], $pdo);
                if ($savedOk) {
                    $matchedNames[] = $safeBrand;
                    $added++;
                }
            }
            $namesStr = implode(' ، ', array_unique($matchedNames));
            // لا نُرسل قائمة السيرفرات أو روابط الهوست نهائياً في استجابة المتصفح
            if ($added === 0) {
                http_response_code(500);
                echo json_encode(['success' => false, 'message' => 'تعذر حفظ نتائج الفحص في قاعدة البيانات.'], JSON_UNESCAPED_UNICODE);
            } else {
                echo json_encode([
                    'success' => true,
                    'message' => "✅ تم حفظ ({$added}) سيرفر متطابق [{$namesStr}] بنجاح!"
                ], JSON_UNESCAPED_UNICODE);
            }
        } else {
            echo json_encode([
                'success' => false,
                'message' => 'لم يتم العثور على سيرفر مطابق في الفحص التلقائي. تأكد من صحة اسم المستخدم وكلمة المرور أو استخدم الوضع اليدوي.'
            ], JSON_UNESCAPED_UNICODE);
        }
        exit;
    }

    // ج) معالجة جلسة الواجهة والـ PIN
    $rawDevice = trim((string)($_REQUEST['device_id'] ?? $_SESSION['portal_device'] ?? ''));
    if (isset($_GET['logout'])) {
        if ($rawDevice !== '') {
            $normLogout = normalizeDevice($rawDevice);
            unset($_SESSION['pin_ok_' . $normLogout]);
        }
        unset($_SESSION['portal_device'], $_SESSION['hide_device']);
        $rawDevice = '';
    }

    $currentDevice = normalizeDevice($rawDevice);

    $flash        = '';
    $flashType    = 'info';
    $pinUnlocked  = false;
    $showTransfer = isset($_GET['show_transfer']);
    $subInfo      = null;
    $playlists    = [];
    $editItem     = null;
    $showSuccess  = false;
    $pinHash      = '';

    if ($currentDevice !== '') {
        if (!checkRateLimit($pdo)) {
            die('<h2 style="text-align:center;color:#EF4444;margin-top:60px;font-family:sans-serif;">⛔ تم تجاوز الحد المسموح من المحاولات. يرجى الانتظار 15 دقيقة.</h2>');
        }
        $subInfo = verifySubscription($currentDevice, $pdo);
        if (empty($subInfo['active'])) {
            // منع الدخول كلياً إلى فضاء الزبون إذا كان رمز الشاشة خاطئاً أو غير مسجل مسبقاً أو منتهي الصلاحية!
            recordFailed($pdo);
            unset($_SESSION['portal_device'], $_SESSION['pin_ok_' . $currentDevice], $_SESSION['hide_device']);
            $flash         = (string)($subInfo['message'] ?? '⛔ لا يمكن الدخول إلى فضاء الزبون بهذا الرمز.');
            $flashType     = 'error';
            $currentDevice = '';
            $subInfo       = null;
        } else {
            $_SESSION['portal_device'] = $currentDevice;
        }
    }

    if ($currentDevice !== '' && $subInfo !== null && !empty($subInfo['active'])) {
        $pinHash = getPinHash($currentDevice, $pdo);

        // زر القفل الفوري للقوائم المحمية بـ PIN
        if (isset($_GET['lock_pin']) && $pinHash !== '') {
            unset($_SESSION['pin_ok_' . $currentDevice]);
            header('Location: ?device_id=' . urlencode($currentDevice));
            exit;
        }

        $pinUnlocked = ($pinHash === '' || !empty($_SESSION['pin_ok_' . $currentDevice]));

        if (isset($_GET['toggle_hide'])) {
            $_SESSION['hide_device'] = empty($_SESSION['hide_device']);
            header('Location: ?device_id=' . urlencode($currentDevice));
            exit;
        }
        $displayDevice = !empty($_SESSION['hide_device']) ? 'DEV-****-****' : $currentDevice;

        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            if (!hash_equals($CSRF_TOKEN, (string)($_POST['csrf_token'] ?? ''))) {
                $flash     = '⛔ انتهت صلاحية الجلسة الأمنية، أعد المحاولة';
                $flashType = 'error';
            } else {
                $fa = (string)($_POST['form_action'] ?? '');

                if ($fa === 'verify_pin') {
                    $pin = trim((string)($_POST['pin_code'] ?? ''));
                    if ($pinHash !== '' && password_verify($pin, $pinHash)) {
                        $_SESSION['pin_ok_' . $currentDevice] = true;
                        $pinUnlocked = true;
                        $flash       = '🔓 تم فك حماية القوائم بنجاح';
                        $flashType   = 'success';
                    } else {
                        recordFailed($pdo);
                        $pinUnlocked = false;
                        $flash       = '❌ رمز الحماية السري (PIN) غير صحيح';
                        $flashType   = 'error';
                    }
                } elseif ($fa === 'set_pin' && $pinUnlocked) {
                    $oldPin = trim((string)($_POST['old_pin'] ?? ''));
                    $newPin = trim((string)($_POST['new_pin'] ?? ''));
                    $isVerified = ($pinHash === '') || password_verify($oldPin, $pinHash);

                    if (!$isVerified) {
                        $flash     = '❌ يجب إدخال رمز PIN الحالي بشكل صحيح لتغييره أو إلغائه';
                        $flashType = 'error';
                    } else {
                        if ($newPin === '') {
                            $pdo->prepare("DELETE FROM portal_security_pins WHERE UPPER(device_id) = ?")->execute([$currentDevice]);
                            unset($_SESSION['pin_ok_' . $currentDevice]);
                            $pinHash     = '';
                            $pinUnlocked = true;
                            $flash       = '🔓 تم إلغاء قفل الـ PIN بنجاح';
                            $flashType   = 'success';
                        } elseif (preg_match('/^[0-9]{4,8}$/', $newPin)) {
                            $hash = password_hash($newPin, PASSWORD_DEFAULT);
                            $pdo->prepare("INSERT INTO portal_security_pins (device_id, pin_hash, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) ON CONFLICT(device_id) DO UPDATE SET pin_hash = excluded.pin_hash, updated_at = CURRENT_TIMESTAMP")
                                ->execute([$currentDevice, $hash]);
                            // فور تفعيل رمز PIN جديد، نقفل الجلسة فوراً لكي تختفي جميع بيانات القوائم حتى يتم فك الحماية!
                            unset($_SESSION['pin_ok_' . $currentDevice]);
                            $pinHash     = $hash;
                            $pinUnlocked = false;
                            $flash       = '🔒 تم تفعيل قفل الـ PIN وإخفاء جميع بيانات القوائم بنجاح! أدخل الرمز لفك الحماية.';
                            $flashType   = 'success';
                        } else {
                            $flash     = '⚠️ يجب أن يتكون رمز PIN من 4 إلى 8 أرقام فقط';
                            $flashType = 'warning';
                        }
                    }
                } elseif ($fa === 'transfer_device' && $pinUnlocked) {
                    $res = transferDevice($pdo, $currentDevice, (string)($_POST['new_device_id'] ?? ''));
                    if ($res['ok']) {
                        if (!empty($_SESSION['pin_ok_' . $currentDevice])) {
                            $_SESSION['pin_ok_' . $res['new_device']] = true;
                        }
                        $_SESSION['portal_device'] = $res['new_device'];
                        $currentDevice = $res['new_device'];
                        $displayDevice = !empty($_SESSION['hide_device']) ? 'DEV-****-****' : $currentDevice;
                        $subInfo       = verifySubscription($currentDevice, $pdo);
                        $showTransfer  = false;
                        $flash         = $res['message'];
                        $flashType     = 'success';
                    } else {
                        $showTransfer = true;
                        $flash        = $res['message'];
                        $flashType    = 'error';
                    }
                } elseif ($fa === 'save_service' && $pinUnlocked) {
                    if (!$subInfo['active']) {
                        $flash     = '⛔ الاشتراك منتهي — للتجديد تواصل مع: ' . SUPPORT_NAME . ' (' . SUPPORT_PHONE . ')';
                        $flashType = 'error';
                    } else {
                        $u       = trim((string)($_POST['username'] ?? ''));
                        $pw      = trim((string)($_POST['password'] ?? ''));
                        $url     = trim((string)($_POST['server_url'] ?? ''));
                        $sNameIn = trim((string)($_POST['server_name'] ?? ''));
                        $editPid = trim((string)($_POST['edit_playlist_id'] ?? ''));

                        $split = xte_parse_m3u_link($url) ?: xte_parse_m3u_link($u);
                        if ($split !== null) {
                            $url = $split['server_url'];
                            if ($u === '' || strpos($u, 'http') === 0) $u = $split['username'];
                            if ($pw === '' || $pw === '••••••••') $pw = $split['password'];
                        }

                        // في حالة تعديل خدمة محفوظة مسبقاً: إذا ترك المستخدم حقل الرابط أو كلمة المرور فارغاً، نستعيدهما من القاعدة دون كشفهما
                        $existingRow = null;
                        if ($editPid !== '' && $pdo !== null) {
                            $stEx = $pdo->prepare("SELECT * FROM customer_xtream_lists WHERE UPPER(device_id) = ? AND playlist_id = ? LIMIT 1");
                            $stEx->execute([$currentDevice, $editPid]);
                            $existingRow = $stEx->fetch();
                        }

                        if ($existingRow) {
                            if ($url === '' || $url === '••••••••' || stripos($url, 'مخفي') !== false) {
                                $url = decryptSecret((string)$existingRow['server_url']);
                            }
                            if ($pw === '' || $pw === '••••••••') {
                                $pw = decryptSecret((string)$existingRow['xtream_pass']);
                            }
                            if ($sNameIn === '') {
                                $sNameIn = (string)$existingRow['server_name'];
                            }
                        }

                        if ($u === '' || $pw === '' || $url === '') {
                            $flash     = '⚠️ يرجى إدخال جميع البيانات المطلوبة';
                            $flashType = 'warning';
                        } else {
                            // إدخال يدوي مباشر وتسجيل فوري في قاعدة البيانات دون التحقق من اتصال السيرفر
                            $normManualUrl = xte_normalize_url($url);
                            $safeName = resolveSafeServerDisplayName($sNameIn, $normManualUrl);
                            learnWorkingServerToDataTxt($safeName, $normManualUrl);
                            $savedOk = savePlaylist($currentDevice, [
                                'id'           => $editPid,
                                'server_name'  => $safeName,
                                'server_url'   => $normManualUrl,
                                'username'     => $u,
                                'password'     => $pw,
                                'exp_date'     => (string)($existingRow['exp_date'] ?? ''),
                                'is_auto_host' => 0
                            ], $pdo);
                            if ($savedOk) {
                                $showSuccess = true;
                                $flash       = '✅ تم حفظ الخدمة وتسجيلها مباشرة في قاعدة البيانات بنجاح!';
                                $flashType   = 'success';
                            } else {
                                $flash       = '⚠️ تعذّر حفظ البيانات، يرجى التأكد من صحة الرابط';
                                $flashType   = 'error';
                            }
                        }
                    }
                } elseif ($fa === 'delete_playlist' && $pinUnlocked) {
                    deletePlaylist($currentDevice, (string)($_POST['playlist_id'] ?? ''), $pdo);
                    $flash     = '🗑️ تم حذف الخدمة من الشاشة بنجاح';
                    $flashType = 'success';
                }
            }
        }

        // حماية مطلقة: لا يتم جلب أي بيانات خاصة بالقوائم إطلاقاً إلا إذا كانت الحماية مفتوحة ($pinUnlocked === true)
        if ($pinUnlocked) {
            $playlists = getPlaylists($currentDevice, $pdo, true);
            if (isset($_GET['edit_id'])) {
                $eid = trim((string)$_GET['edit_id']);
                foreach ($playlists as $pl) {
                    if ($pl['id'] === $eid) {
                        // نمرر بيانات التعديل بدون رابط الهوست وبدون كلمة المرور نهائياً!
                        $editItem = $pl;
                        break;
                    }
                }
            }
        } else {
            $playlists    = [];
            $editItem     = null;
            $showTransfer = false;
        }
    }
} catch (Throwable $e) {
    http_response_code(500);
    die('FATAL ERROR: ' . htmlspecialchars($e->getMessage()));
}
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>LIVE PRO — فضاء الزبون الاحترافي المؤمّن</title>
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700;800;900&display=swap" rel="stylesheet">
<style>
:root{--bg:#08080A;--card:#141418;--bd:#282832;--ac:#E50914;--gd:#F59E0B;--cy:#38BDF8;--ok:#10B981;--er:#EF4444;--lv:#E2E8F0;--mt:#94A3B8}
*{box-sizing:border-box;margin:0;padding:0;text-align:center;-webkit-tap-highlight-color:transparent}
body{font-family:'Cairo',sans-serif;background:radial-gradient(circle at 50% 0%,#181820 0%,var(--bg) 75%);color:#F8FAFC;min-height:100vh;padding-bottom:48px;line-height:1.75;font-size:1.05rem}
.bar{position:sticky;top:0;z-index:40;background:rgba(10,10,14,.95);backdrop-filter:blur(16px);border-bottom:1px solid var(--bd);padding:12px 16px;box-shadow:0 5px 18px rgba(0,0,0,.82)}
.bar-in{max-width:840px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}
.logo{width:42px;height:42px;border-radius:12px;background:linear-gradient(135deg,#E50914,#991B1B);display:flex;align-items:center;justify-content:center;font-size:1.25rem;box-shadow:0 4px 14px rgba(229,9,20,.45)}
.title{font-size:1.18rem;font-weight:900;color:#fff}
.badges{max-width:840px;margin:8px auto 0;display:flex;align-items:center;justify-content:center;gap:8px;flex-wrap:wrap;width:100%}
.pill{display:inline-flex;align-items:center;justify-content:center;gap:5px;padding:5px 14px;border-radius:999px;font-size:.86rem;font-weight:800;background:rgba(38,38,48,.85);border:1px solid #3F3F4E;color:#F1F5F9}
.pill-ok{background:rgba(16,185,129,.18);border-color:var(--ok);color:#A7F3D0}
.pill-er{background:rgba(239,68,68,.22);border-color:var(--er);color:#FECACA}
.pill-ac{background:rgba(56,189,248,.14);border-color:var(--cy);color:#BAE6FD;font-family:monospace;letter-spacing:1px;font-size:.9rem}
.btn-exit{background:rgba(239,68,68,.18);border:1px solid var(--er);color:#FECACA;padding:6px 14px;border-radius:8px;font-size:.85rem;font-weight:800;text-decoration:none;display:inline-flex;align-items:center;gap:4px}
.btn-lock-top{background:rgba(245,158,11,.18);border:1px solid var(--gd);color:#FDE68A;padding:6px 14px;border-radius:8px;font-size:.85rem;font-weight:800;text-decoration:none;display:inline-flex;align-items:center;gap:4px}
.ct{max-width:840px;margin:18px auto 0;padding:0 12px}
.card{background:linear-gradient(180deg,rgba(22,22,28,.98),rgba(12,12,16,.99));border:1.5px solid var(--bd);border-radius:20px;padding:24px 18px;margin:0 auto 16px;box-shadow:0 12px 34px rgba(0,0,0,.78)}
.card-red{border:2px solid var(--er)!important;box-shadow:0 0 24px rgba(239,68,68,.35)!important}
.card-locked{border:2px solid var(--gd)!important;box-shadow:0 0 26px rgba(245,158,11,.25)!important}
.ctitle{font-size:1.24rem;font-weight:900;color:#fff;margin-bottom:14px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin-bottom:14px}
.stat{background:rgba(10,10,14,.92);border:1px solid var(--bd);border-radius:14px;padding:14px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px}
.stat-l{font-size:.88rem;color:var(--lv);font-weight:700}
.stat-v{font-size:1.18rem;font-weight:900;color:#fff}
.dev-code{font-family:monospace;font-size:1.08rem;font-weight:900;letter-spacing:1px;color:#38BDF8;background:rgba(56,189,248,.12);border:1.5px dashed rgba(56,189,248,.5);padding:8px 12px;border-radius:12px;display:inline-flex;align-items:center;justify-content:center;gap:8px;direction:ltr;width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.form-group{width:100%;margin:0 auto 14px;max-width:520px;text-align:center}
label{display:block;font-size:.96rem;font-weight:800;color:var(--lv);margin-bottom:6px;text-align:center}
input[type=text],input[type=password]{width:100%;background:#0A0A0E;border:1.5px solid var(--bd);color:#fff;padding:15px;border-radius:12px;font-family:inherit;font-size:1.05rem;text-align:center;outline:none;transition:all .2s}
input::placeholder{color:#64748B;text-align:center}
input:focus{border-color:var(--ac);box-shadow:0 0 0 3px rgba(229,9,20,.25)}
.code-in{font-family:monospace;font-size:1.18rem!important;font-weight:900;text-transform:uppercase;letter-spacing:2px}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;background:linear-gradient(135deg,#272732,#181820);color:#fff;border:1px solid rgba(255,255,255,.22);padding:15px 22px;border-radius:12px;font-family:inherit;font-weight:800;font-size:1.04rem;cursor:pointer;text-decoration:none;transition:all .15s;width:100%;max-width:360px;margin:0 auto}
.btn:hover{transform:translateY(-1px);box-shadow:0 6px 18px rgba(0,0,0,.6)}
.btn-ok{background:linear-gradient(135deg,var(--ok),#047857)}
.btn-gd{background:linear-gradient(135deg,var(--gd),#B45309)}
.btn-cy{background:linear-gradient(135deg,#0284C7,#0369A1)}
.btn-er{background:rgba(239,68,68,.2);border:1.5px solid var(--er);color:#FECACA}
.btn-sm{padding:9px 15px;font-size:.9rem;border-radius:10px;width:auto;display:inline-flex}
.alert{padding:14px 16px;border-radius:14px;margin:0 auto 16px;max-width:840px;font-weight:800;white-space:pre-line;line-height:1.65;font-size:.96rem}
.alert-success{background:rgba(16,185,129,.15);border:1px solid var(--ok);color:#A7F3D0}
.alert-error{background:rgba(239,68,68,.18);border:1px solid var(--er);color:#FECACA}
.alert-warning{background:rgba(245,158,11,.16);border:1px solid var(--gd);color:#FDE68A}
.host-shield-badge{display:inline-flex;align-items:center;justify-content:center;gap:6px;background:rgba(16,185,129,.12);border:1px dashed rgba(16,185,129,.55);color:#A7F3D0;padding:10px 14px;border-radius:12px;font-size:.9rem;font-weight:800;margin-bottom:12px;max-width:520px;width:100%}
.tbl-wrap{overflow-x:auto}
table{width:100%;border-collapse:collapse;margin-top:8px}
th,td{padding:12px 9px;text-align:center;border-bottom:1px solid var(--bd);font-size:.94rem}
th{color:var(--lv);font-weight:800;background:rgba(10,10,14,.85)}
.mob-cards{display:none}
.mob-card{background:rgba(10,10,14,.92);border:1px solid var(--bd);border-radius:14px;padding:15px;margin:0 auto 10px;display:flex;flex-direction:column;gap:10px;align-items:center;justify-content:center}
@media(max-width:720px){.desk-tbl{display:none}.mob-cards{display:block}}
.modal{position:fixed;inset:0;z-index:60;background:rgba(4,4,6,.88);backdrop-filter:blur(8px);display:flex;align-items:center;justify-content:center;padding:14px}
.modal-box{background:linear-gradient(180deg,#181820,#0D0D12);border:1.5px solid var(--bd);border-radius:20px;max-width:480px;width:100%;max-height:90vh;overflow-y:auto;padding:22px 16px;box-shadow:0 20px 60px rgba(0,0,0,.92)}
#successScreen{display:none;position:fixed;inset:0;z-index:200;background:rgba(3,1,8,.95);backdrop-filter:blur(15px);align-items:center;justify-content:center;padding:20px}
.success-box{background:linear-gradient(180deg,#0A1A12 0%,#06030D 100%);border:2px solid var(--ok);border-radius:24px;padding:30px 20px;max-width:420px;width:100%;box-shadow:0 20px 60px rgba(16,185,129,.25);animation:popIn .4s cubic-bezier(.175,.885,.32,1.275)}
@keyframes popIn{0%{transform:scale(.8);opacity:0}100%{transform:scale(1);opacity:1}}
.success-icon{font-size:3.5rem;margin-bottom:10px;animation:bounce 1s infinite alternate}
@keyframes bounce{from{transform:translateY(0)}to{transform:translateY(-10px)}}
</style>
</head>
<body>

<div id="successScreen">
    <div class="success-box">
        <div class="success-icon">🎉</div>
        <h2 style="color:#10B981;font-size:1.5rem;font-weight:900;margin-bottom:10px;">تم الحفظ بنجاح!</h2>
        <p style="color:#E2E8F0;font-size:1rem;line-height:1.6;margin-bottom:24px;">
            تم تسجيل الخدمة وتأمين الهوست بنجاح في قاعدة البيانات.<br>
            <strong style="color:#FDE68A;">⚠️ خطوة هامة:</strong> يرجى <strong>إغلاق تطبيق الشاشة</strong> (LIVE PRO) وإعادة فتحه من جديد لتحديث القوائم فوراً.
        </p>
        <button type="button" class="btn btn-ok" onclick="closeSuccess()">حسناً، فهمت</button>
    </div>
</div>

<header class="bar">
    <div class="bar-in">
        <div style="display:flex;align-items:center;gap:10px;">
            <div class="logo">👑</div>
            <div class="title">LIVE PRO — فضاء الزبون</div>
        </div>
        <?php if ($currentDevice !== ''): ?>
            <div style="display:flex;align-items:center;gap:8px;">
                <?php if ($pinHash !== '' && $pinUnlocked): ?>
                    <a href="?device_id=<?php echo urlencode($currentDevice); ?>&lock_pin=1" class="btn-lock-top">🔒 قفل القوائم</a>
                <?php endif; ?>
                <a href="?logout=1" class="btn-exit">🚪 خروج</a>
            </div>
        <?php endif; ?>
    </div>
    <?php if ($currentDevice !== '' && $subInfo !== null && !empty($subInfo['active'])): ?>
    <div class="badges">
        <span class="pill <?php echo $subInfo['active'] ? 'pill-ok' : 'pill-er'; ?>">
            <?php echo $subInfo['active'] ? '⏳ الصلاحية: ' . (int)$subInfo['days_left'] . ' يوم' : '⛔ الاشتراك منتهي'; ?>
        </span>
        <div class="pill pill-ac" dir="ltr">
            <span style="min-width:105px;display:inline-block;text-align:center;"><?php echo htmlspecialchars($displayDevice); ?></span>
            <a href="?device_id=<?php echo urlencode($currentDevice); ?>&toggle_hide=1" style="text-decoration:none;color:#A5F3FC;font-size:1rem;cursor:pointer;margin-left:4px;" title="إخفاء/إظهار رمز الشاشة"><?php echo !empty($_SESSION['hide_device']) ? '👁️' : '🙈'; ?></a>
        </div>
        <span class="pill">📞 <?php echo htmlspecialchars(SUPPORT_NAME); ?> : <span dir="ltr"><?php echo htmlspecialchars(SUPPORT_PHONE); ?></span></span>
    </div>
    <?php endif; ?>
</header>

<div class="ct">
<?php if ($flash !== ''): ?>
<div class="alert alert-<?php echo htmlspecialchars($flashType); ?>"><?php echo htmlspecialchars($flash); ?></div>
<?php endif; ?>

<?php if ($currentDevice === ''): ?>
<div class="card" style="max-width:480px;margin:24px auto">
    <div class="ctitle">🖥️ الدخول الآمن لفضاء الزبون</div>
    <p style="font-size:.88rem;color:var(--lv);margin-bottom:14px;">أدخل رمز الشاشة الخاص بجهازك لإدارة قوائمك</p>
    <form method="get">
        <div class="form-group">
            <input type="text" name="device_id" class="code-in" placeholder="DEV-XXXX-XXXX" required>
        </div>
        <button type="submit" class="btn btn-ok">🚀 دخول إلى فضاء الزبون</button>
    </form>
</div>
<?php endif; ?>

<?php if ($currentDevice !== '' && $subInfo !== null && !empty($subInfo['active'])): ?>

    <!-- ملخص الشاشة: عند تفعيل قفل PIN لا يظهر عدد القوائم ولا زر النقل حتى فك الحماية -->
    <div class="card <?php echo (!$subInfo['active'] || $subInfo['days_left'] <= 5) ? 'card-red' : ''; ?>">
        <div class="ctitle">📊 ملخص الشاشة</div>
        <div class="stats">
            <div class="stat">
                <span class="stat-l">🖥️ رمز الشاشة</span>
                <div class="dev-code"><?php echo htmlspecialchars($displayDevice); ?></div>
            </div>
            <div class="stat">
                <span class="stat-l">⏳ صلاحية المشغل</span>
                <span class="stat-v" style="color:<?php echo $subInfo['active'] ? '#10B981' : '#EF4444'; ?>">
                    <?php echo $subInfo['active'] ? (int)$subInfo['days_left'] . ' يوم' : 'منتهي'; ?>
                </span>
            </div>
            <div class="stat">
                <span class="stat-l">📡 حالة القوائم</span>
                <span class="stat-v" style="color:<?php echo $pinUnlocked ? '#FFFFFF' : '#FDE68A'; ?>">
                    <?php echo $pinUnlocked ? count($playlists) . ' خدمة' : '🔒 محمية بـ PIN'; ?>
                </span>
            </div>
        </div>
        <?php if ($pinUnlocked): ?>
            <button type="button" class="btn btn-gd" onclick="document.getElementById('tModal').style.display='flex'">🔄 نقل الشاشة إلى جهاز آخر</button>
        <?php else: ?>
            <div style="font-size:.82rem;color:#FDE68A;font-weight:800;">🔒 جميع القوائم والبيانات وعمليات النقل مخفية تماماً حتى فك حماية الـ PIN</div>
        <?php endif; ?>
    </div>

    <?php if (!$pinUnlocked): ?>
        <!-- شاشة الحماية الصارمة: لا تظهر أي معلومة عن القوائم حتى فك القفل -->
        <div class="card card-locked" style="max-width:480px;margin:0 auto 20px">
            <div class="ctitle">🔒 القوائم محمية برمز سري (PIN)</div>
            <p style="font-size:.9rem;color:var(--lv);margin-bottom:16px;">
                تم إخفاء جميع القوائم وبيانات الاشتراك الخاصة بهذه الشاشة بالكامل.<br>
                يرجى إدخال رمز الحماية السري (PIN) لفك القفل وإظهار قوائمك:
            </p>
            <form method="post" autocomplete="off">
                <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                <input type="hidden" name="form_action" value="verify_pin">
                <div class="form-group">
                    <input type="password" name="pin_code" placeholder="••••" maxlength="8" required autofocus class="code-in">
                </div>
                <button type="submit" class="btn btn-ok">🔓 فك الحماية وإظهار القوائم</button>
            </form>
        </div>
    <?php else: ?>

        <?php if ($subInfo['active']): ?>
        <div class="card">
            <div class="ctitle">➕ إضافة خدمة جديدة</div>
            <div style="display:flex;justify-content:center;gap:8px;margin-bottom:14px;">
                <button type="button" id="tabAuto" class="btn btn-ok btn-sm" style="flex:1;max-width:165px;" onclick="switchMode('auto')">🤖 تلقائي ذكي</button>
                <button type="button" id="tabManual" class="btn btn-sm" style="flex:1;max-width:165px;background:#1F1F28;" onclick="switchMode('manual')">🌐 إدخال يدوي</button>
            </div>

            <div id="autoShieldHint" class="host-shield-badge">
                🛡️ الوضع التلقائي الذكي: يتعرف على السيرفر تلقائياً مع إخفاء الهوست وحمايته كلياً
            </div>

            <form id="addForm" method="post" onsubmit="handleSmartSubmit(event)" autocomplete="off">
                <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                <input type="hidden" name="form_action" value="save_service">
                <input type="hidden" id="isManualMode" value="0">

                <div id="manualSec" style="display:none;margin-bottom:14px;">
                    <div class="form-group">
                        <label>🏷️ اسم الخدمة (اختياري)</label>
                        <input type="text" name="server_name" id="nameIn" placeholder="مثال: اشتراكي الذهبي VIP">
                    </div>
                    <div class="form-group">
                        <label>🌐 رابط السيرفر (أو الصق رابط M3U كامل لتفكيكه تلقائياً)</label>
                        <input type="text" name="server_url" id="urlIn" dir="ltr" placeholder="http://example.com:8080" oninput="autoSplitM3u(this.value)">
                    </div>
                </div>

                <div class="form-group">
                    <label>👤 اسم المستخدم (Username)</label>
                    <input type="text" name="username" id="userIn" dir="ltr" placeholder="Username" required>
                </div>
                <div class="form-group">
                    <label>🔑 كلمة المرور (Password)</label>
                    <input type="password" name="password" id="passIn" dir="ltr" placeholder="Password" required>
                </div>

                <button type="submit" id="actionBtn" class="btn btn-ok" style="margin-top:8px;">🔍 فحص وتفعيل تلقائي ذكي</button>
            </form>
        </div>
        <?php endif; ?>

        <!-- قائمة الخدمات: الهوست وكلمة المرور مخفيان كلياً 100% -->
        <div class="card">
            <div class="ctitle">📋 الخدمات المسجلة (<?php echo count($playlists); ?>)</div>
            <?php if (empty($playlists)): ?>
                <div style="padding:20px;color:var(--lv)"><p>لا توجد خدمات مسجلة لهذه الشاشة حالياً</p></div>
            <?php else: ?>
                <div class="tbl-wrap desk-tbl">
                    <table>
                        <thead>
                            <tr>
                                <th>#</th>
                                <th>اسم الخدمة</th>
                                <th>الهوست</th>
                                <th>المستخدم</th>
                                <th>الصلاحية</th>
                                <th>تحكم</th>
                            </tr>
                        </thead>
                        <tbody>
                        <?php foreach ($playlists as $i => $pl): ?>
                            <tr>
                                <td><strong>#<?php echo $i + 1; ?></strong></td>
                                <td><strong><?php echo htmlspecialchars($pl['server_name']); ?></strong></td>
                                <td><span class="pill" style="font-size:.7rem;">🔒 محمي ومخفي</span></td>
                                <td dir="ltr"><?php echo htmlspecialchars($pl['username']); ?></td>
                                <td><span class="pill <?php echo $pl['exp_date'] ? 'pill-ok' : ''; ?>" style="font-size:.72rem"><?php echo htmlspecialchars($pl['exp_date'] ?: 'نشط ✓'); ?></span></td>
                                <td>
                                    <div style="display:inline-flex;gap:6px;justify-content:center;align-items:center;">
                                        <a href="?device_id=<?php echo urlencode($currentDevice); ?>&edit_id=<?php echo urlencode($pl['id']); ?>" class="btn btn-sm btn-cy" title="تعديل">✏️</a>
                                        <form method="post" style="display:inline" onsubmit="return confirm('هل تريد حذف هذه الخدمة نهائياً؟');">
                                            <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                                            <input type="hidden" name="form_action" value="delete_playlist">
                                            <input type="hidden" name="playlist_id" value="<?php echo htmlspecialchars($pl['id']); ?>">
                                            <button type="submit" class="btn btn-sm btn-er" title="حذف">🗑️</button>
                                        </form>
                                    </div>
                                </td>
                            </tr>
                        <?php endforeach; ?>
                        </tbody>
                    </table>
                </div>

                <div class="mob-cards">
                <?php foreach ($playlists as $i => $pl): ?>
                    <div class="mob-card">
                        <div style="font-weight:900;font-size:1rem;">
                            <span style="color:#E50914;">#<?php echo $i + 1; ?></span>
                            <?php echo htmlspecialchars($pl['server_name']); ?>
                        </div>
                        <div style="display:flex;gap:6px;flex-wrap:wrap;justify-content:center;">
                            <span class="pill" style="font-size:.72rem;">🔒 الهوست محمي ومخفي</span>
                            <span class="pill pill-ok" style="font-size:.72rem;"><?php echo htmlspecialchars($pl['exp_date'] ?: 'نشط ✓'); ?></span>
                        </div>
                        <div dir="ltr" style="font-size:.92rem;color:#E2E8F0;font-weight:700;">👤 <?php echo htmlspecialchars($pl['username']); ?></div>
                        <div style="display:flex;gap:8px;width:100%;justify-content:center;">
                            <a href="?device_id=<?php echo urlencode($currentDevice); ?>&edit_id=<?php echo urlencode($pl['id']); ?>" class="btn btn-sm btn-cy" style="flex:1;">✏️ تعديل</a>
                            <form method="post" style="flex:1;" onsubmit="return confirm('هل تريد حذف هذه الخدمة نهائياً؟');">
                                <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                                <input type="hidden" name="form_action" value="delete_playlist">
                                <input type="hidden" name="playlist_id" value="<?php echo htmlspecialchars($pl['id']); ?>">
                                <button type="submit" class="btn btn-sm btn-er" style="width:100%;">🗑️ حذف</button>
                            </form>
                        </div>
                    </div>
                <?php endforeach; ?>
                </div>
            <?php endif; ?>
        </div>

        <!-- إعدادات حماية PIN -->
        <div class="card">
            <div class="ctitle">🔒 حماية القوائم برقم سري (PIN)</div>
            <p style="font-size:.84rem;color:var(--lv);margin-bottom:14px;">
                عند تفعيل رقم PIN، تُحجب جميع القوائم والبيانات تماماً ولا تظهر لأي شخص إلا بعد إدخال الرمز الصحيح.
            </p>
            <form method="post" autocomplete="off">
                <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                <input type="hidden" name="form_action" value="set_pin">
                <?php if ($pinHash !== ''): ?>
                <div class="form-group">
                    <label>🔑 الرمز السري (PIN) الحالي</label>
                    <input type="password" name="old_pin" maxlength="8" required placeholder="••••" class="code-in">
                </div>
                <?php endif; ?>
                <div class="form-group">
                    <label><?php echo $pinHash !== '' ? '🆕 الرمز الجديد (أو اتركه فارغاً لإلغاء القفل)' : '🛡️ أدخل رمز PIN جديد (4 إلى 8 أرقام)'; ?></label>
                    <input type="password" name="new_pin" maxlength="8" placeholder="••••" class="code-in">
                </div>
                <button type="submit" class="btn btn-ok">💾 حفظ وإغلاق القوائم بالقفل</button>
            </form>
        </div>

    <?php endif; ?>

<?php endif; ?>
</div>

<?php if ($pinUnlocked && $editItem !== null): ?>
<!-- نافذة التعديل: الهوست وكلمة المرور لا يظهران إطلاقاً في الحقول لحمايتهما الكاملة -->
<div id="editModal" class="modal" style="display:flex;">
    <div class="modal-box">
        <div class="ctitle">✏️ تعديل الخدمة (<?php echo htmlspecialchars($editItem['server_name']); ?>)</div>
        <div class="host-shield-badge">
            🔒 رابط الهوست محمي ومخفي كلياً — اترك حقل الرابط فارغاً للاحتفاظ بالسيرفر الحالي
        </div>
        <form method="post" onsubmit="showLoad('💾 جارٍ حفظ التعديلات...')" autocomplete="off">
            <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
            <input type="hidden" name="form_action" value="save_service">
            <input type="hidden" name="edit_playlist_id" value="<?php echo htmlspecialchars($editItem['id']); ?>">

            <div class="form-group">
                <label>🏷️ اسم الخدمة</label>
                <input type="text" name="server_name" value="<?php echo htmlspecialchars($editItem['server_name']); ?>" required>
            </div>
            <div class="form-group">
                <label>👤 اسم المستخدم (Username)</label>
                <input type="text" name="username" dir="ltr" value="<?php echo htmlspecialchars($editItem['username']); ?>" required>
            </div>
            <div class="form-group">
                <label>🔑 كلمة المرور الجديدة (اتركها فارغة للاحتفاظ بالحالية)</label>
                <input type="password" name="password" dir="ltr" value="" placeholder="•••••••• (محمية ومشفرة)">
            </div>
            <div class="form-group">
                <label>🌐 تغيير الهوست (اختياري — اتركه فارغاً للاحتفاظ بالهوست المخفي)</label>
                <input type="password" name="server_url" dir="ltr" value="" placeholder="🔒 الهوست الحالي مخفي ومحمي تلقائياً">
            </div>

            <div style="display:flex;gap:10px;justify-content:center;">
                <a href="?device_id=<?php echo urlencode($currentDevice); ?>" class="btn btn-er" style="flex:1;">إلغاء</a>
                <button type="submit" class="btn btn-ok" style="flex:1;">💾 حفظ التعديلات</button>
            </div>
        </form>
    </div>
</div>
<?php endif; ?>

<?php if ($currentDevice !== '' && $pinUnlocked): ?>
<div id="tModal" class="modal" style="display:<?php echo $showTransfer ? 'flex' : 'none'; ?>">
    <div class="modal-box">
        <div class="ctitle">🔄 نقل الشاشة والخدمات إلى جهاز جديد</div>
        <p style="font-size:.85rem;color:var(--lv);margin-bottom:14px;">
            سيتم نقل جميع قوائمك، وأيام الصلاحية المتبقية، وقفل الـ PIN فوراً إلى رمز الشاشة الجديد:
        </p>
        <form method="post" onsubmit="showLoad('🔄 جارٍ نقل الشاشة والخدمات...')">
            <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
            <input type="hidden" name="form_action" value="transfer_device">
            <div class="form-group">
                <label>🖥️ رمز الشاشة الجديد *</label>
                <input type="text" name="new_device_id" class="code-in" placeholder="DEV-XXXX-XXXX" required>
            </div>
            <div style="display:flex;gap:10px;justify-content:center;">
                <button type="button" class="btn btn-er" style="flex:1;" onclick="document.getElementById('tModal').style.display='none'">إلغاء</button>
                <button type="submit" class="btn btn-gd" style="flex:1;">تأكيد النقل الآن</button>
            </div>
        </form>
    </div>
</div>
<?php endif; ?>

<script>
let currentMode = 'auto';

function switchMode(m){
    currentMode = m;
    const tA = document.getElementById('tabAuto'),
          tM = document.getElementById('tabManual'),
          sec = document.getElementById('manualSec'),
          hint = document.getElementById('autoShieldHint'),
          btnAction = document.getElementById('actionBtn'),
          modeInput = document.getElementById('isManualMode');

    if (m === 'manual') {
        tA.style.background = '#1F1F28';
        tM.style.background = 'linear-gradient(135deg,var(--ok),#047857)';
        sec.style.display = 'block';
        if (hint) hint.style.display = 'none';
        btnAction.textContent = '💾 حفظ وتسجيل مباشر في قاعدة البيانات';
        modeInput.value = '1';
    } else {
        tM.style.background = '#1F1F28';
        tA.style.background = 'linear-gradient(135deg,var(--ok),#047857)';
        sec.style.display = 'none';
        if (hint) hint.style.display = 'inline-flex';
        btnAction.textContent = '🔍 فحص وتفعيل تلقائي ذكي';
        modeInput.value = '0';
    }
}

function autoSplitM3u(val) {
    if (!val || (val.indexOf('username=') === -1 && val.indexOf('/live/') === -1)) return;
    try {
        const cleanVal = val.replace(/^https?:\/\/(?:www\.)?google\.[^\/]+\/search\?(?:.*&)?q=/i, '');
        const u = new URL(decodeURIComponent(cleanVal.trim()));
        const base = u.protocol + '//' + u.host;
        let user = u.searchParams.get('username') || u.searchParams.get('user') || '';
        let pass = u.searchParams.get('password') || u.searchParams.get('pass') || '';
        if (!user && u.pathname) {
            const m = u.pathname.match(/\/(?:live|movie|series)\/([^\/]+)\/([^\/]+)/i);
            if (m) {
                user = decodeURIComponent(m[1]);
                pass = decodeURIComponent(m[2]);
            }
        }
        if (user && pass) {
            document.getElementById('urlIn').value = base;
            document.getElementById('userIn').value = user;
            document.getElementById('passIn').value = pass;
        }
    } catch (e) {}
}

function handleSmartSubmit(e) {
    e.preventDefault();

    const isManual = document.getElementById('isManualMode').value === '1';
    const u = document.getElementById('userIn').value.trim();
    const p = document.getElementById('passIn').value.trim();
    const url = document.getElementById('urlIn').value.trim();
    const did = '<?php echo htmlspecialchars($currentDevice, ENT_QUOTES); ?>';

    if (!u || !p) {
        alert('⚠️ يرجى إدخال اسم المستخدم وكلمة المرور');
        return;
    }

    if (isManual) {
        if (!url) {
            alert('⚠️ يرجى إدخال رابط السيرفر (Host) أو التحويل للوضع التلقائي الذكي');
            return;
        }
        showLoad('💾 جارٍ الحفظ والتسجيل المباشر في قاعدة البيانات...');
        document.getElementById('addForm').submit();
    } else {
        showLoad('🔍 جارٍ الفحص التلقائي الذكي للسيرفرات وتأمين الهوست...');

        const fd = new FormData();
        fd.append('action', 'auto_discover');
        fd.append('device_id', did);
        fd.append('username', u);
        fd.append('password', p);

        fetch(location.pathname, { method: 'POST', body: fd })
        .then(r => r.json())
        .then(data => {
            if (document.getElementById('loadOv')) document.getElementById('loadOv').remove();
            if (data.success) {
                document.getElementById('successScreen').style.display = 'flex';
            } else {
                alert('⚠️ ' + data.message);
            }
        }).catch(() => {
            if (document.getElementById('loadOv')) document.getElementById('loadOv').remove();
            alert('❌ حدث خطأ في الاتصال، حاول مجدداً.');
        });
    }
}

function showLoad(msg){
    if (document.getElementById('loadOv')) document.getElementById('loadOv').remove();
    let ov = document.createElement('div');
    ov.id = 'loadOv';
    ov.style.cssText = 'position:fixed;inset:0;z-index:100;background:rgba(3,1,8,.88);backdrop-filter:blur(10px);display:flex;align-items:center;justify-content:center;padding:16px;';
    ov.innerHTML = '<div style="background:#16161E;border:2px solid var(--ac);border-radius:20px;padding:30px;max-width:380px;width:100%;text-align:center;"><div style="width:50px;height:50px;border:4px solid rgba(124,58,237,.25);border-top-color:var(--ok);border-radius:50%;animation:spin .9s linear infinite;margin:0 auto 16px;"></div><h3 style="font-weight:900;color:#fff;font-size:1.05rem;">' + msg + '</h3><style>@keyframes spin{to{transform:rotate(360deg)}}</style></div>';
    document.body.appendChild(ov);
}

function closeSuccess() {
    document.getElementById('successScreen').style.display = 'none';
    location.href = '?device_id=<?php echo urlencode($currentDevice); ?>';
}

<?php if ($showSuccess): ?>
window.addEventListener('DOMContentLoaded', function() {
    document.getElementById('successScreen').style.display = 'flex';
});
<?php endif; ?>
</script>
</body>
</html>
