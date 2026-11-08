<?php
/**
 * =============================================================================
 * LIVE PRO v25.0 — فضاء الموزعين الاحترافي (Professional Reseller Portal)
 * - دخول آمن للموزعين المعتمدين من الإدارة
 * - نظام النقاط الذري:
 *     * اشتراك شهر واحد (30 يوم)  = 1 نقطة
 *     * اشتراك 3 أشهر (90 يوم)    = 3 نقاط
 *     * اشتراك 6 أشهر (180 يوم)   = 6 نقاط
 *     * اشتراك سنة كاملة (365 يوم) = 12 نقطة
 * - استخراج قسائم بصيغة قياسية صارمة (XXXX-XXXX) من اليسار لليمين (LTR) لا تتبعثر عند النسخ أو اللصق
 * - تفعيل مباشر لرمز شاشة الزبون (DEV-XXXX-XXXX) + فحص صلاحية شاشة الزبون
 * - كشف حساب تفصيلي للنقاط المشحونة والمستهلكة + طباعة ومشاركة القسائم عبر واتساب
 * =============================================================================
 */
declare(strict_types=1);

@ini_set('display_errors', '0');
@ini_set('html_errors', '0');
@ini_set('memory_limit', '512M');
error_reporting(E_ALL & ~E_NOTICE & ~E_DEPRECATED);

session_start();

header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: SAMEORIGIN');
header('Referrer-Policy: no-referrer');

if (!function_exists('str_starts_with')) {
    function str_starts_with($h, $n) { return strpos((string)$h, (string)$n) === 0; }
}
if (!defined('SUPPORT_NAME'))  define('SUPPORT_NAME',  'الدعم الفني');
if (!defined('SUPPORT_PHONE')) define('SUPPORT_PHONE', '');

/* =============================================================================
   1. الاتصال بقاعدة البيانات الموحدة وإنشاء جداول الموزعين والقسائم تلقائياً
   ============================================================================= */
function getResellerDB(): PDO {
    static $pdo = null;
    if ($pdo instanceof PDO) return $pdo;

    foreach (['db.php', 'config.php', '../db.php'] as $f) {
        if (is_file(__DIR__ . '/' . $f)) {
            @include_once __DIR__ . '/' . $f;
        }
    }
    if (function_exists('getDB')) {
        try {
            $p = @getDB();
            if ($p instanceof PDO) {
                $pdo = $p;
                ensureResellerPortalSchema($pdo);
                return $pdo;
            }
        } catch (Throwable $e) {}
    }
    foreach (['pdo', 'db', 'conn', 'connection'] as $v) {
        if (isset($GLOBALS[$v]) && $GLOBALS[$v] instanceof PDO) {
            $pdo = $GLOBALS[$v];
            ensureResellerPortalSchema($pdo);
            return $pdo;
        }
    }
    $dbPath = __DIR__ . '/livepro_portal.sqlite';
    $pdo = new PDO('sqlite:' . $dbPath, null, null, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
    ]);
    ensureResellerPortalSchema($pdo);
    return $pdo;
}

function getSecondaryPortalDbForReseller(): ?PDO {
    $portalDbFile = __DIR__ . '/livepro_portal.sqlite';
    if (!is_file($portalDbFile)) return null;
    try {
        $p = new PDO('sqlite:' . $portalDbFile, null, null, [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
        ]);
        return $p;
    } catch (Throwable $e) {
        return null;
    }
}

function ensureResellerPortalSchema(PDO $db): void {
    foreach (["PRAGMA journal_mode=WAL", "PRAGMA synchronous=NORMAL", "PRAGMA busy_timeout=15000"] as $pragma) {
        try { $db->exec($pragma); } catch (Throwable $e) {}
    }

    $db->exec("
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT UNIQUE NOT NULL,
            device_name TEXT DEFAULT '',
            expire_date TEXT DEFAULT '',
            is_active INTEGER DEFAULT 1,
            trial_used INTEGER DEFAULT 0,
            notes TEXT DEFAULT '',
            blocked_reason TEXT DEFAULT NULL,
            total_paid_days INTEGER DEFAULT 0,
            last_card_code TEXT DEFAULT NULL,
            last_activation_at TEXT DEFAULT NULL,
            registration_source TEXT DEFAULT 'app',
            bad_attempts INTEGER DEFAULT 0,
            last_bad_attempt_at TEXT DEFAULT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
            last_seen TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ");

    $db->exec("
        CREATE TABLE IF NOT EXISTS activation_cards (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT UNIQUE NOT NULL,
            card_type TEXT NOT NULL DEFAULT '1month',
            duration_days INTEGER NOT NULL DEFAULT 30,
            batch_id TEXT DEFAULT '',
            status TEXT NOT NULL DEFAULT 'unused',
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            expires_at TEXT DEFAULT NULL,
            used_at TEXT DEFAULT NULL,
            used_by_device TEXT DEFAULT NULL,
            attempts INTEGER DEFAULT 0,
            last_attempt_at TEXT DEFAULT NULL,
            notes TEXT DEFAULT NULL,
            created_by TEXT DEFAULT NULL,
            reseller_id INTEGER DEFAULT NULL,
            reseller_name TEXT DEFAULT NULL,
            points_cost INTEGER DEFAULT 0
        )
    ");

    $cCols = array_column($db->query("PRAGMA table_info(activation_cards)")->fetchAll(PDO::FETCH_ASSOC), 'name');
    foreach ([
        'used_at'       => "TEXT DEFAULT NULL",
        'used_by_device'=> "TEXT DEFAULT NULL",
        'attempts'      => "INTEGER DEFAULT 0",
        'last_attempt_at'=> "TEXT DEFAULT NULL",
        'notes'         => "TEXT DEFAULT NULL",
        'created_by'    => "TEXT DEFAULT NULL",
        'reseller_id'   => "INTEGER DEFAULT NULL",
        'reseller_name' => "TEXT DEFAULT NULL",
        'points_cost'   => "INTEGER DEFAULT 0"
    ] as $col => $def) {
        if (!in_array($col, $cCols, true)) {
            try { $db->exec("ALTER TABLE activation_cards ADD COLUMN $col $def"); } catch (Throwable $e) {}
        }
    }

    $db->exec("
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
    try { $db->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_redemptions_code_unique ON card_redemptions(code)"); } catch (Throwable $e) {}

    $db->exec("
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

    $db->exec("
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

    $db->exec("
        CREATE TABLE IF NOT EXISTS reseller_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            reseller_id INTEGER NOT NULL,
            token TEXT UNIQUE NOT NULL,
            expires_at TEXT NOT NULL,
            ip_address TEXT DEFAULT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ");


    $uCols = array_column($db->query("PRAGMA table_info(users)")->fetchAll(PDO::FETCH_ASSOC), 'name');
    if (!in_array('reseller_id', $uCols, true)) {
        try { $db->exec("ALTER TABLE users ADD COLUMN reseller_id INTEGER DEFAULT NULL"); } catch (Throwable $e) {}
    }

    $rCols = array_column($db->query("PRAGMA table_info(resellers)")->fetchAll(PDO::FETCH_ASSOC), 'name');
    foreach ([
        'parent_reseller_id'   => "INTEGER DEFAULT NULL",
        'parent_reseller_name' => "TEXT DEFAULT NULL"
    ] as $col => $def) {
        if (!in_array($col, $rCols, true)) {
            try { $db->exec("ALTER TABLE resellers ADD COLUMN $col $def"); } catch (Throwable $e) {}
        }
    }

    $db->exec("
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
            server_type TEXT DEFAULT 'Xtream API',
            is_auto_host INTEGER DEFAULT 1,
            enabled INTEGER DEFAULT 1,
            order_index INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ");

    $db->exec("
        CREATE TABLE IF NOT EXISTS device_favorites (
            device_id TEXT PRIMARY KEY NOT NULL,
            favorites_json TEXT NOT NULL DEFAULT '[]',
            items_count INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ");

    $db->exec("
        CREATE TABLE IF NOT EXISTS activity_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT DEFAULT NULL,
            action TEXT NOT NULL,
            details TEXT DEFAULT NULL,
            ip_address TEXT DEFAULT NULL,
            user_agent TEXT DEFAULT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ");
}

/* =============================================================================
   2. دوال التطبيع الصارم والباقات المعتمدة
   ============================================================================= */

function rpEncryptSecret(string $plain): string {
    if ($plain === '' || str_starts_with($plain, 'ENCv9:') || str_starts_with($plain, 'ENC:')) return $plain;
    $key = hash('sha256', defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026', true);
    $iv = random_bytes(16);
    $cipher = openssl_encrypt($plain, 'AES-256-CBC', $key, OPENSSL_RAW_DATA, $iv);
    return $cipher !== false ? ('ENCv9:' . base64_encode($iv . $cipher)) : $plain;
}

function rpDecryptSecret(string $stored): string {
    if ($stored === '') return '';
    if (str_starts_with($stored, 'ENCv9:')) {
        $raw = base64_decode(substr($stored, 6), true);
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

function rpExtractDomainHost(string $input): string {
    $s = trim($input);
    if ($s === '') return '';
    $s = preg_replace('/[\x{200B}-\x{200F}\x{202A}-\x{202E}\x{2060}-\x{2069}\x{FEFF}]/u', '', $s);
    if (!preg_match('~^[a-zA-Z][a-zA-Z0-9+\-.]*://~', $s)) {
        $s = 'http://' . ltrim($s, '/');
    }
    $parts = @parse_url($s);
    if (!is_array($parts) || empty($parts['host'])) return '';
    $host = strtolower(trim($parts['host']));
    $port = isset($parts['port']) ? ':' . (int)$parts['port'] : '';
    return $host . $port;
}

function rpReplaceDomainHostInUrl(string $rawUrl, string $oldAuthority, string $newInput): array {
    $trimmedUrl = trim($rawUrl);
    if ($trimmedUrl === '') return ['changed' => false, 'url' => $rawUrl];
    $oldAuthLower = strtolower(trim($oldAuthority));
    $oldHostOnly  = explode(':', $oldAuthLower)[0];
    $newTrim = trim($newInput);
    $forcedScheme = null;
    if (preg_match('~^(https?)://~i', $newTrim, $mScheme)) {
        $forcedScheme = strtolower($mScheme[1]);
    }
    $newAuth = rpExtractDomainHost($newTrim);
    if ($oldHostOnly === '' || $newAuth === '') return ['changed' => false, 'url' => $rawUrl];
    $matchWithPort = (strpos($oldAuthLower, ':') !== false);
    $newHostOnly   = explode(':', $newAuth)[0];

    $pattern = '~(https?://)([^/\s:?#]+(?::\d+)?)(?=[:/?#\s]|$)~i';
    $changed = false;
    $updated = preg_replace_callback($pattern, function($m) use ($oldAuthLower, $oldHostOnly, $matchWithPort, $newAuth, $newHostOnly, $forcedScheme, &$changed) {
        $scheme  = $m[1];
        $curAuth = strtolower($m[2]);
        $curHost = explode(':', $curAuth)[0];
        $curPort = strpos($curAuth, ':') !== false ? ':' . explode(':', $curAuth)[1] : '';
        if ($matchWithPort) {
            if ($curAuth !== $oldAuthLower) return $m[0];
            $changed = true;
            $finalScheme = $forcedScheme ? ($forcedScheme . '://') : $scheme;
            return $finalScheme . $newAuth;
        } else {
            if ($curHost !== $oldHostOnly) return $m[0];
            $changed = true;
            $finalScheme = $forcedScheme ? ($forcedScheme . '://') : $scheme;
            $replacementAuth = (strpos($newAuth, ':') !== false) ? $newAuth : ($newHostOnly . $curPort);
            return $finalScheme . $replacementAuth;
        }
    }, $trimmedUrl);
    return ['changed' => $changed, 'url' => $changed ? (string)$updated : $rawUrl];
}

function rpGetOwnedDeviceIds(PDO $db, int $resellerId): array {
    $owned = [];
    $resellerIds = [$resellerId];
    try {
        $stSub = $db->prepare("SELECT id FROM resellers WHERE parent_reseller_id = ?");
        $stSub->execute([$resellerId]);
        foreach ($stSub->fetchAll(PDO::FETCH_COLUMN) as $sid) {
            $sidInt = (int)$sid;
            if ($sidInt > 0 && !in_array($sidInt, $resellerIds, true)) {
                $resellerIds[] = $sidInt;
            }
        }
    } catch (Throwable $e) {}

    $inPlaceholders = implode(',', array_fill(0, count($resellerIds), '?'));
    $dbs = [$db];
    $sec = getSecondaryPortalDbForReseller();
    if ($sec !== null) $dbs[] = $sec;

    foreach ($dbs as $pdo) {
        try {
            $st1 = $pdo->prepare("SELECT DISTINCT UPPER(TRIM(used_by_device)) FROM activation_cards WHERE reseller_id IN ($inPlaceholders) AND used_by_device IS NOT NULL AND TRIM(used_by_device) != ''");
            $st1->execute($resellerIds);
            foreach ($st1->fetchAll(PDO::FETCH_COLUMN) as $d) {
                $norm = rpNormalizeDevice((string)$d);
                if ($norm !== '') $owned[$norm] = true;
            }
        } catch (Throwable $e) {}
        try {
            $st2 = $pdo->prepare("SELECT DISTINCT UPPER(TRIM(target_device_id)) FROM reseller_transactions WHERE reseller_id IN ($inPlaceholders) AND target_device_id IS NOT NULL AND TRIM(target_device_id) != ''");
            $st2->execute($resellerIds);
            foreach ($st2->fetchAll(PDO::FETCH_COLUMN) as $d) {
                $norm = rpNormalizeDevice((string)$d);
                if ($norm !== '') $owned[$norm] = true;
            }
        } catch (Throwable $e) {}
        try {
            $st3 = $pdo->prepare("SELECT DISTINCT UPPER(TRIM(device_id)) FROM users WHERE reseller_id IN ($inPlaceholders) AND device_id IS NOT NULL AND TRIM(device_id) != ''");
            $st3->execute($resellerIds);
            foreach ($st3->fetchAll(PDO::FETCH_COLUMN) as $d) {
                $norm = rpNormalizeDevice((string)$d);
                if ($norm !== '') $owned[$norm] = true;
            }
        } catch (Throwable $e) {}
        try {
            $st4 = $pdo->prepare("
                SELECT DISTINCT UPPER(TRIM(r.redeemed_by_device))
                FROM card_redemptions r
                INNER JOIN activation_cards c ON c.code = r.code COLLATE NOCASE
                WHERE c.reseller_id IN ($inPlaceholders) AND r.redeemed_by_device IS NOT NULL AND TRIM(r.redeemed_by_device) != ''
            ");
            $st4->execute($resellerIds);
            foreach ($st4->fetchAll(PDO::FETCH_COLUMN) as $d) {
                $norm = rpNormalizeDevice((string)$d);
                if ($norm !== '') $owned[$norm] = true;
            }
        } catch (Throwable $e) {}
    }
    return array_keys($owned);
}

function rpCollectMyActiveDomains(PDO $db, int $resellerId): array {
    $ownedDevices = rpGetOwnedDeviceIds($db, $resellerId);
    if (empty($ownedDevices)) return [];
    $inPlaceholders = implode(',', array_fill(0, count($ownedDevices), '?'));
    $domainsMap = [];
    $seenRowKeys = [];

    $dbs = [$db];
    $sec = getSecondaryPortalDbForReseller();
    if ($sec !== null) $dbs[] = $sec;

    foreach ($dbs as $pdo) {
        try {
            $st = $pdo->prepare("SELECT device_id, playlist_id, server_name, server_url, m3u_url FROM customer_xtream_lists WHERE UPPER(device_id) IN ($inPlaceholders)");
            $st->execute($ownedDevices);
            while ($row = $st->fetch(PDO::FETCH_ASSOC)) {
                $devKey = strtoupper(trim((string)($row['device_id'] ?? '')));
                $plKey  = trim((string)($row['playlist_id'] ?? ''));
                $uKey   = $devKey . '|' . $plKey;
                if (isset($seenRowKeys[$uKey])) continue;
                $seenRowKeys[$uKey] = true;

                $srvPlain = rpDecryptSecret((string)($row['server_url'] ?? ''));
                $m3uPlain = rpDecryptSecret((string)($row['m3u_url'] ?? ''));
                $rawCandidate = $srvPlain !== '' ? $srvPlain : $m3uPlain;
                $h = rpExtractDomainHost($rawCandidate);
                if ($h !== '') {
                    $fullUrl = preg_match('~^https?://~i', $rawCandidate) ? (strtolower(parse_url($rawCandidate, PHP_URL_SCHEME) ?: 'http') . '://' . $h) : ('http://' . $h);
                    if (!isset($domainsMap[$h])) {
                        $domainsMap[$h] = [
                            'domain'          => $h,
                            'full_url'        => $fullUrl,
                            'playlists_count' => 0,
                            'devices'         => [],
                            'names'           => []
                        ];
                    }
                    $domainsMap[$h]['playlists_count']++;
                    $domainsMap[$h]['devices'][$devKey] = true;
                    $sName = trim((string)($row['server_name'] ?? ''));
                    if ($sName !== '' && stripos($sName, 'http') === false) {
                        $domainsMap[$h]['names'][$sName] = true;
                    }
                }
            }
        } catch (Throwable $e) {}
    }

    $result = [];
    foreach ($domainsMap as $h => $info) {
        $namesList = array_keys($info['names']);
        $result[] = [
            'domain'          => $info['domain'],
            'full_url'        => $info['full_url'],
            'server_name'     => !empty($namesList) ? implode(' • ', array_slice($namesList, 0, 2)) : 'سيرفر شاشاتي',
            'playlists_count' => $info['playlists_count'],
            'devices_count'   => count($info['devices'])
        ];
    }
    usort($result, function($a, $b) {
        if ($b['playlists_count'] !== $a['playlists_count']) return $b['playlists_count'] <=> $a['playlists_count'];
        return strcmp($a['domain'], $b['domain']);
    });
    return $result;
}

function rpBulkReplaceDomainInPdo(PDO $pdo, array $ownedDevices, string $oldDomain, string $newDomain): array {
    $stats = ['customer_lists_updated' => 0, 'favorites_updated' => 0, 'affected_devices' => []];
    if (empty($ownedDevices)) return $stats;
    ensureResellerPortalSchema($pdo);
    $inPlaceholders = implode(',', array_fill(0, count($ownedDevices), '?'));

    try {
        $st = $pdo->prepare("SELECT id, device_id, server_url, m3u_url FROM customer_xtream_lists WHERE UPPER(device_id) IN ($inPlaceholders)");
        $st->execute($ownedDevices);
        $rows = $st->fetchAll(PDO::FETCH_ASSOC);
        $up = $pdo->prepare("UPDATE customer_xtream_lists SET server_url = ?, m3u_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?");
        foreach ($rows as $row) {
            $srvStored = (string)($row['server_url'] ?? '');
            $m3uStored = (string)($row['m3u_url'] ?? '');
            $srvPlain  = rpDecryptSecret($srvStored);
            $m3uPlain  = rpDecryptSecret($m3uStored);

            $r1 = rpReplaceDomainHostInUrl($srvPlain, $oldDomain, $newDomain);
            $r2 = rpReplaceDomainHostInUrl($m3uPlain, $oldDomain, $newDomain);
            if ($r1['changed'] || $r2['changed']) {
                $newSrv = $r1['changed'] ? rpEncryptSecret($r1['url']) : $srvStored;
                $newM3u = $r2['changed'] ? ($r2['url'] !== '' ? rpEncryptSecret($r2['url']) : '') : $m3uStored;
                $up->execute([$newSrv, $newM3u, $row['id']]);
                $stats['customer_lists_updated']++;
                $devId = strtoupper(trim((string)($row['device_id'] ?? '')));
                if ($devId !== '') $stats['affected_devices'][$devId] = true;
            }
        }
    } catch (Throwable $e) {}

    try {
        $stFav = $pdo->prepare("SELECT device_id, favorites_json FROM device_favorites WHERE UPPER(device_id) IN ($inPlaceholders)");
        $stFav->execute($ownedDevices);
        $favRows = $stFav->fetchAll(PDO::FETCH_ASSOC);
        $upFav = $pdo->prepare("UPDATE device_favorites SET favorites_json = ?, updated_at = CURRENT_TIMESTAMP WHERE device_id = ?");
        foreach ($favRows as $fRow) {
            $jsonStr = (string)($fRow['favorites_json'] ?? '');
            if ($jsonStr === '' || $jsonStr === '[]') continue;
            $rFav = rpReplaceDomainHostInUrl($jsonStr, $oldDomain, $newDomain);
            if ($rFav['changed']) {
                $upFav->execute([$rFav['url'], $fRow['device_id']]);
                $stats['favorites_updated']++;
            }
        }
    } catch (Throwable $e) {}

    return $stats;
}


function rpUpdateDataTxtAndRecordMigration(PDO $db, int $resellerId, string $oldDomain, string $newDomain): int {
    $oldHost = rpExtractDomainHost($oldDomain);
    $newHost = rpExtractDomainHost($newDomain);
    $newNormUrl = trim($newDomain);
    if ($newNormUrl !== '' && !preg_match('~^https?://~i', $newNormUrl)) {
        $newNormUrl = 'http://' . ltrim($newNormUrl, '/');
    }
    $newNormUrl = rtrim($newNormUrl, '/');

    // 1. تحديث ملف data.txt
    $dataTxtUpdated = 0;
    $dataFile = __DIR__ . '/data.txt';
    if (is_file($dataFile)) {
        $lines = @file($dataFile, FILE_IGNORE_NEW_LINES) ?: [];
        $newLines = [];
        foreach ($lines as $ln) {
            $trimLn = trim($ln);
            if ($trimLn !== '' && $trimLn[0] !== '#') {
                if (strpos($trimLn, '|') !== false) {
                    [$nm, $u] = explode('|', $trimLn, 2);
                    $r = rpReplaceDomainHostInUrl(trim($u), $oldHost, $newDomain);
                    if ($r['changed']) {
                        $ln = trim($nm) . '|' . rtrim($r['url'], '/');
                        $dataTxtUpdated++;
                    }
                } elseif (strpos($trimLn, '=') !== false && !preg_match('~^https?://~i', explode('=', $trimLn, 2)[0])) {
                    [$nm, $u] = explode('=', $trimLn, 2);
                    $r = rpReplaceDomainHostInUrl(trim($u), $oldHost, $newDomain);
                    if ($r['changed']) {
                        $ln = trim($nm) . '|' . rtrim($r['url'], '/');
                        $dataTxtUpdated++;
                    }
                } else {
                    $r = rpReplaceDomainHostInUrl($trimLn, $oldHost, $newDomain);
                    if ($r['changed']) {
                        $ln = rtrim($r['url'], '/');
                        $dataTxtUpdated++;
                    }
                }
            }
            $newLines[] = $ln;
        }
        if ($dataTxtUpdated > 0) {
            @file_put_contents($dataFile, implode("\n", $newLines) . "\n");
        }
    }

    // 2. تسجيل التحويل في جدول domain_migrations في كلتا القاعدتين لضمان التحديث الإجباري لقاعدة البيانات المحلية للتطبيق عند إعادة إقلاع التطبيق
    if ($oldHost !== '' && $newHost !== '' && strcasecmp($oldHost, $newHost) !== 0) {
        $dbs = [$db];
        $sec = getSecondaryPortalDbForReseller();
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
                $pdo->prepare("UPDATE domain_migrations SET new_domain = ?, updated_at = datetime('now') WHERE LOWER(new_domain) = LOWER(?) OR LOWER(new_domain) = LOWER(?)")
                    ->execute([$newNormUrl, $oldHost, 'http://' . $oldHost]);
                $pdo->prepare("DELETE FROM domain_migrations WHERE LOWER(old_domain) = LOWER(?)")->execute([$oldHost]);
                $pdo->prepare("INSERT INTO domain_migrations (old_domain, new_domain, server_name, scope, reseller_id, updated_at) VALUES (?, ?, '', 'reseller', ?, datetime('now'))")
                    ->execute([$oldHost, $newNormUrl, $resellerId]);
            } catch (Throwable $e) {}
        }
    }
    return $dataTxtUpdated;
}

function rpPackages(): array {
    return [
        '1month'  => ['type' => '1month',  'days' => 30,  'points' => 1,  'label' => 'شهر واحد (30 يوم)',   'short' => 'شهر واحد',   'icon' => '📅', 'badge' => '1 نقطة'],
        '3months' => ['type' => '3months', 'days' => 90,  'points' => 3,  'label' => '3 أشهر (90 يوم)',     'short' => '3 أشهر',     'icon' => '🔥', 'badge' => '3 نقاط'],
        '6months' => ['type' => '6months', 'days' => 180, 'points' => 6,  'label' => '6 أشهر (180 يوم)',    'short' => '6 أشهر',     'icon' => '💎', 'badge' => '6 نقاط'],
        '1year'   => ['type' => '1year',   'days' => 365, 'points' => 12, 'label' => 'سنة كاملة (365 يوم)', 'short' => 'سنة كاملة', 'icon' => '👑', 'badge' => '12 نقطة'],
    ];
}

function rpNormalizeDevice(string $raw): string {
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

function rpGenerateUniqueCode(PDO $db): string {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for ($try = 0; $try < 60; $try++) {
        $p1 = '';
        $p2 = '';
        for ($i = 0; $i < 4; $i++) {
            $p1 .= $alphabet[random_int(0, strlen($alphabet) - 1)];
            $p2 .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }
        $code = $p1 . '-' . $p2;
        $st1 = $db->prepare("SELECT 1 FROM activation_cards WHERE code = ? COLLATE NOCASE LIMIT 1");
        $st1->execute([$code]);
        if ($st1->fetchColumn()) continue;
        $st2 = $db->prepare("SELECT 1 FROM card_redemptions WHERE code = ? COLLATE NOCASE LIMIT 1");
        $st2->execute([$code]);
        if ($st2->fetchColumn()) continue;
        return $code;
    }
    return substr(strtoupper(bin2hex(random_bytes(4))), 0, 4) . '-' . substr(strtoupper(bin2hex(random_bytes(4))), 0, 4);
}

function rpJsonOut(bool $success, array $payload = [], int $httpCode = 200): void {
    http_response_code($httpCode);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
    echo json_encode(array_merge(['success' => $success], $payload), JSON_UNESCAPED_UNICODE);
    exit;
}

function rpAuthenticate(PDO $db): ?array {
    $token = trim((string)($_REQUEST['reseller_token'] ?? $_SESSION['reseller_token'] ?? ''));
    if ($token === '') return null;
    $db->exec("DELETE FROM reseller_sessions WHERE expires_at <= datetime('now')");
    $stmt = $db->prepare("
        SELECT r.*, s.token AS session_token
        FROM reseller_sessions s
        JOIN resellers r ON r.id = s.reseller_id
        WHERE s.token = ? AND s.expires_at > datetime('now')
        LIMIT 1
    ");
    $stmt->execute([$token]);
    $reseller = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$reseller || (int)($reseller['is_active'] ?? 1) !== 1) {
        return null;
    }
    return $reseller;
}

/* =============================================================================
   3. معالج طلبات AJAX المباشر لبوابة الموزعين
   ============================================================================= */
$ajaxAction = trim((string)($_REQUEST['ajax_action'] ?? ''));
if ($ajaxAction !== '') {
    try {
        $db = getResellerDB();

        if ($ajaxAction === 'login') {
            $username = strtolower(trim((string)($_POST['username'] ?? '')));
            $password = trim((string)($_POST['password'] ?? ''));
            if ($username === '' || $password === '') {
                rpJsonOut(false, ['error' => '⚠️ يرجى إدخال اسم المستخدم وكلمة المرور'], 400);
            }
            $st = $db->prepare("SELECT * FROM resellers WHERE username = ? COLLATE NOCASE LIMIT 1");
            $st->execute([$username]);
            $reseller = $st->fetch(PDO::FETCH_ASSOC);
            if (!$reseller || !password_verify($password, (string)$reseller['password_hash'])) {
                usleep(350000);
                rpJsonOut(false, ['error' => '❌ اسم المستخدم أو كلمة المرور غير صحيحة'], 401);
            }
            if ((int)($reseller['is_active'] ?? 1) !== 1) {
                rpJsonOut(false, ['error' => '⛔ حساب الموزع الخاص بك موقوف حالياً. يرجى التواصل مع الإدارة.'], 403);
            }

            $token = bin2hex(random_bytes(32));
            $ttl = 86400 * 7;
            $db->prepare("INSERT INTO reseller_sessions (reseller_id, token, expires_at, ip_address, created_at) VALUES (?, ?, datetime('now', '+' || ? || ' seconds'), ?, datetime('now'))")
               ->execute([$reseller['id'], $token, $ttl, $_SERVER['REMOTE_ADDR'] ?? '']);
            $db->prepare("UPDATE resellers SET last_login_at = datetime('now') WHERE id = ?")->execute([$reseller['id']]);
            $_SESSION['reseller_token'] = $token;

            unset($reseller['password_hash'], $reseller['password_plain']);
            rpJsonOut(true, [
                'token'    => $token,
                'reseller' => $reseller,
                'message'  => '✅ تم تسجيل الدخول بنجاح'
            ]);
        }

        if ($ajaxAction === 'logout') {
            $token = trim((string)($_REQUEST['reseller_token'] ?? $_SESSION['reseller_token'] ?? ''));
            if ($token !== '') {
                $db->prepare("DELETE FROM reseller_sessions WHERE token = ?")->execute([$token]);
            }
            unset($_SESSION['reseller_token']);
            rpJsonOut(true, ['message' => 'تم تسجيل الخروج']);
        }

        $reseller = rpAuthenticate($db);
        if (!$reseller) {
            rpJsonOut(false, ['error' => 'انتهت الجلسة، يرجى تسجيل الدخول مجدداً', 'auth_expired' => true], 401);
        }
        $rid = (int)$reseller['id'];

        if ($ajaxAction === 'dashboard') {
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

            $subSt = $db->prepare("
                SELECT r.id, r.username, r.password_plain, r.display_name, r.phone,
                       r.points, r.total_points_received, r.total_points_spent,
                       r.total_cards_generated, r.is_active, r.notes,
                       r.last_login_at, r.created_at,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'unused') AS unused_cards_count,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'used') AS used_cards_count
                FROM resellers r
                WHERE r.parent_reseller_id = ?
                ORDER BY r.id DESC
            ");
            $subSt->execute([$rid]);
            $subResellers = $subSt->fetchAll(PDO::FETCH_ASSOC);

            $ownedDevices = rpGetOwnedDeviceIds($db, $rid);
            $myDomains    = rpCollectMyActiveDomains($db, $rid);

            unset($reseller['password_hash'], $reseller['password_plain'], $reseller['session_token']);
            rpJsonOut(true, [
                'reseller'            => $reseller,
                'stats'               => [
                    'points_balance'        => (int)$reseller['points'],
                    'total_points_received' => (int)$reseller['total_points_received'],
                    'total_points_spent'    => (int)$reseller['total_points_spent'],
                    'total_cards_generated' => (int)$reseller['total_cards_generated'],
                    'unused_cards'          => $unusedCount,
                    'used_cards'            => $usedCount,
                    'sub_resellers_count'   => count($subResellers),
                    'owned_devices_count'   => count($ownedDevices)
                ],
                'packages'            => array_values(rpPackages()),
                'cards'               => $cards,
                'transactions'        => $transactions,
                'sub_resellers'       => $subResellers,
                'owned_devices_count' => count($ownedDevices),
                'domains'             => $myDomains
            ]);
        }

        if ($ajaxAction === 'generate_cards') {
            $pkgKey   = trim((string)($_POST['package_type'] ?? '1month'));
            $quantity = min(50, max(1, (int)($_POST['quantity'] ?? 1)));
            $custNote = trim((string)($_POST['customer_note'] ?? ''));

            $catalog = rpPackages();
            if (!isset($catalog[$pkgKey])) {
                rpJsonOut(false, ['error' => 'الباقة المختارة غير صالحة'], 400);
            }
            $pkg       = $catalog[$pkgKey];
            $unitCost  = (int)$pkg['points'];
            $totalCost = $unitCost * $quantity;
            $days      = (int)$pkg['days'];

            $db->beginTransaction();
            try {
                $st = $db->prepare("SELECT points, display_name, username, is_active FROM resellers WHERE id = ? LIMIT 1");
                $st->execute([$rid]);
                $fresh = $st->fetch(PDO::FETCH_ASSOC);
                if (!$fresh || (int)$fresh['is_active'] !== 1) {
                    $db->rollBack();
                    rpJsonOut(false, ['error' => 'حساب الموزع غير نشط'], 403);
                }
                $currentBalance = (int)$fresh['points'];
                if ($currentBalance < $totalCost) {
                    $db->rollBack();
                    rpJsonOut(false, [
                        'error' => "⛔ رصيد النقاط غير كافٍ! رصيدك الحالي: ({$currentBalance} نقطة)، والتكلفة المطلوبة: ({$totalCost} نقطة). يرجى التواصل مع الإدارة لشحن رصيدك."
                    ], 400);
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
                    $code = rpGenerateUniqueCode($db);
                    $insCard->execute([
                        $code, $pkg['type'], $days, $batchId, $noteText,
                        'reseller:' . $fresh['username'], $rid, $fresh['display_name'], $unitCost
                    ]);
                    $runningBalance -= $unitCost;
                    $insTx->execute([
                        $rid, -$unitCost, $runningBalance, $pkg['type'], $days, $code,
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

                $db->commit();

                // مزامنة القسائم أيضاً مع قاعدة البوابة الثانوية إن وُجدت
                try {
                    $sec = getSecondaryPortalDbForReseller();
                    if ($sec !== null) {
                        ensureResellerPortalSchema($sec);
                        $secIns = $sec->prepare("INSERT OR IGNORE INTO activation_cards (code, card_type, duration_days, batch_id, status, notes, created_by, reseller_id, reseller_name, points_cost, created_at) VALUES (?, ?, ?, ?, 'unused', ?, ?, ?, ?, ?, datetime('now'))");
                        foreach ($generated as $gCode) {
                            $secIns->execute([$gCode, $pkg['type'], $days, $batchId, $noteText, 'reseller:' . $fresh['username'], $rid, $fresh['display_name'], $unitCost]);
                        }
                    }
                } catch (Throwable $e) {}

                rpJsonOut(true, [
                    'cards'           => $generated,
                    'count'           => count($generated),
                    'package_type'    => $pkg['type'],
                    'package_label'   => $pkg['label'],
                    'duration_days'   => $days,
                    'points_deducted' => $totalCost,
                    'new_balance'     => $newBalance,
                    'customer_note'   => $custNote,
                    'message'         => "✅ تم استخراج " . count($generated) . " قسيمة ({$pkg['label']}) بنجاح وخصم {$totalCost} نقطة!"
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                rpJsonOut(false, ['error' => 'حدث خطأ أثناء استخراج القسيمة: ' . $e->getMessage()], 500);
            }
        }

        if ($ajaxAction === 'check_device') {
            $deviceId = rpNormalizeDevice((string)($_REQUEST['device_id'] ?? ''));
            if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $deviceId)) {
                rpJsonOut(false, ['error' => '⚠️ صيغة رمز الشاشة غير صحيحة (مثال: DEV-A1B2-C3D4)'], 400);
            }
            $st = $db->prepare("SELECT * FROM users WHERE UPPER(device_id) = ? LIMIT 1");
            $st->execute([$deviceId]);
            $user = $st->fetch(PDO::FETCH_ASSOC);
            if (!$user) {
                rpJsonOut(true, [
                    'found'       => false,
                    'device_id'   => $deviceId,
                    'status_text' => 'شاشة جديدة (سيتم تسجيلها وتفعيلها فوراً عند التفعيل المباشر)',
                    'expire_date' => '—',
                    'days_left'   => 0
                ]);
            }
            $expRaw = trim((string)($user['expire_date'] ?? ''));
            $expTs  = $expRaw !== '' ? strtotime($expRaw . ' 23:59:59') : 0;
            $days   = $expTs > 0 ? (int)ceil(($expTs - time()) / 86400) : 0;
            $active = ((int)($user['is_active'] ?? 1) === 1) && $days > 0;
            rpJsonOut(true, [
                'found'       => true,
                'device_id'   => $deviceId,
                'device_name' => $user['device_name'] ?: 'شاشة مسجلة',
                'active'      => $active,
                'expire_date' => $expRaw ?: '—',
                'days_left'   => max(0, $days),
                'status_text' => $active ? "نشط (متبقي {$days} يوم — ينتهي {$expRaw})" : "منتهي الصلاحية ({$expRaw})"
            ]);
        }

        if ($ajaxAction === 'direct_activate') {
            $pkgKey   = trim((string)($_POST['package_type'] ?? '1month'));
            $deviceId = rpNormalizeDevice((string)($_POST['device_id'] ?? ''));
            $custName = trim((string)($_POST['customer_name'] ?? ''));

            if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $deviceId)) {
                rpJsonOut(false, ['error' => '⚠️ يرجى إدخال رمز الشاشة بالصيغة الصحيحة (مثال: DEV-A1B2-C3D4)'], 400);
            }
            $catalog = rpPackages();
            if (!isset($catalog[$pkgKey])) {
                rpJsonOut(false, ['error' => 'الباقة المختارة غير صالحة'], 400);
            }
            $pkg      = $catalog[$pkgKey];
            $unitCost = (int)$pkg['points'];
            $days     = (int)$pkg['days'];

            $db->beginTransaction();
            try {
                $st = $db->prepare("SELECT points, display_name, username, is_active FROM resellers WHERE id = ? LIMIT 1");
                $st->execute([$rid]);
                $fresh = $st->fetch(PDO::FETCH_ASSOC);
                if (!$fresh || (int)$fresh['is_active'] !== 1) {
                    $db->rollBack();
                    rpJsonOut(false, ['error' => 'حساب الموزع غير نشط'], 403);
                }
                $currentBalance = (int)$fresh['points'];
                if ($currentBalance < $unitCost) {
                    $db->rollBack();
                    rpJsonOut(false, [
                        'error' => "⛔ رصيد النقاط غير كافٍ! رصيدك الحالي: ({$currentBalance} نقطة)، والتكلفة المطلوبة: ({$unitCost} نقطة)."
                    ], 400);
                }

                $newBalance = $currentBalance - $unitCost;
                $code       = rpGenerateUniqueCode($db);
                $batchId    = 'DIRECT-' . strtoupper($fresh['username']) . '-' . date('YmdHis');
                $noteText   = "تفعيل مباشر من الموزع {$fresh['display_name']} ({$pkg['short']})" . ($custName !== '' ? " | {$custName}" : "");

                $db->prepare("
                    INSERT INTO activation_cards (code, card_type, duration_days, batch_id, status, used_at, used_by_device, notes, created_by, reseller_id, reseller_name, points_cost, created_at)
                    VALUES (?, ?, ?, ?, 'used', datetime('now'), ?, ?, ?, ?, ?, ?, datetime('now'))
                ")->execute([
                    $code, $pkg['type'], $days, $batchId, $deviceId, $noteText,
                    'reseller:' . $fresh['username'], $rid, $fresh['display_name'], $unitCost
                ]);
                $cardId = (int)$db->lastInsertId();

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
                $devNameFinal = $custName !== '' ? $custName : ($user['device_name'] ?? 'شاشة زبون');

                if ($user) {
                    $oldNotes = trim((string)($user['notes'] ?? ''));
                    $newNotes = $oldNotes === '' ? $noteText : ($oldNotes . ' | ' . $noteText);
                    $db->prepare("
                        UPDATE users
                        SET expire_date = ?, device_name = ?, is_active = 1, blocked_reason = NULL,
                            notes = ?, last_card_code = ?, last_activation_at = datetime('now'),
                            total_paid_days = COALESCE(total_paid_days, 0) + ?, trial_used = 1,
                            reseller_id = ?, updated_at = datetime('now'), last_seen = datetime('now')
                        WHERE id = ?
                    ")->execute([$newExpire, $devNameFinal, $newNotes, $code, $days, $rid, $user['id']]);
                } else {
                    $db->prepare("
                        INSERT INTO users (device_id, device_name, expire_date, is_active, notes, trial_used, last_card_code, last_activation_at, total_paid_days, reseller_id, created_at, updated_at, registration_source, last_seen)
                        VALUES (?, ?, ?, 1, ?, 1, ?, datetime('now'), ?, ?, datetime('now'), datetime('now'), 'reseller', datetime('now'))
                    ")->execute([$deviceId, $devNameFinal, $newExpire, $noteText, $code, $days, $rid]);
                }

                $db->prepare("
                    INSERT INTO card_redemptions (code, redeemed_by_device, redeemed_at, card_id, duration_days, new_expire, is_renewal, ip_address)
                    VALUES (?, ?, datetime('now'), ?, ?, ?, ?, ?)
                ")->execute([$code, $deviceId, $cardId, $days, $newExpire, $isRenewal ? 1 : 0, $_SERVER['REMOTE_ADDR'] ?? null]);

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

                $db->commit();

                try {
                    $sec = getSecondaryPortalDbForReseller();
                    if ($sec !== null) {
                        ensureResellerPortalSchema($sec);
                        $sec->prepare("
                            INSERT INTO users (device_id, device_name, expire_date, is_active, trial_used, notes, created_at, updated_at, last_seen)
                            VALUES (?, ?, ?, 1, 1, ?, datetime('now'), datetime('now'), datetime('now'))
                            ON CONFLICT(device_id) DO UPDATE SET
                                expire_date = excluded.expire_date,
                                device_name = excluded.device_name,
                                is_active = 1,
                                trial_used = 1,
                                notes = excluded.notes,
                                updated_at = datetime('now')
                        ")->execute([$deviceId, $devNameFinal, $newExpire, $noteText]);
                    }
                } catch (Throwable $e) {}

                rpJsonOut(true, [
                    'device_id'       => $deviceId,
                    'card_code'       => $code,
                    'package_label'   => $pkg['label'],
                    'added_days'      => $days,
                    'new_expire'      => $newExpire,
                    'points_deducted' => $unitCost,
                    'new_balance'     => $newBalance,
                    'is_renewal'      => $isRenewal,
                    'message'         => "✅ تم تفعيل وتجديد الشاشة ({$deviceId}) لمدة {$pkg['label']} بنجاح! صالحة لغاية {$newExpire}"
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                rpJsonOut(false, ['error' => 'حدث خطأ أثناء التفعيل المباشر: ' . $e->getMessage()], 500);
            }
        }


        if ($ajaxAction === 'list_my_domains') {
            $ownedDevices = rpGetOwnedDeviceIds($db, $rid);
            $domains      = rpCollectMyActiveDomains($db, $rid);
            rpJsonOut(true, [
                'owned_devices_count' => count($ownedDevices),
                'domains'             => $domains
            ]);
        }

        if ($ajaxAction === 'bulk_replace_domain') {
            $oldInput = trim((string)($_POST['old_domain'] ?? ''));
            $newInput = trim((string)($_POST['new_domain'] ?? ''));
            $oldHost  = rpExtractDomainHost($oldInput);
            $newHost  = rpExtractDomainHost($newInput);

            if ($oldHost === '' || $newHost === '') {
                rpJsonOut(false, ['error' => '⚠️ يرجى إدخال الدومين القديم المستهدف والدومين الجديد المقترح بشكل صحيح (مثال: old-host.com و new-host.com)'], 400);
            }
            if (strtolower($oldHost) === strtolower($newHost) && !preg_match('~^https?://~i', $newInput)) {
                rpJsonOut(false, ['error' => '⚠️ الدومين الجديد مطابق للدومين القديم'], 400);
            }

            $ownedDevices = rpGetOwnedDeviceIds($db, $rid);
            if (empty($ownedDevices)) {
                rpJsonOut(false, ['error' => '⚠️ لا توجد شاشات مفعّلة تابعة لحسابك حالياً لتغيير الدومين عليها.'], 400);
            }

            $primaryStats = rpBulkReplaceDomainInPdo($db, $ownedDevices, $oldHost, $newInput);
            $secStats = ['customer_lists_updated' => 0, 'favorites_updated' => 0, 'affected_devices' => []];
            try {
                $sec = getSecondaryPortalDbForReseller();
                if ($sec !== null) {
                    $secStats = rpBulkReplaceDomainInPdo($sec, $ownedDevices, $oldHost, $newInput);
                }
            } catch (Throwable $e) {}

            $dataTxtUpdated = rpUpdateDataTxtAndRecordMigration($db, $rid, $oldHost, $newInput);

            $listsUpdated = max($primaryStats['customer_lists_updated'], $secStats['customer_lists_updated']);
            $favsUpdated  = max($primaryStats['favorites_updated'], $secStats['favorites_updated']);
            $allAffected  = array_unique(array_merge(array_keys($primaryStats['affected_devices']), array_keys($secStats['affected_devices'])));
            $devicesCount = count($allAffected);

            rpJsonOut(true, [
                'data_txt_updated'       => $dataTxtUpdated,
                'old_domain'             => $oldHost,
                'new_domain'             => $newHost,
                'owned_devices_total'    => count($ownedDevices),
                'customer_lists_updated' => $listsUpdated,
                'devices_affected'       => $devicesCount,
                'favorites_updated'      => $favsUpdated,
                'domains'                => rpCollectMyActiveDomains($db, $rid),
                'message'                => "✅ تم استبدال الدومين ({$oldHost}) بالدومين الجديد ({$newHost}) للشاشات التابعة لك فقط! (تحديث {$listsUpdated} قائمة على {$devicesCount} شاشة من أصل " . count($ownedDevices) . " شاشة تابعة لك)"
            ]);
        }

        if ($ajaxAction === 'list_sub_resellers') {
            $st = $db->prepare("
                SELECT r.id, r.username, r.password_plain, r.display_name, r.phone,
                       r.points, r.total_points_received, r.total_points_spent,
                       r.total_cards_generated, r.is_active, r.notes,
                       r.last_login_at, r.created_at,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'unused') AS unused_cards_count,
                       (SELECT COUNT(*) FROM activation_cards c WHERE c.reseller_id = r.id AND c.status = 'used') AS used_cards_count
                FROM resellers r
                WHERE r.parent_reseller_id = ?
                ORDER BY r.id DESC
            ");
            $st->execute([$rid]);
            rpJsonOut(true, ['sub_resellers' => $st->fetchAll(PDO::FETCH_ASSOC)]);
        }

        if ($ajaxAction === 'add_sub_reseller') {
            $username    = strtolower(trim((string)($_POST['username'] ?? '')));
            $password    = trim((string)($_POST['password'] ?? ''));
            $displayName = trim((string)($_POST['display_name'] ?? ''));
            $phone       = trim((string)($_POST['phone'] ?? ''));
            $initialPts  = max(0, (int)($_POST['initial_points'] ?? 0));
            $notes       = trim((string)($_POST['notes'] ?? ''));

            if ($username === '' || !preg_match('/^[a-z0-9_.\-]{3,32}$/', $username)) {
                rpJsonOut(false, ['error' => '⚠️ اسم المستخدم للموزع الفرعي يجب أن يتكون من أحرف إنجليزية أو أرقام (3 إلى 32 حرفاً بدون مسافات)'], 400);
            }
            if (strlen($password) < 4) {
                rpJsonOut(false, ['error' => '⚠️ كلمة المرور للموزع الفرعي يجب أن تكون 4 أحرف أو أرقام على الأقل'], 400);
            }
            if ($displayName === '') $displayName = $username;

            $db->beginTransaction();
            try {
                $parentSt = $db->prepare("SELECT id, username, display_name, points, is_active FROM resellers WHERE id = ? LIMIT 1");
                $parentSt->execute([$rid]);
                $parentRow = $parentSt->fetch(PDO::FETCH_ASSOC);
                if (!$parentRow || (int)$parentRow['is_active'] !== 1) {
                    $db->rollBack();
                    rpJsonOut(false, ['error' => 'حساب الموزع غير نشط'], 403);
                }

                $dupSt = $db->prepare("SELECT id FROM resellers WHERE username = ? COLLATE NOCASE LIMIT 1");
                $dupSt->execute([$username]);
                if ($dupSt->fetchColumn()) {
                    $db->rollBack();
                    rpJsonOut(false, ['error' => '⚠️ اسم المستخدم هذا مستخدم بالفعل، اختر اسماً آخر'], 409);
                }

                $parentBalance = (int)$parentRow['points'];
                if ($initialPts > 0 && $parentBalance < $initialPts) {
                    $db->rollBack();
                    rpJsonOut(false, ['error' => "⛔ رصيد نقاطك الحالي ({$parentBalance} نقطة) غير كافٍ لتحويل ({$initialPts} نقطة) للموزع الفرعي"], 400);
                }

                $newParentBalance = $parentBalance - $initialPts;
                $passHash = password_hash($password, PASSWORD_DEFAULT);

                $db->prepare("
                    INSERT INTO resellers (username, password_hash, password_plain, display_name, phone, points, total_points_received, total_points_spent, total_cards_generated, is_active, notes, parent_reseller_id, parent_reseller_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 1, ?, ?, ?, datetime('now'), datetime('now'))
                ")->execute([
                    $username, $passHash, $password, $displayName, $phone,
                    $initialPts, $initialPts, $notes,
                    $rid, $parentRow['display_name']
                ]);
                $subId = (int)$db->lastInsertId();

                if ($initialPts > 0) {
                    $db->prepare("UPDATE resellers SET points = ?, total_points_spent = total_points_spent + ?, updated_at = datetime('now') WHERE id = ?")
                       ->execute([$newParentBalance, $initialPts, $rid]);

                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'sub_reseller_transfer_out', ?, ?, ?, datetime('now'))
                    ")->execute([
                        $rid, -$initialPts, $newParentBalance,
                        "تحويل رصيد ابتدائي ({$initialPts} نقطة) لفتح حساب الموزع الفرعي: {$displayName} (@{$username})"
                    ]);

                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'sub_reseller_transfer_in', ?, ?, ?, datetime('now'))
                    ")->execute([
                        $subId, $initialPts, $initialPts,
                        "رصيد افتتاحي محوّل من الموزع الرئيسي: {$parentRow['display_name']}"
                    ]);
                }

                $db->commit();
                rpJsonOut(true, [
                    'sub_reseller_id'    => $subId,
                    'new_parent_balance' => $newParentBalance,
                    'message'            => "✅ تم فتح حساب الموزع الفرعي ({$displayName}) بنجاح" . ($initialPts > 0 ? " وتحويل {$initialPts} نقطة من رصيدك إليه!" : "!")
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                rpJsonOut(false, ['error' => 'حدث خطأ أثناء إنشاء الموزع الفرعي: ' . $e->getMessage()], 500);
            }
        }

        if ($ajaxAction === 'transfer_sub_points') {
            $subId  = (int)($_POST['sub_reseller_id'] ?? 0);
            $mode   = strtolower(trim((string)($_POST['mode'] ?? 'add'))); // add | deduct
            $amount = max(1, (int)($_POST['points'] ?? 0));
            $reason = trim((string)($_POST['notes'] ?? ''));

            if ($subId <= 0 || $amount <= 0) {
                rpJsonOut(false, ['error' => '⚠️ يرجى تحديد الموزع الفرعي وعدد النقاط بشكل صحيح'], 400);
            }

            $db->beginTransaction();
            try {
                $pSt = $db->prepare("SELECT id, username, display_name, points, is_active FROM resellers WHERE id = ? LIMIT 1");
                $pSt->execute([$rid]);
                $parentRow = $pSt->fetch(PDO::FETCH_ASSOC);

                $sSt = $db->prepare("SELECT * FROM resellers WHERE id = ? AND parent_reseller_id = ? LIMIT 1");
                $sSt->execute([$subId, $rid]);
                $subRow = $sSt->fetch(PDO::FETCH_ASSOC);

                if (!$subRow) {
                    $db->rollBack();
                    rpJsonOut(false, ['error' => '⛔ هذا الموزع الفرعي غير موجود أو غير تابع لحسابك'], 404);
                }

                $parentPts = (int)$parentRow['points'];
                $subPts    = (int)$subRow['points'];

                if ($mode === 'deduct') {
                    if ($subPts < $amount) {
                        $db->rollBack();
                        rpJsonOut(false, ['error' => "⛔ رصيد الموزع الفرعي الحالي ({$subPts} نقطة) أقل من النقاط المطلوب استرجاعها ({$amount} نقطة)"], 400);
                    }
                    $newParentPts = $parentPts + $amount;
                    $newSubPts    = $subPts - $amount;

                    $db->prepare("UPDATE resellers SET points = ?, updated_at = datetime('now') WHERE id = ?")->execute([$newParentPts, $rid]);
                    $db->prepare("UPDATE resellers SET points = ?, updated_at = datetime('now') WHERE id = ?")->execute([$newSubPts, $subId]);

                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'sub_reseller_reclaim_in', ?, ?, ?, datetime('now'))
                    ")->execute([
                        $rid, $amount, $newParentPts,
                        $reason !== '' ? "استرجاع نقاط من الموزع الفرعي {$subRow['display_name']}: {$reason}" : "استرجاع ({$amount} نقطة) من الموزع الفرعي {$subRow['display_name']}"
                    ]);

                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'sub_reseller_reclaim_out', ?, ?, ?, datetime('now'))
                    ")->execute([
                        $subId, -$amount, $newSubPts,
                        $reason !== '' ? "خصم نقاط بواسطة الموزع الرئيسي {$parentRow['display_name']}: {$reason}" : "سحب ({$amount} نقطة) إلى الموزع الرئيسي {$parentRow['display_name']}"
                    ]);
                } else {
                    if ($parentPts < $amount) {
                        $db->rollBack();
                        rpJsonOut(false, ['error' => "⛔ رصيدك الحالي ({$parentPts} نقطة) غير كافٍ لتحويل ({$amount} نقطة) للموزع الفرعي"], 400);
                    }
                    $newParentPts = $parentPts - $amount;
                    $newSubPts    = $subPts + $amount;

                    $db->prepare("UPDATE resellers SET points = ?, total_points_spent = total_points_spent + ?, updated_at = datetime('now') WHERE id = ?")->execute([$newParentPts, $amount, $rid]);
                    $db->prepare("UPDATE resellers SET points = ?, total_points_received = total_points_received + ?, updated_at = datetime('now') WHERE id = ?")->execute([$newSubPts, $amount, $subId]);

                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'sub_reseller_transfer_out', ?, ?, ?, datetime('now'))
                    ")->execute([
                        $rid, -$amount, $newParentPts,
                        $reason !== '' ? "تحويل نقاط للموزع الفرعي {$subRow['display_name']}: {$reason}" : "تحويل ({$amount} نقطة) إلى الموزع الفرعي {$subRow['display_name']}"
                    ]);

                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'sub_reseller_transfer_in', ?, ?, ?, datetime('now'))
                    ")->execute([
                        $subId, $amount, $newSubPts,
                        $reason !== '' ? "شحن نقاط من الموزع الرئيسي {$parentRow['display_name']}: {$reason}" : "شحن ({$amount} نقطة) من الموزع الرئيسي {$parentRow['display_name']}"
                    ]);
                }

                $db->commit();
                rpJsonOut(true, [
                    'new_parent_balance' => $newParentPts,
                    'new_sub_balance'    => $newSubPts,
                    'message'            => $mode === 'deduct'
                        ? "✅ تم استرجاع {$amount} نقطة من ({$subRow['display_name']}) إلى رصيدك! رصيدك الآن: {$newParentPts} نقطة"
                        : "✅ تم تحويل {$amount} نقطة إلى ({$subRow['display_name']}) بنجاح! رصيده الآن: {$newSubPts} نقطة"
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                rpJsonOut(false, ['error' => 'حدث خطأ أثناء تحويل النقاط: ' . $e->getMessage()], 500);
            }
        }

        if ($ajaxAction === 'update_sub_reseller') {
            $subId       = (int)($_POST['sub_reseller_id'] ?? 0);
            $displayName = trim((string)($_POST['display_name'] ?? ''));
            $phone       = trim((string)($_POST['phone'] ?? ''));
            $newPass     = trim((string)($_POST['password'] ?? ''));
            $isActive    = isset($_POST['is_active']) ? (int)$_POST['is_active'] : 1;
            $notes       = trim((string)($_POST['notes'] ?? ''));

            $sSt = $db->prepare("SELECT * FROM resellers WHERE id = ? AND parent_reseller_id = ? LIMIT 1");
            $sSt->execute([$subId, $rid]);
            $subRow = $sSt->fetch(PDO::FETCH_ASSOC);
            if (!$subRow) {
                rpJsonOut(false, ['error' => '⛔ الموزع الفرعي غير موجود أو غير تابع لحسابك'], 404);
            }
            if ($displayName === '') $displayName = $subRow['display_name'];

            if ($newPass !== '') {
                if (strlen($newPass) < 4) {
                    rpJsonOut(false, ['error' => 'كلمة المرور الجديدة يجب أن تكون 4 أحرف أو أرقام على الأقل'], 400);
                }
                $hash = password_hash($newPass, PASSWORD_DEFAULT);
                $db->prepare("UPDATE resellers SET display_name = ?, phone = ?, password_hash = ?, password_plain = ?, is_active = ?, notes = ?, updated_at = datetime('now') WHERE id = ? AND parent_reseller_id = ?")
                   ->execute([$displayName, $phone, $hash, $newPass, $isActive ? 1 : 0, $notes, $subId, $rid]);
            } else {
                $db->prepare("UPDATE resellers SET display_name = ?, phone = ?, is_active = ?, notes = ?, updated_at = datetime('now') WHERE id = ? AND parent_reseller_id = ?")
                   ->execute([$displayName, $phone, $isActive ? 1 : 0, $notes, $subId, $rid]);
            }
            if (!$isActive) {
                $db->prepare("DELETE FROM reseller_sessions WHERE reseller_id = ?")->execute([$subId]);
            }
            rpJsonOut(true, ['message' => "✅ تم تحديث بيانات الموزع الفرعي ({$displayName}) بنجاح"]);
        }

        if ($ajaxAction === 'delete_sub_reseller') {
            $subId = (int)($_POST['sub_reseller_id'] ?? 0);
            $sSt = $db->prepare("SELECT * FROM resellers WHERE id = ? AND parent_reseller_id = ? LIMIT 1");
            $sSt->execute([$subId, $rid]);
            $subRow = $sSt->fetch(PDO::FETCH_ASSOC);
            if (!$subRow) {
                rpJsonOut(false, ['error' => '⛔ الموزع الفرعي غير موجود أو غير تابع لحسابك'], 404);
            }

            $db->beginTransaction();
            try {
                $reclaimPts = max(0, (int)($subRow['points'] ?? 0));
                if ($reclaimPts > 0) {
                    $db->prepare("UPDATE resellers SET points = points + ?, updated_at = datetime('now') WHERE id = ?")->execute([$reclaimPts, $rid]);
                    $newParentPts = (int)$db->query("SELECT points FROM resellers WHERE id = $rid")->fetchColumn();
                    $db->prepare("
                        INSERT INTO reseller_transactions (reseller_id, type, points_delta, balance_after, notes, created_at)
                        VALUES (?, 'sub_reseller_reclaim_in', ?, ?, ?, datetime('now'))
                    ")->execute([
                        $rid, $reclaimPts, $newParentPts,
                        "استرداد الرصيد المتبقي ({$reclaimPts} نقطة) تلقائياً عند حذف الموزع الفرعي {$subRow['display_name']}"
                    ]);
                }
                $db->prepare("DELETE FROM reseller_sessions WHERE reseller_id = ?")->execute([$subId]);
                $db->prepare("DELETE FROM resellers WHERE id = ? AND parent_reseller_id = ?")->execute([$subId, $rid]);
                $db->commit();
                rpJsonOut(true, [
                    'reclaimed_points' => $reclaimPts,
                    'message'          => "🗑 تم حذف الموزع الفرعي ({$subRow['display_name']})" . ($reclaimPts > 0 ? " واسترجاع رصيده المتبقي ({$reclaimPts} نقطة) إلى حسابك!" : " بنجاح")
                ]);
            } catch (Throwable $e) {
                if ($db->inTransaction()) $db->rollBack();
                rpJsonOut(false, ['error' => 'حدث خطأ أثناء حذف الموزع الفرعي: ' . $e->getMessage()], 500);
            }
        }

        if ($ajaxAction === 'change_password') {
            $oldPass = trim((string)($_POST['old_password'] ?? ''));
            $newPass = trim((string)($_POST['new_password'] ?? ''));
            if (strlen($newPass) < 4) {
                rpJsonOut(false, ['error' => 'كلمة المرور الجديدة يجب أن تكون 4 أحرف أو أرقام على الأقل'], 400);
            }
            if (!password_verify($oldPass, (string)$reseller['password_hash'])) {
                rpJsonOut(false, ['error' => '❌ كلمة المرور الحالية غير صحيحة'], 400);
            }
            $hash = password_hash($newPass, PASSWORD_DEFAULT);
            $db->prepare("UPDATE resellers SET password_hash = ?, password_plain = ?, updated_at = datetime('now') WHERE id = ?")
               ->execute([$hash, $newPass, $rid]);
            rpJsonOut(true, ['message' => '✅ تم تغيير كلمة المرور بنجاح']);
        }

        rpJsonOut(false, ['error' => 'إجراء غير معروف'], 404);
    } catch (Throwable $e) {
        rpJsonOut(false, ['error' => 'خطأ في الخادم: ' . $e->getMessage()], 500);
    }
}
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>LIVE PRO — فضاء الموزعين الاحترافي</title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>💎</text></svg>">
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700;800;900&display=swap" rel="stylesheet">
<style>
:root {
  --bg: #070b14;
  --sf: #111827;
  --sf2: #1e293b;
  --bd: #283548;
  --tx: #f8fafc;
  --txd: #94a3b8;
  --gold: #f59e0b;
  --gold-h: #fbbf24;
  --gold-bg: rgba(245, 158, 11, 0.14);
  --ok: #10b981;
  --ok-bg: rgba(16, 185, 129, 0.14);
  --dn: #ef4444;
  --dn-bg: rgba(239, 68, 68, 0.15);
  --inf: #38bdf8;
  --inf-bg: rgba(56, 189, 248, 0.14);
  --purp: #8b5cf6;
  --transition: 0.22s ease;
}
* { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
body {
  font-family: 'Cairo', sans-serif;
  background: radial-gradient(circle at 50% 0%, #172036 0%, var(--bg) 70%);
  color: var(--tx);
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
}

/* Header */
header {
  background: rgba(17, 24, 39, 0.92);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--bd);
  padding: 12px 24px;
  position: sticky;
  top: 0;
  z-index: 100;
}
.header-inner {
  max-width: 1320px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-logo {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--gold), #b45309);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  box-shadow: 0 4px 16px rgba(245, 158, 11, 0.35);
}
.brand-title { font-size: 19px; font-weight: 900; color: #fff; line-height: 1.2; }
.brand-sub { font-size: 12px; color: var(--gold-h); font-weight: 700; }

.user-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.points-pill {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.24), rgba(245, 158, 11, 0.08));
  border: 1.5px solid var(--gold);
  color: var(--gold-h);
  padding: 6px 16px;
  border-radius: 999px;
  font-weight: 900;
  font-size: 14px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  box-shadow: 0 0 20px rgba(245, 158, 11, 0.15);
}
.reseller-badge {
  background: var(--sf2);
  border: 1px solid var(--bd);
  color: var(--tx);
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 800;
}

/* Containers */
.container {
  max-width: 1320px;
  margin: 0 auto;
  padding: 24px 18px 60px;
  width: 100%;
  flex: 1;
}

/* Login Card */
.login-wrap {
  min-height: calc(100vh - 90px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.login-card {
  background: linear-gradient(180deg, rgba(30, 41, 59, 0.95), rgba(15, 23, 42, 0.98));
  border: 1.5px solid var(--bd);
  border-radius: 22px;
  padding: 34px 28px;
  width: 100%;
  max-width: 440px;
  box-shadow: 0 20px 55px rgba(0, 0, 0, 0.65);
  text-align: center;
}
.login-card h1 { font-size: 22px; font-weight: 900; color: var(--gold); margin-bottom: 6px; }
.login-card p { font-size: 13.5px; color: var(--txd); margin-bottom: 22px; }

/* Stats Grid */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
.stat-card {
  background: var(--sf);
  border: 1px solid var(--bd);
  border-radius: 16px;
  padding: 20px 18px;
  display: flex;
  align-items: center;
  gap: 16px;
  position: relative;
  overflow: hidden;
  transition: var(--transition);
}
.stat-card:hover { transform: translateY(-2px); border-color: var(--gold); }
.stat-card::before {
  content: '';
  position: absolute;
  top: 0; right: 0; left: 0;
  height: 3px;
  background: var(--gold);
}
.stat-card.ok::before { background: var(--ok); }
.stat-card.inf::before { background: var(--inf); }
.stat-card.purp::before { background: var(--purp); }
.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--gold-bg);
  color: var(--gold);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  flex-shrink: 0;
}
.stat-card.ok .stat-icon { background: var(--ok-bg); color: var(--ok); }
.stat-card.inf .stat-icon { background: var(--inf-bg); color: var(--inf); }
.stat-val { font-size: 26px; font-weight: 900; color: #fff; line-height: 1.1; }
.stat-lbl { font-size: 12.5px; color: var(--txd); font-weight: 700; margin-top: 3px; }

/* Main Grid Layout */
.work-grid {
  display: grid;
  grid-template-columns: 1.25fr 1fr;
  gap: 20px;
  margin-bottom: 24px;
}
@media (max-width: 960px) {
  .work-grid { grid-template-columns: 1fr; }
}

.card {
  background: var(--sf);
  border: 1px solid var(--bd);
  border-radius: 18px;
  overflow: hidden;
  margin-bottom: 22px;
  box-shadow: 0 8px 25px rgba(0, 0, 0, 0.25);
}
.card-header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--bd);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  background: rgba(255, 255, 255, 0.02);
}
.card-title { font-size: 17px; font-weight: 900; color: var(--gold); display: flex; align-items: center; gap: 8px; }
.card-body { padding: 20px; }

/* Package Selector Grid */
.pkg-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 18px;
}
@media (max-width: 520px) {
  .pkg-grid { grid-template-columns: 1fr; }
}
.pkg-card {
  background: var(--bg);
  border: 2px solid var(--bd);
  border-radius: 14px;
  padding: 15px;
  cursor: pointer;
  transition: var(--transition);
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  user-select: none;
}
.pkg-card:hover { border-color: var(--gold); background: rgba(245, 158, 11, 0.05); }
.pkg-card.selected {
  border-color: var(--gold);
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.16), rgba(16, 185, 129, 0.06));
  box-shadow: 0 6px 20px rgba(245, 158, 11, 0.18);
}
.pkg-top { display: flex; align-items: center; justify-content: space-between; }
.pkg-name { font-size: 15.5px; font-weight: 900; color: #fff; }
.pkg-pts {
  background: var(--gold);
  color: #000;
  font-weight: 900;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
}
.pkg-days { font-size: 12.5px; color: var(--txd); font-weight: 700; }

/* Forms & Buttons */
.form-group { margin-bottom: 15px; text-align: right; }
.form-group label { display: block; margin-bottom: 6px; font-size: 13px; font-weight: 800; color: var(--txd); }
.form-control {
  width: 100%;
  padding: 12px 15px;
  background: var(--bg);
  border: 1.5px solid var(--bd);
  border-radius: 11px;
  color: var(--tx);
  font-family: inherit;
  font-size: 14.5px;
  transition: var(--transition);
}
.form-control:focus { outline: none; border-color: var(--gold); box-shadow: 0 0 0 3px var(--gold-bg); }
.ltr-input {
  direction: ltr !important;
  unicode-bidi: isolate !important;
  text-align: center !important;
  font-family: monospace !important;
  font-size: 17px !important;
  font-weight: 900 !important;
  letter-spacing: 1.5px !important;
  text-transform: uppercase;
}

.btn {
  padding: 11px 18px;
  border-radius: 11px;
  font-weight: 800;
  cursor: pointer;
  border: none;
  font-family: inherit;
  transition: var(--transition);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  white-space: nowrap;
}
.btn.primary { background: linear-gradient(135deg, var(--gold), #d97706); color: #000; }
.btn.primary:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(245, 158, 11, 0.35); }
.btn.success { background: linear-gradient(135deg, var(--ok), #059669); color: #fff; }
.btn.success:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(16, 185, 129, 0.35); }
.btn.outline { background: transparent; border: 1.5px solid var(--bd); color: var(--tx); }
.btn.outline:hover { border-color: var(--gold); color: var(--gold); background: var(--gold-bg); }
.btn.danger { background: var(--dn-bg); color: var(--dn); border: 1px solid rgba(239, 68, 68, 0.35); }
.btn.danger:hover { background: var(--dn); color: #fff; }
.btn.sm { padding: 7px 12px; font-size: 12.5px; border-radius: 8px; }
.btn:disabled { opacity: 0.55; cursor: not-allowed; transform: none !important; }

/* Strict LTR Voucher Code Box (Never Scrambles!) */
.voucher-box {
  font-family: 'Courier New', Courier, monospace;
  direction: ltr !important;
  unicode-bidi: isolate !important;
  text-align: center;
  background: #050811;
  border: 2px dashed var(--gold);
  color: var(--gold-h);
  padding: 14px 18px;
  border-radius: 14px;
  font-size: 24px;
  font-weight: 900;
  letter-spacing: 3px;
  user-select: all;
  margin: 10px 0;
  box-shadow: inset 0 0 20px rgba(245, 158, 11, 0.1);
}
.code-chip {
  font-family: 'Courier New', Courier, monospace;
  direction: ltr !important;
  unicode-bidi: isolate !important;
  display: inline-block;
  text-align: center;
  background: var(--bg);
  border: 1px solid var(--gold);
  color: var(--gold-h);
  padding: 4px 10px;
  border-radius: 7px;
  font-size: 14px;
  font-weight: 900;
  letter-spacing: 1.4px;
  cursor: pointer;
}
.code-chip:hover { background: var(--gold); color: #000; }

/* Tables & Tabs */
.sub-tabs { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.sub-tab {
  padding: 9px 18px;
  border-radius: 10px;
  background: var(--bg);
  border: 1px solid var(--bd);
  color: var(--txd);
  font-family: inherit;
  font-weight: 800;
  font-size: 13.5px;
  cursor: pointer;
  transition: var(--transition);
}
.sub-tab.active { background: var(--gold); color: #000; border-color: var(--gold); }

.toolbar { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; }
.table-responsive { overflow-x: auto; border: 1px solid var(--bd); border-radius: 12px; }
table { width: 100%; border-collapse: collapse; font-size: 13.5px; white-space: nowrap; }
th { background: var(--bg); color: var(--txd); font-weight: 800; padding: 13px 14px; text-align: right; border-bottom: 1px solid var(--bd); }
td { padding: 12px 14px; border-bottom: 1px solid var(--bd); vertical-align: middle; }
tr:last-child td { border-bottom: none; }
tr:hover td { background: rgba(255, 255, 255, 0.025); }

.badge { display: inline-flex; align-items: center; padding: 4px 10px; border-radius: 999px; font-size: 11.5px; font-weight: 800; }
.badge.ok { background: var(--ok-bg); color: var(--ok); }
.badge.dn { background: var(--dn-bg); color: var(--dn); }
.badge.warn { background: var(--gold-bg); color: var(--gold-h); }
.badge.inf { background: var(--inf-bg); color: var(--inf); }

/* Modals */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(3, 6, 14, 0.82);
  backdrop-filter: blur(6px);
  z-index: 1000;
  display: none;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.modal-overlay.active { display: flex; }
.modal-box {
  background: var(--sf);
  border: 1.5px solid var(--gold);
  border-radius: 20px;
  width: 100%;
  max-width: 540px;
  max-height: 90vh;
  overflow-y: auto;
  padding: 24px 20px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.8);
}

.toast {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%) translateY(20px);
  background: var(--sf);
  border: 1.5px solid var(--gold);
  color: #fff;
  padding: 12px 22px;
  border-radius: 12px;
  font-weight: 800;
  font-size: 14px;
  opacity: 0;
  pointer-events: none;
  transition: 0.28s;
  z-index: 9999;
  box-shadow: 0 12px 35px rgba(0, 0, 0, 0.65);
  text-align: center;
}
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
.toast.success { border-color: var(--ok); background: #064e3b; }
.toast.error { border-color: var(--dn); background: #7f1d1d; }
</style>
</head>
<body>

<header id="topHeader" style="display:none">
  <div class="header-inner">
    <div class="brand">
      <div class="brand-logo">💎</div>
      <div>
        <div class="brand-title">LIVE PRO — لوحة الموزع المعتمدة</div>
        <div class="brand-sub" id="hdrResellerName">—</div>
      </div>
    </div>
    <div class="user-bar">
      <div class="points-pill">💎 الرصيد: <span id="hdrPoints">0</span> نقطة</div>
      <button class="btn outline sm" onclick="loadDashboard(true)">🔄 تحديث</button>
      <button class="btn outline sm" onclick="openModal('passModal')">🔑 كلمة المرور</button>
      <button class="btn danger sm" onclick="doLogout()">🚪 خروج</button>
    </div>
  </div>
</header>

<!-- شاشة تسجيل الدخول للموزع -->
<div id="loginView" class="login-wrap">
  <div class="login-card">
    <div style="width:66px;height:66px;border-radius:18px;background:linear-gradient(135deg,var(--gold),#b45309);display:flex;align-items:center;justify-content:center;font-size:32px;margin:0 auto 14px;box-shadow:0 8px 25px rgba(245,158,11,0.35)">💼</div>
    <h1>فضاء الموزعين — LIVE PRO</h1>
    <p>سجّل دخولك لاستخراج قسائم التفعيل الفورية وإدارة اشتراكات زبائنك</p>
    <form onsubmit="handleLogin(event)" autocomplete="on">
      <div class="form-group">
        <label>👤 اسم المستخدم (Username)</label>
        <input type="text" id="loginUser" class="form-control" dir="ltr" style="text-align:center;font-weight:800" placeholder="Username" required autofocus>
      </div>
      <div class="form-group">
        <label>🔒 كلمة المرور (Password)</label>
        <input type="password" id="loginPass" class="form-control" dir="ltr" style="text-align:center;font-weight:800" placeholder="••••••••" required>
      </div>
      <button type="submit" id="btnLogin" class="btn primary" style="width:100%;padding:14px;font-size:15.5px;margin-top:6px">🚀 دخول إلى لوحة الموزع</button>
    </form>
    <div style="margin-top:22px;padding-top:16px;border-top:1px solid var(--bd);font-size:12.5px;color:var(--txd)">
      لفتح حساب موزع معتمد أو شحن رصيد النقاط:<br>
      <strong style="color:var(--gold-h)">💬 تواصل مع الإدارة لشحن رصيد النقاط</strong>
    </div>
  </div>
</div>

<!-- لوحة تحكم الموزع الرئيسية -->
<div id="dashboardView" class="container" style="display:none">

  <!-- بطاقات الإحصائيات -->
  <div class="stats-grid">
    <div class="stat-card">
      <div class="stat-icon">💎</div>
      <div>
        <div class="stat-val" id="stBalance" style="color:var(--gold-h)">0</div>
        <div class="stat-lbl">رصيد النقاط الحالي</div>
      </div>
    </div>
    <div class="stat-card inf">
      <div class="stat-icon">🎫</div>
      <div>
        <div class="stat-val" id="stTotalCards">0</div>
        <div class="stat-lbl">إجمالي القسائم المستخرجة</div>
      </div>
    </div>
    <div class="stat-card ok">
      <div class="stat-icon">🟢</div>
      <div>
        <div class="stat-val" id="stUnusedCards">0</div>
        <div class="stat-lbl">قسائم متاحة (غير مستخدمة)</div>
      </div>
    </div>
    <div class="stat-card purp">
      <div class="stat-icon">✅</div>
      <div>
        <div class="stat-val" id="stUsedCards">0</div>
        <div class="stat-lbl">قسائم مفعّلة على الشاشات</div>
      </div>
    </div>
  </div>

  <!-- قسم استخراج القسائم + التفعيل المباشر للشاشة -->
  <div class="work-grid">

    <!-- 1. استخراج قسيمة فورية -->
    <div class="card" style="border-color:rgba(245,158,11,0.45);margin-bottom:0">
      <div class="card-header">
        <div class="card-title">⚡ استخراج قسيمة اشتراك فورية (XXXX-XXXX)</div>
        <span class="badge warn">1 شهر = 1 نقطة</span>
      </div>
      <div class="card-body">
        <label style="display:block;margin-bottom:10px;font-size:13px;font-weight:800;color:var(--txd)">اختر مدة الاشتراك المطلوبة:</label>
        <div class="pkg-grid" id="pkgGrid">
          <div class="pkg-card selected" data-pkg="1month" data-pts="1" data-days="30" data-label="شهر واحد (30 يوم)" onclick="selectPackage(this)">
            <div class="pkg-top"><span class="pkg-name">📅 شهر واحد</span><span class="pkg-pts">1 نقطة</span></div>
            <div class="pkg-days">صلاحية 30 يوم كاملة</div>
          </div>
          <div class="pkg-card" data-pkg="3months" data-pts="3" data-days="90" data-label="3 أشهر (90 يوم)" onclick="selectPackage(this)">
            <div class="pkg-top"><span class="pkg-name">🔥 3 أشهر</span><span class="pkg-pts">3 نقاط</span></div>
            <div class="pkg-days">صلاحية 90 يوم كاملة</div>
          </div>
          <div class="pkg-card" data-pkg="6months" data-pts="6" data-days="180" data-label="6 أشهر (180 يوم)" onclick="selectPackage(this)">
            <div class="pkg-top"><span class="pkg-name">💎 6 أشهر</span><span class="pkg-pts">6 نقاط</span></div>
            <div class="pkg-days">صلاحية 180 يوم كاملة</div>
          </div>
          <div class="pkg-card" data-pkg="1year" data-pts="12" data-days="365" data-label="سنة كاملة (365 يوم)" onclick="selectPackage(this)">
            <div class="pkg-top"><span class="pkg-name">👑 سنة كاملة</span><span class="pkg-pts">12 نقطة</span></div>
            <div class="pkg-days">صلاحية 365 يوم كاملة</div>
          </div>
        </div>

        <div style="display:grid;grid-template-columns:130px 1fr;gap:12px">
          <div class="form-group">
            <label>الكمية (عدد القسائم)</label>
            <input type="number" id="genQty" class="form-control" value="1" min="1" max="50" oninput="updateCostPreview()">
          </div>
          <div class="form-group">
            <label>اسم الزبون / ملاحظة (اختياري)</label>
            <input type="text" id="genNote" class="form-control" placeholder="مثال: الزبون أحمد - شاشة غرفة الجلوس">
          </div>
        </div>

        <div id="costPreviewBox" style="background:var(--bg);border:1px solid var(--bd);border-radius:12px;padding:12px 15px;margin-bottom:16px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;font-size:13.5px">
          <span>الباقة: <b id="prevPkgLabel" style="color:var(--gold-h)">شهر واحد (30 يوم)</b></span>
          <span>التكلفة الإجمالية: <b id="prevPtsCost" style="color:var(--ok)">1 نقطة</b></span>
        </div>

        <button type="button" id="btnExtract" class="btn primary" style="width:100%;padding:14px;font-size:15.5px" onclick="extractVouchersNow()">
          🎫 استخراج القسيمة الآن وخصم النقاط
        </button>
      </div>
    </div>

    <!-- 2. التفعيل المباشر لرمز شاشة زبون (DEV-XXXX-XXXX) -->
    <div class="card" style="margin-bottom:0">
      <div class="card-header">
        <div class="card-title">🖥️ تفعيل مباشر لرمز شاشة زبون</div>
        <span class="badge ok">تفعيل فوري عن بُعد</span>
      </div>
      <div class="card-body">
        <p style="font-size:13px;color:var(--txd);margin-bottom:14px;line-height:1.65">
          يمكنك تفعيل أو تجديد شاشة الزبون مباشرة عن بُعد بإدخال رمز الشاشة الظاهر على تلفازه (<span dir="ltr" style="color:var(--inf);font-family:monospace">DEV-XXXX-XXXX</span>) دون حاجة الزبون لكتابة القسيمة:
        </p>
        <div class="form-group">
          <label>🖥️ رمز شاشة الزبون (DEV-XXXX-XXXX) *</label>
          <div style="display:flex;gap:8px">
            <input type="text" id="directDeviceId" class="form-control ltr-input" placeholder="DEV-A1B2-C3D4" oninput="formatDeviceInput(this)">
            <button type="button" class="btn outline" onclick="checkCustomerDevice()" title="فحص حالة الشاشة">🔍 فحص</button>
          </div>
        </div>
        <div id="deviceCheckAlert" style="display:none;padding:10px 14px;border-radius:10px;margin-bottom:14px;font-size:13px;font-weight:700"></div>

        <div class="form-group">
          <label>📅 باقة التفعيل المباشر</label>
          <select id="directPkgSelect" class="form-control">
            <option value="1month">📅 شهر واحد (30 يوم) — يخصم 1 نقطة</option>
            <option value="3months">🔥 3 أشهر (90 يوم) — يخصم 3 نقاط</option>
            <option value="6months">💎 6 أشهر (180 يوم) — يخصم 6 نقاط</option>
            <option value="1year">👑 سنة كاملة (365 يوم) — يخصم 12 نقطة</option>
          </select>
        </div>

        <div class="form-group">
          <label>👤 اسم الزبون (اختياري)</label>
          <input type="text" id="directCustName" class="form-control" placeholder="اسم الزبون لحفظه في السجل">
        </div>

        <button type="button" id="btnDirectActivate" class="btn success" style="width:100%;padding:14px;font-size:15px" onclick="directActivateScreen()">
          ⚡ تفعيل وتجديد الشاشة فوراً
        </button>
      </div>
    </div>

  </div>


  <!-- 🌐 التغيير الجماعي للدومين (للشاشات التابعة للموزع فقط) -->
  <div class="card" style="border-color:rgba(14,165,233,0.45);background:linear-gradient(135deg,rgba(14,165,233,0.08),rgba(17,24,39,0.96))">
    <div class="card-header" style="flex-wrap:wrap;gap:10px">
      <div class="card-title">🌐 التغيير التلقائي الجماعي للدومين (للشاشات التي فعّلتها أنت فقط)</div>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <span class="badge ok" id="lblOwnedDevicesBadge">📺 شاشاتك المفعّلة: 0</span>
        <button type="button" class="btn primary" style="padding:7px 14px;font-size:12.5px" onclick="openMyDomainsModal()">
          📋 نافذة كشف الدومينات المسجلة (<span id="lblMyDomainsModalCount">0</span>)
        </button>
      </div>
    </div>
    <div class="card-body">
      <p style="font-size:13px;color:var(--txd);margin-bottom:14px;line-height:1.7">
        يتيح لك هذا القسم تغيير الدومين القديم إلى دومين جديد دفعة واحدة <b>حصرياً للشاشات التي قمت أنت (أو موزعوك الفرعيون) بتفعيلها</b>. يمكنك البحث في الجدول أدناه أو فتح <b>النافذة المنبثقة</b> لاختيار الدومين المستهدف بضغطة زر:
      </p>

      <!-- جدول كشف الدومينات المسجلة داخل الصفحة (كل 10 دومينات في صفحة مع محرك بحث ذكي) -->
      <div style="background:rgba(7,11,20,0.65);border:1px solid var(--bd);border-radius:14px;padding:14px;margin-bottom:16px">
        <div style="display:flex;flex-wrap:wrap;gap:10px;justify-content:space-between;align-items:center;margin-bottom:12px">
          <div style="font-weight:800;font-size:13.5px;color:var(--gold-h)">
            🔎 جدول كشف الدومينات المسجلة لشاشاتك (10 دومينات في كل صفحة)
          </div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;flex:1;max-width:540px;justify-content:flex-end">
            <input type="text" id="myDomInlineSearch" class="form-control" style="flex:1;min-width:190px;padding:8px 12px;font-size:13px" placeholder="🔍 بحث ذكي بالدومين أو اسم السيرفر..." oninput="onSearchMyDomainsInline()">
            <select id="myDomInlineSort" class="form-control" style="width:auto;min-width:155px;padding:8px 10px;font-size:12.5px" onchange="onSearchMyDomainsInline()">
              <option value="playlists">📊 الأكثر قوائم</option>
              <option value="devices">📺 الأكثر شاشات</option>
              <option value="az">🔤 أبجدياً (A-Z)</option>
            </select>
          </div>
        </div>

        <div class="table-responsive">
          <table>
            <thead>
              <tr>
                <th style="width:46px">#</th>
                <th>🌐 الدومين / الهوست المسجل</th>
                <th>🏷️ اسم السيرفر</th>
                <th>📊 القوائم والشاشات</th>
                <th style="width:210px">⚡ إجراء سريع</th>
              </tr>
            </thead>
            <tbody id="myDomainsInlineBody">
              <tr><td colspan="5" style="text-align:center;padding:20px;color:var(--txd)">لا توجد دومينات مسجلة حالياً</td></tr>
            </tbody>
          </table>
        </div>
        <div id="myDomainsInlinePagination" style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;margin-top:12px"></div>
      </div>

      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px;align-items:end">
        <div class="form-group" style="margin-bottom:0">
          <label>🎯 خانة الدومين القديم المستهدف (Old Domain)</label>
          <input type="text" id="myBulkOldDomain" class="form-control" dir="ltr" style="text-align:left;font-family:monospace;font-size:14px" placeholder="مثال: old-domain.com:8080">
        </div>
        <div class="form-group" style="margin-bottom:0">
          <label>🚀 خانة الدومين الجديد المقترح (New Domain)</label>
          <input type="text" id="myBulkNewDomain" class="form-control" dir="ltr" style="text-align:left;font-family:monospace;font-size:14px" placeholder="مثال: new-domain.com:8080">
        </div>
        <div>
          <button type="button" id="btnMyBulkDomain" class="btn primary" style="width:100%;padding:13px" onclick="bulkReplaceMyDomainNow()">
            🔄 استبدال الدومين لشاشاتي الآن
          </button>
        </div>
      </div>
    </div>
  </div>

  <!-- 👥 إدارة الموزعين الفرعيين (فتح لوحات موزعين معك وتحويل النقاط لهم) -->
  <div class="card" style="border-color:rgba(168,85,247,0.45)">
    <div class="card-header">
      <div class="card-title">👥 لوحات الموزعين الفرعيين التابعين لك (<span id="lblSubResellersCount">0</span>)</div>
      <button type="button" class="btn primary" onclick="openAddSubResellerModal()">➕ فتح حساب موزع فرعي</button>
    </div>
    <div class="card-body">
      <p style="font-size:13px;color:var(--txd);margin-bottom:14px;line-height:1.7">
        يمكنك فتح حسابات لموزعين فرعيين يعملون معك، وتحويل النقاط إليهم من رصيدك الحالي أو استرجاع النقاط غير المستخدمة منهم في أي وقت. يدخل الموزع الفرعي من نفس رابط بوابة الموزعين باسم المستخدم وكلمة المرور الخاصة به.
      </p>
      <div class="table-responsive">
        <table>
          <thead>
            <tr>
              <th>اسم الموزع الفرعي</th>
              <th>اسم الدخول (Username)</th>
              <th>كلمة المرور</th>
              <th>رصيد النقاط</th>
              <th>القسائم المستخرجة</th>
              <th>الحالة</th>
              <th>إجراءات</th>
            </tr>
          </thead>
          <tbody id="subResellersBody">
            <tr><td colspan="7" style="text-align:center;padding:22px;color:var(--txd)">لا يوجد موزعون فرعيون حالياً — اضغط على "➕ فتح حساب موزع فرعي" لإضافة موزع معك</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- السجلات: القسائم المستخرجة + كشف حساب النقاط -->
  <div class="card">
    <div class="card-header">
      <div class="sub-tabs" style="margin-bottom:0">
        <button class="sub-tab active" id="tabBtnCards" onclick="switchSubTab('cards')">🎫 سجل القسائم المستخرجة (<span id="lblCountCards">0</span>)</button>
        <button class="sub-tab" id="tabBtnTx" onclick="switchSubTab('tx')">📊 كشف حساب النقاط والعمليات (<span id="lblCountTx">0</span>)</button>
      </div>
    </div>
    <div class="card-body">

      <!-- جدول القسائم -->
      <div id="subPaneCards">
        <div class="toolbar">
          <input type="text" id="searchCards" class="form-control" style="flex:1;min-width:220px" placeholder="🔍 بحث برمز القسيمة، رمز الشاشة، أو اسم الزبون..." oninput="renderMyCards()">
          <select id="filterCardStatus" class="form-control" style="width:auto;min-width:150px" onchange="renderMyCards()">
            <option value="">كل الحالات</option>
            <option value="unused">🟢 متاحة (غير مستخدمة)</option>
            <option value="used">✅ مفعّلة (مستخدمة)</option>
          </select>
          <select id="filterCardPkg" class="form-control" style="width:auto;min-width:150px" onchange="renderMyCards()">
            <option value="">كل الباقات</option>
            <option value="1month">شهر واحد (1 نقطة)</option>
            <option value="3months">3 أشهر (3 نقاط)</option>
            <option value="6months">6 أشهر (6 نقاط)</option>
            <option value="1year">سنة كاملة (12 نقطة)</option>
          </select>
        </div>
        <div class="table-responsive">
          <table>
            <thead>
              <tr>
                <th>رمز القسيمة (LTR)</th>
                <th>الباقة والمدة</th>
                <th>النقاط</th>
                <th>الحالة</th>
                <th>الشاشة المفعّلة</th>
                <th>ملاحظة / الزبون</th>
                <th>تاريخ الاستخراج</th>
                <th>إجراءات</th>
              </tr>
            </thead>
            <tbody id="myCardsBody"><tr><td colspan="8" style="text-align:center;padding:24px;color:var(--txd)">جارٍ التحميل...</td></tr></tbody>
          </table>
        </div>
      </div>

      <!-- جدول كشف حساب النقاط -->
      <div id="subPaneTx" style="display:none">
        <div class="table-responsive">
          <table>
            <thead>
              <tr>
                <th>نوع العملية</th>
                <th>النقاط</th>
                <th>الرصيد بعد العملية</th>
                <th>رمز القسيمة / الشاشة</th>
                <th>البيان والتفاصيل</th>
                <th>التاريخ والوقت</th>
              </tr>
            </thead>
            <tbody id="myTxBody"><tr><td colspan="6" style="text-align:center;padding:24px;color:var(--txd)">جارٍ التحميل...</td></tr></tbody>
          </table>
        </div>
      </div>

    </div>
  </div>

</div>

<!-- نافذة عرض القسيمة المستخرجة (بتنسيق LTR صارم قابل للنسخ والطباعة) -->
<div class="modal-overlay" id="extractedModal">
  <div class="modal-box" style="text-align:center">
    <div style="font-size:44px;margin-bottom:6px">🎉</div>
    <h3 style="color:var(--gold-h);font-size:20px;font-weight:900;margin-bottom:6px" id="extModalTitle">تم استخراج القسيمة بنجاح!</h3>
    <p style="font-size:13.5px;color:var(--txd);margin-bottom:12px" id="extModalSub"></p>

    <div id="singleCodeContainer">
      <div class="voucher-box" id="extSingleCode" dir="ltr">XXXX-XXXX</div>
    </div>

    <div id="multiCodeContainer" style="display:none;margin:10px 0">
      <textarea id="extMultiCodes" class="form-control ltr-input" rows="6" readonly style="font-size:16px!important"></textarea>
    </div>

    <div style="background:rgba(16,185,129,0.1);border:1px dashed var(--ok);color:#a7f3d0;padding:10px 14px;border-radius:11px;font-size:12.5px;font-weight:700;margin:12px 0;line-height:1.6">
      ✅ الرمز مضبوط بتنسيق قياسي ثابت (<span dir="ltr">XXXX-XXXX</span>) — عند ضغط الزبون على زر <b>"لصق"</b> في التطبيق يُكتب بنفس التنسيق تماماً دون تبعثر ويتفعل فوراً.
    </div>

    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:16px">
      <button type="button" class="btn primary" onclick="copyExtractedPureCode()">📋 نسخ الكود الصافي</button>
      <button type="button" class="btn success" onclick="shareExtractedWhatsApp()">💬 إرسال عبر واتساب</button>
      <button type="button" class="btn outline" onclick="printExtractedVoucher()">🖨️ طباعة وصل القسيمة</button>
      <button type="button" class="btn outline" onclick="closeModal('extractedModal')">إغلاق</button>
    </div>
  </div>
</div>

<!-- نافذة تغيير كلمة المرور للموزع -->
<div class="modal-overlay" id="passModal">
  <div class="modal-box" style="max-width:420px">
    <h3 style="color:var(--gold);margin-bottom:16px;font-size:18px">🔑 تغيير كلمة المرور</h3>
    <div class="form-group">
      <label>كلمة المرور الحالية</label>
      <input type="password" id="oldResPass" class="form-control" dir="ltr">
    </div>
    <div class="form-group">
      <label>كلمة المرور الجديدة</label>
      <input type="password" id="newResPass" class="form-control" dir="ltr">
    </div>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:16px">
      <button type="button" class="btn outline" onclick="closeModal('passModal')">إلغاء</button>
      <button type="button" class="btn primary" onclick="changeMyPassword()">💾 حفظ</button>
    </div>
  </div>
</div>



<!-- نافذة منبثقة احترافية لكشف الدومينات المسجلة للموزع (كل 10 دومينات في جدول مع محرك بحث ذكي) -->
<div class="modal-overlay" id="myDomainsModal">
  <div class="modal-box" style="max-width:860px;width:96%">
    <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:14px;border-bottom:1px solid var(--bd);padding-bottom:12px">
      <div>
        <h3 style="color:var(--gold-h);font-size:18.5px;font-weight:900;margin:0">🌐 كشف الدومينات المسجلة لشاشاتك</h3>
        <p style="font-size:12.5px;color:var(--txd);margin:4px 0 0 0">مرتبة في جدول احترافي (10 دومينات في كل صفحة) مع محرك بحث ذكي وتعبئة فورية</p>
      </div>
      <button type="button" class="btn outline" style="padding:6px 12px" onclick="closeModal('myDomainsModal')">✕ إغلاق</button>
    </div>

    <div style="display:flex;flex-wrap:wrap;gap:10px;margin-bottom:14px">
      <input type="text" id="myDomModalSearch" class="form-control" style="flex:1;min-width:220px" placeholder="🔍 ابحث بالدومين، رقم البورت، أو اسم السيرفر..." oninput="onSearchMyDomainsModal()">
      <select id="myDomModalSort" class="form-control" style="width:auto;min-width:170px" onchange="onSearchMyDomainsModal()">
        <option value="playlists">📊 الترتيب: الأكثر قوائم</option>
        <option value="devices">📺 الترتيب: الأكثر شاشات</option>
        <option value="az">🔤 الترتيب: الأبجدي (A-Z)</option>
      </select>
    </div>

    <div class="table-responsive" style="max-height:430px;overflow-y:auto">
      <table>
        <thead>
          <tr>
            <th style="width:46px">#</th>
            <th>🌐 الدومين / الهوست المسجل</th>
            <th>🏷️ اسم السيرفر</th>
            <th>📊 الإحصائيات (القوائم / الشاشات)</th>
            <th style="width:220px">⚡ اختيار أو نسخ</th>
          </tr>
        </thead>
        <tbody id="myDomainsModalBody">
          <tr><td colspan="5" style="text-align:center;padding:24px;color:var(--txd)">لا توجد دومينات مسجلة</td></tr>
        </tbody>
      </table>
    </div>

    <div id="myDomainsModalPagination" style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px;margin-top:14px;padding-top:10px;border-top:1px solid var(--bd)"></div>
  </div>
</div>

<!-- نافذة فتح حساب موزع فرعي جديد -->
<div class="modal-overlay" id="addSubResellerModal">
  <div class="modal-box" style="max-width:480px">
    <h3 style="color:var(--gold);margin-bottom:14px;font-size:18px">➕ فتح حساب موزع فرعي جديد معك</h3>
    <div class="form-group">
      <label>🏪 الاسم الظاهر للموزع الفرعي</label>
      <input type="text" id="subAddDisplayName" class="form-control" placeholder="مثال: محل النور للستلايت">
    </div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
      <div class="form-group">
        <label>👤 اسم المستخدم للدخول (إنجليزي)</label>
        <input type="text" id="subAddUsername" class="form-control" dir="ltr" style="text-align:left;font-family:monospace" placeholder="reseller2">
      </div>
      <div class="form-group">
        <label>🔑 كلمة المرور</label>
        <input type="text" id="subAddPassword" class="form-control" dir="ltr" style="text-align:left;font-family:monospace" placeholder="123456">
      </div>
    </div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
      <div class="form-group">
        <label>💎 النقاط الابتدائية (تُخصم من رصيدك)</label>
        <input type="number" id="subAddInitialPts" class="form-control" min="0" value="0" dir="ltr">
      </div>
      <div class="form-group">
        <label>📱 رقم الهاتف / واتساب (اختياري)</label>
        <input type="text" id="subAddPhone" class="form-control" dir="ltr" placeholder="00213...">
      </div>
    </div>
    <div class="form-group">
      <label>📝 ملاحظات (اختياري)</label>
      <input type="text" id="subAddNotes" class="form-control" placeholder="ملاحظة خاصة بهذا الموزع الفرعي">
    </div>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:16px">
      <button type="button" class="btn outline" onclick="closeModal('addSubResellerModal')">إلغاء</button>
      <button type="button" class="btn primary" onclick="createSubResellerNow()">✅ إنشاء الحساب وتحويل النقاط</button>
    </div>
  </div>
</div>

<!-- نافذة تحويل أو سحب نقاط لموزع فرعي -->
<div class="modal-overlay" id="subPointsModal">
  <div class="modal-box" style="max-width:440px">
    <h3 style="color:var(--gold);margin-bottom:8px;font-size:18px" id="subPtsModalTitle">💎 تحويل نقاط للموزع الفرعي</h3>
    <p style="font-size:13px;color:var(--txd);margin-bottom:14px" id="subPtsModalSub"></p>
    <input type="hidden" id="subPtsTargetId">
    <div class="form-group">
      <label>⚙️ نوع العملية</label>
      <select id="subPtsMode" class="form-control">
        <option value="add">➕ إرسال نقاط من رصيدي إلى الموزع الفرعي</option>
        <option value="deduct">➖ استرجاع (سحب) نقاط من الموزع الفرعي إلى رصيدي</option>
      </select>
    </div>
    <div class="form-group">
      <label>💎 عدد النقاط (1 نقطة = شهر اشتراك)</label>
      <input type="number" id="subPtsAmount" class="form-control" min="1" value="5" dir="ltr" style="font-size:18px;font-weight:900;text-align:center">
    </div>
    <div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:12px">
      <button type="button" class="btn outline" style="padding:6px 10px;font-size:12px" onclick="$('subPtsAmount').value=1">1 نقطة (شهر)</button>
      <button type="button" class="btn outline" style="padding:6px 10px;font-size:12px" onclick="$('subPtsAmount').value=3">3 نقاط (3 أشهر)</button>
      <button type="button" class="btn outline" style="padding:6px 10px;font-size:12px" onclick="$('subPtsAmount').value=6">6 نقاط (6 أشهر)</button>
      <button type="button" class="btn outline" style="padding:6px 10px;font-size:12px" onclick="$('subPtsAmount').value=12">12 نقطة (سنة)</button>
    </div>
    <div class="form-group">
      <label>📝 بيان العملية (اختياري)</label>
      <input type="text" id="subPtsReason" class="form-control" placeholder="مثال: دفعة نقاط جديدة">
    </div>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:16px">
      <button type="button" class="btn outline" onclick="closeModal('subPointsModal')">إلغاء</button>
      <button type="button" class="btn primary" onclick="submitSubPointsTransfer()">🚀 تنفيذ العملية</button>
    </div>
  </div>
</div>

<!-- نافذة تعديل بيانات موزع فرعي -->
<div class="modal-overlay" id="editSubResellerModal">
  <div class="modal-box" style="max-width:450px">
    <h3 style="color:var(--gold);margin-bottom:14px;font-size:18px">✏️ تعديل حساب الموزع الفرعي</h3>
    <input type="hidden" id="editSubId">
    <div class="form-group">
      <label>🏪 الاسم الظاهر للموزع الفرعي</label>
      <input type="text" id="editSubDisplayName" class="form-control">
    </div>
    <div class="form-group">
      <label>🔑 كلمة مرور جديدة (اتركها فارغة لعدم التغيير)</label>
      <input type="text" id="editSubPassword" class="form-control" dir="ltr" style="text-align:left;font-family:monospace" placeholder="اتركها فارغة للاحتفاظ بالحالية">
    </div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
      <div class="form-group">
        <label>📱 الهاتف</label>
        <input type="text" id="editSubPhone" class="form-control" dir="ltr">
      </div>
      <div class="form-group">
        <label>⚡ حالة الحساب</label>
        <select id="editSubActive" class="form-control">
          <option value="1">🟢 نشط</option>
          <option value="0">🔴 موقوف</option>
        </select>
      </div>
    </div>
    <div class="form-group">
      <label>📝 ملاحظات</label>
      <input type="text" id="editSubNotes" class="form-control">
    </div>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:16px">
      <button type="button" class="btn outline" onclick="closeModal('editSubResellerModal')">إلغاء</button>
      <button type="button" class="btn primary" onclick="saveSubResellerEdit()">💾 حفظ التعديلات</button>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
let RESELLER_TOKEN = localStorage.getItem('livepro_reseller_token') || '';
let STATE = {
  reseller: null,
  stats: {},
  cards: [],
  transactions: [],
  subResellers: [],
  domains: [],
  ownedDevicesCount: 0,
  selectedPkg: { type: '1month', pts: 1, days: 30, label: 'شهر واحد (30 يوم)' },
  lastExtracted: { codes: [], pkgLabel: '', days: 30, note: '' }
};

const $ = id => document.getElementById(id);
const esc = s => String(s || '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function toast(msg, type = '') {
  const el = $('toast');
  el.textContent = msg;
  el.className = 'toast show' + (type ? ' ' + type : '');
  clearTimeout(el.timer);
  el.timer = setTimeout(() => el.className = 'toast', 3600);
}

function openModal(id) { $(id).classList.add('active'); }
function closeModal(id) { $(id).classList.remove('active'); }

// تنظيف الرمز من أي محارف اتجاه مخفية قبل النسخ لضمان لصق متطابق 100% في التطبيق
function sanitizePureVoucher(code) {
  return String(code || '').replace(/[\u200B-\u200F\u202A-\u202E\u2060-\u2069\uFEFF]/g, '').trim().toUpperCase();
}

function copyPureCode(code) {
  const clean = sanitizePureVoucher(code);
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(clean).then(() => toast('📋 تم نسخ رمز القسيمة الصافي: ' + clean, 'success'));
  } else {
    const ta = document.createElement('textarea');
    ta.value = clean;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    toast('📋 تم نسخ الرمز: ' + clean, 'success');
  }
}

async function rpApi(action, data = {}) {
  const fd = new FormData();
  fd.append('ajax_action', action);
  if (RESELLER_TOKEN) fd.append('reseller_token', RESELLER_TOKEN);
  for (const k in data) {
    fd.append(k, data[k] == null ? '' : data[k]);
  }
  const res = await fetch(location.pathname, { method: 'POST', body: fd });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch (err) {
    throw new Error('استجابة غير صالحة من الخادم');
  }
  if (!json.success) {
    if (res.status === 401 || json.auth_expired) {
      RESELLER_TOKEN = '';
      localStorage.removeItem('livepro_reseller_token');
      showLoginView();
    }
    throw new Error(json.error || 'حدث خطأ');
  }
  return json;
}

function showLoginView() {
  $('topHeader').style.display = 'none';
  $('dashboardView').style.display = 'none';
  $('loginView').style.display = 'flex';
}

function showDashboardView() {
  $('loginView').style.display = 'none';
  $('topHeader').style.display = 'block';
  $('dashboardView').style.display = 'block';
}

async function handleLogin(e) {
  e.preventDefault();
  const username = $('loginUser').value.trim();
  const password = $('loginPass').value.trim();
  if (!username || !password) return toast('يرجى إدخال اسم المستخدم وكلمة المرور', 'error');
  const btn = $('btnLogin');
  btn.disabled = true;
  btn.textContent = '⏳ جارٍ التحقق...';
  try {
    const res = await rpApi('login', { username, password });
    RESELLER_TOKEN = res.token;
    localStorage.setItem('livepro_reseller_token', RESELLER_TOKEN);
    toast(res.message || '✅ مرحباً بك', 'success');
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = '🚀 دخول إلى لوحة الموزع';
  }
}

async function doLogout() {
  if (!confirm('هل تريد تسجيل الخروج من لوحة الموزع؟')) return;
  try { await rpApi('logout'); } catch (_) {}
  RESELLER_TOKEN = '';
  localStorage.removeItem('livepro_reseller_token');
  showLoginView();
}

async function loadDashboard(showMsg = false) {
  try {
    const d = await rpApi('dashboard');
    STATE.reseller = d.reseller;
    STATE.stats = d.stats || {};
    STATE.cards = d.cards || [];
    STATE.transactions = d.transactions || [];

    showDashboardView();
    $('hdrResellerName').textContent = `🏪 ${d.reseller.display_name} (@${d.reseller.username})`;
    $('hdrPoints').textContent = STATE.stats.points_balance ?? 0;
    $('stBalance').textContent = STATE.stats.points_balance ?? 0;
    $('stTotalCards').textContent = STATE.stats.total_cards_generated ?? 0;
    $('stUnusedCards').textContent = STATE.stats.unused_cards ?? 0;
    $('stUsedCards').textContent = STATE.stats.used_cards ?? 0;

    $('lblCountCards').textContent = STATE.cards.length;
    $('lblCountTx').textContent = STATE.transactions.length;

    STATE.subResellers = d.sub_resellers || [];
    STATE.domains = d.domains || [];
    STATE.ownedDevicesCount = d.owned_devices_count ?? (STATE.stats.owned_devices_count || 0);

    renderMyCards();
    renderMyTransactions();
    renderSubResellers();
    renderMyActiveDomains();
    updateCostPreview();
    if (showMsg) toast('✅ تم تحديث البيانات والرصيد', 'success');
  } catch (err) {
    showLoginView();
  }
}

function selectPackage(cardEl) {
  document.querySelectorAll('#pkgGrid .pkg-card').forEach(el => el.classList.remove('selected'));
  cardEl.classList.add('selected');
  STATE.selectedPkg = {
    type: cardEl.dataset.pkg,
    pts: parseInt(cardEl.dataset.pts) || 1,
    days: parseInt(cardEl.dataset.days) || 30,
    label: cardEl.dataset.label
  };
  updateCostPreview();
}

function updateCostPreview() {
  const qty = Math.min(50, Math.max(1, parseInt($('genQty').value) || 1));
  const totalPts = qty * STATE.selectedPkg.pts;
  $('prevPkgLabel').textContent = `${qty} × ${STATE.selectedPkg.label}`;
  $('prevPtsCost').textContent = `${totalPts} نقطة`;
}

async function extractVouchersNow() {
  const qty = Math.min(50, Math.max(1, parseInt($('genQty').value) || 1));
  const totalPts = qty * STATE.selectedPkg.pts;
  const currentPts = parseInt(STATE.stats.points_balance) || 0;

  if (currentPts < totalPts) {
    return toast(`⛔ رصيدك الحالي (${currentPts} نقطة) غير كافٍ لاستخراج ${qty} قسيمة (${totalPts} نقطة)`, 'error');
  }
  if (!confirm(`تأكيد استخراج ${qty} قسيمة (${STATE.selectedPkg.label}) وخصم ${totalPts} نقطة من رصيدك؟`)) return;

  const btn = $('btnExtract');
  btn.disabled = true;
  btn.textContent = '⏳ جارٍ استخراج القسيمة...';
  try {
    const note = $('genNote').value.trim();
    const res = await rpApi('generate_cards', {
      package_type: STATE.selectedPkg.type,
      quantity: qty,
      customer_note: note
    });
    toast(res.message, 'success');
    $('genNote').value = '';
    $('genQty').value = 1;

    STATE.lastExtracted = {
      codes: res.cards || [],
      pkgLabel: res.package_label,
      days: res.duration_days,
      note: note
    };

    $('extModalTitle').textContent = res.cards.length === 1 ? '🎉 تم استخراج القسيمة بنجاح!' : `🎉 تم استخراج ${res.cards.length} قسائم بنجاح!`;
    $('extModalSub').textContent = `الباقة: ${res.package_label} | تم خصم ${res.points_deducted} نقطة | رصيدك المتبقي: ${res.new_balance} نقطة`;

    if (res.cards.length === 1) {
      $('singleCodeContainer').style.display = 'block';
      $('multiCodeContainer').style.display = 'none';
      $('extSingleCode').textContent = sanitizePureVoucher(res.cards[0]);
    } else {
      $('singleCodeContainer').style.display = 'none';
      $('multiCodeContainer').style.display = 'block';
      $('extMultiCodes').value = res.cards.map(sanitizePureVoucher).join('\n');
    }
    openModal('extractedModal');
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = '🎫 استخراج القسيمة الآن وخصم النقاط';
  }
}

function copyExtractedPureCode() {
  const codes = (STATE.lastExtracted.codes || []).map(sanitizePureVoucher).join('\n');
  copyPureCode(codes);
}

function shareExtractedWhatsApp() {
  const codes = (STATE.lastExtracted.codes || []).map(sanitizePureVoucher);
  if (!codes.length) return;
  const text = codes.length === 1
    ? `🎬 اشتراك LIVE PRO\n📅 الباقة: ${STATE.lastExtracted.pkgLabel}\n🎫 رمز التفعيل (انسخ الكود والصقه في التطبيق):\n\n${codes[0]}`
    : `🎬 قسائم اشتراك LIVE PRO (${STATE.lastExtracted.pkgLabel}):\n\n${codes.join('\n')}`;
  window.open('https://wa.me/?text=' + encodeURIComponent(text), '_blank');
}

function printExtractedVoucher() {
  const codes = (STATE.lastExtracted.codes || []).map(sanitizePureVoucher);
  if (!codes.length) return;
  const resellerName = (STATE.reseller && STATE.reseller.display_name) || 'LIVE PRO Reseller';
  const win = window.open('', '_blank', 'width=450,height=650');
  if (!win) return toast('يرجى السماح بالنوافذ المنبثقة للطباعة', 'error');
  const cardsHtml = codes.map(c => `
    <div style="border:2px dashed #000;border-radius:14px;padding:18px;margin-bottom:16px;text-align:center;font-family:'Cairo',sans-serif">
      <div style="font-size:18px;font-weight:900">📺 LIVE PRO — قسيمة تفعيل رسمية</div>
      <div style="font-size:13px;margin:4px 0">الموزع المعتمد: <b>${esc(resellerName)}</b></div>
      <div style="font-size:14px;font-weight:800;margin:6px 0">الباقة: ${esc(STATE.lastExtracted.pkgLabel)}</div>
      <div dir="ltr" style="font-family:monospace;font-size:26px;font-weight:900;letter-spacing:3px;border:2px solid #000;padding:10px;border-radius:10px;margin:10px 0;direction:ltr;unicode-bidi:isolate">${esc(c)}</div>
      <div style="font-size:11.5px;color:#333">أدخل الرمز في تطبيق LIVE PRO بالترتيب من اليسار لليمين (XXXX-XXXX) ثم اضغط تفعيل</div>
    </div>
  `).join('');
  win.document.write(`<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="UTF-8"><title>طباعة القسيمة</title></head><body onload="window.print()">${cardsHtml}</body></html>`);
  win.document.close();
}

function formatDeviceInput(input) {
  let raw = input.value.toUpperCase().replace(/[\u200B-\u200F\u202A-\u202E\u2060-\u2069\uFEFF]/g, '');
  raw = raw.replace(/^DEV-?/, '').replace(/[^A-Z0-9]/g, '').substring(0, 8);
  if (raw.length > 4) {
    input.value = 'DEV-' + raw.substring(0, 4) + '-' + raw.substring(4);
  } else if (raw.length > 0) {
    input.value = 'DEV-' + raw;
  }
}

async function checkCustomerDevice() {
  const dev = $('directDeviceId').value.trim().toUpperCase();
  if (!dev) return toast('يرجى إدخال رمز الشاشة أولاً (مثال: DEV-A1B2-C3D4)', 'error');
  const box = $('deviceCheckAlert');
  box.style.display = 'block';
  box.style.background = 'rgba(56,189,248,0.12)';
  box.style.border = '1px solid var(--inf)';
  box.style.color = '#bae6fd';
  box.textContent = '⏳ جارٍ فحص حالة الشاشة...';
  try {
    const res = await rpApi('check_device', { device_id: dev });
    $('directDeviceId').value = res.device_id;
    box.style.background = res.active ? 'var(--ok-bg)' : 'var(--gold-bg)';
    box.style.borderColor = res.active ? 'var(--ok)' : 'var(--gold)';
    box.style.color = res.active ? '#a7f3d0' : '#fde68a';
    box.textContent = `🖥️ ${res.device_id}: ${res.status_text}`;
  } catch (err) {
    box.style.background = 'var(--dn-bg)';
    box.style.borderColor = 'var(--dn)';
    box.style.color = '#fecaca';
    box.textContent = err.message;
  }
}

async function directActivateScreen() {
  const device_id = $('directDeviceId').value.trim().toUpperCase();
  const package_type = $('directPkgSelect').value;
  const customer_name = $('directCustName').value.trim();
  if (!device_id) return toast('يرجى إدخال رمز الشاشة (DEV-XXXX-XXXX)', 'error');
  if (!confirm(`تأكيد التفعيل المباشر للشاشة (${device_id})؟`)) return;

  const btn = $('btnDirectActivate');
  btn.disabled = true;
  btn.textContent = '⏳ جارٍ تفعيل الشاشة...';
  try {
    const res = await rpApi('direct_activate', { device_id, package_type, customer_name });
    toast(res.message, 'success');
    $('directCustName').value = '';
    const box = $('deviceCheckAlert');
    box.style.display = 'block';
    box.style.background = 'var(--ok-bg)';
    box.style.borderColor = 'var(--ok)';
    box.style.color = '#a7f3d0';
    box.textContent = `${res.message} (رمز العملية: ${res.card_code})`;
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = '⚡ تفعيل وتجديد الشاشة فوراً';
  }
}

function switchSubTab(tab) {
  $('tabBtnCards').classList.toggle('active', tab === 'cards');
  $('tabBtnTx').classList.toggle('active', tab === 'tx');
  $('subPaneCards').style.display = tab === 'cards' ? 'block' : 'none';
  $('subPaneTx').style.display = tab === 'tx' ? 'block' : 'none';
}

function renderMyCards() {
  const q = ($('searchCards').value || '').toLowerCase().trim();
  const st = $('filterCardStatus').value;
  const pk = $('filterCardPkg').value;
  const pkgLabels = { '1month': 'شهر واحد (30 يوم)', '3months': '3 أشهر (90 يوم)', '6months': '6 أشهر (180 يوم)', '1year': 'سنة كاملة (365 يوم)' };

  const list = (STATE.cards || []).filter(c => {
    const matchQ = !q ||
      String(c.code_formatted || '').toLowerCase().includes(q) ||
      String(c.used_by_device || '').toLowerCase().includes(q) ||
      String(c.redemption_device || '').toLowerCase().includes(q) ||
      String(c.notes || '').toLowerCase().includes(q);
    const matchSt = !st || c.status === st;
    const matchPk = !pk || c.card_type === pk;
    return matchQ && matchSt && matchPk;
  });

  const tbody = $('myCardsBody');
  if (!list.length) {
    tbody.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:24px;color:var(--txd)">لا توجد قسائم مطابقة للبحث</td></tr>';
    return;
  }

  tbody.innerHTML = list.map(c => {
    const code = sanitizePureVoucher(c.code_formatted);
    const pkgText = pkgLabels[c.card_type] || `${c.duration_days} يوم`;
    const devUsed = c.used_by_device || c.redemption_device || '';
    const isUnused = c.status === 'unused';
    return `
      <tr>
        <td><span class="code-chip" dir="ltr" onclick="copyPureCode('${esc(code)}')" title="اضغط لنسخ الرمز الصافي">${esc(code)}</span></td>
        <td><b>${esc(pkgText)}</b></td>
        <td><span class="badge warn">${c.points_cost || 1} نقطة</span></td>
        <td><span class="badge ${isUnused ? 'ok' : 'inf'}">${isUnused ? '🟢 متاحة للبيع' : '✅ مفعّلة'}</span></td>
        <td dir="ltr" style="font-family:monospace;color:var(--inf)">${esc(devUsed || '—')}</td>
        <td style="font-size:12.5px;color:var(--txd)">${esc(c.notes || '—')}</td>
        <td style="font-size:12px;color:var(--txd)">${esc(c.created_at || '')}</td>
        <td style="white-space:nowrap">
          <button class="btn outline sm" onclick="copyPureCode('${esc(code)}')" title="نسخ الرمز الصافي بنفس التنسيق">📋 نسخ الكود</button>
          <button class="btn success sm" onclick="shareSingleCardWhatsApp('${esc(code)}', '${esc(pkgText)}')" title="إرسال للزبون عبر واتساب">💬</button>
        </td>
      </tr>
    `;
  }).join('');
}

function shareSingleCardWhatsApp(code, pkgText) {
  const clean = sanitizePureVoucher(code);
  const msg = `🎬 اشتراك LIVE PRO\n📅 الباقة: ${pkgText}\n🎫 رمز التفعيل:\n\n${clean}`;
  window.open('https://wa.me/?text=' + encodeURIComponent(msg), '_blank');
}

function renderMyTransactions() {
  const tbody = $('myTxBody');
  const list = STATE.transactions || [];
  if (!list.length) {
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:24px;color:var(--txd)">لا توجد عمليات مسجلة بعد</td></tr>';
    return;
  }
  const txLabels = {
    credit_add: '➕ شحن رصيد من الإدارة',
    credit_deduct: '➖ خصم إداري',
    generate_card: '🎫 استخراج قسيمة',
    direct_activate: '🖥️ تفعيل مباشر لشاشة'
  };
  tbody.innerHTML = list.map(t => {
    const isPlus = parseInt(t.points_delta) > 0;
    const codeOrDev = t.card_code || t.target_device_id || '—';
    return `
      <tr>
        <td><span class="badge ${isPlus ? 'ok' : 'warn'}">${esc(txLabels[t.type] || t.type)}</span></td>
        <td dir="ltr" style="font-weight:900;font-size:15px;color:${isPlus ? 'var(--ok)' : 'var(--gold-h)'}">${isPlus ? '+' + t.points_delta : t.points_delta}</td>
        <td><b>${t.balance_after}</b> نقطة</td>
        <td>${codeOrDev !== '—' ? `<span class="code-chip" dir="ltr" onclick="copyPureCode('${esc(codeOrDev)}')">${esc(codeOrDev)}</span>` : '—'}</td>
        <td style="font-size:13px">${esc(t.notes || '')}</td>
        <td style="font-size:12px;color:var(--txd)">${esc(t.created_at || '')}</td>
      </tr>
    `;
  }).join('');
}


let myDomInlinePage = 1;
let myDomModalPage = 1;
const MY_DOM_PER_PAGE = 10;

function getFilteredAndSortedMyDomains(queryStr, sortMode) {
  const q = String(queryStr || '').toLowerCase().trim();
  const list = (STATE.domains || []).filter(item => {
    if (!q) return true;
    return String(item.domain || '').toLowerCase().includes(q) ||
           String(item.full_url || '').toLowerCase().includes(q) ||
           String(item.server_name || '').toLowerCase().includes(q);
  });
  return [...list].sort((a, b) => {
    if (sortMode === 'devices') {
      if ((b.devices_count || 0) !== (a.devices_count || 0)) return (b.devices_count || 0) - (a.devices_count || 0);
    } else if (sortMode === 'az') {
      return String(a.domain || '').localeCompare(String(b.domain || ''));
    }
    if ((b.playlists_count || 0) !== (a.playlists_count || 0)) return (b.playlists_count || 0) - (a.playlists_count || 0);
    return String(a.domain || '').localeCompare(String(b.domain || ''));
  });
}

function buildMyDomainsPaginationHtml(totalItems, currentPage, perPage, pageFnName) {
  const totalPages = Math.ceil(totalItems / perPage) || 1;
  if (totalItems === 0) return '';
  const startIdx = (currentPage - 1) * perPage + 1;
  const endIdx = Math.min(totalItems, currentPage * perPage);
  let btns = '';
  btns += `<button type="button" class="btn outline" style="padding:5px 10px;font-size:12px" ${currentPage <= 1 ? 'disabled' : ''} onclick="${pageFnName}(${currentPage - 1})">◀ السابق</button>`;
  for (let p = 1; p <= totalPages; p++) {
    if (totalPages > 7 && p > 2 && p < totalPages - 1 && Math.abs(p - currentPage) > 1) {
      if (p === 3 || p === totalPages - 2) btns += `<span style="color:var(--txd);padding:0 4px">...</span>`;
      continue;
    }
    const isAct = p === currentPage;
    btns += `<button type="button" class="btn ${isAct ? 'primary' : 'outline'}" style="padding:5px 11px;font-size:12px;min-width:34px" onclick="${pageFnName}(${p})">${p}</button>`;
  }
  btns += `<button type="button" class="btn outline" style="padding:5px 10px;font-size:12px" ${currentPage >= totalPages ? 'disabled' : ''} onclick="${pageFnName}(${currentPage + 1})">التالي ▶</button>`;
  return `
    <div style="font-size:12px;color:var(--txd);font-weight:700">
      عرض <b>${startIdx}–${endIdx}</b> من أصل <b>${totalItems}</b> دومين مسجل (صفحة ${currentPage} من ${totalPages})
    </div>
    <div style="display:flex;gap:5px;flex-wrap:wrap;align-items:center">${btns}</div>
  `;
}

function selectMyOldDomainFromTable(domainVal, fromModal = false) {
  const inp = $('myBulkOldDomain');
  if (inp) inp.value = domainVal;
  if (fromModal) closeModal('myDomainsModal');
  const newInp = $('myBulkNewDomain');
  if (newInp) {
    newInp.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setTimeout(() => newInp.focus(), 120);
  }
  toast(`🎯 تم اختيار الدومين القديم: ${domainVal} — أدخل الدومين الجديد الآن`, 'success');
}

function renderMyDomainsTableSection(tbodyId, pagId, queryId, sortId, pageNum, pageFnName, isModal) {
  const tbody = $(tbodyId);
  const pagBox = $(pagId);
  if (!tbody) return;
  const q = ($(queryId) && $(queryId).value) || '';
  const sortMode = ($(sortId) && $(sortId).value) || 'playlists';
  const filtered = getFilteredAndSortedMyDomains(q, sortMode);
  const total = filtered.length;
  const totalPages = Math.ceil(total / MY_DOM_PER_PAGE) || 1;
  const safePage = Math.min(Math.max(1, pageNum), totalPages);
  const slice = filtered.slice((safePage - 1) * MY_DOM_PER_PAGE, safePage * MY_DOM_PER_PAGE);

  if (!slice.length) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:22px;color:var(--txd)">لا توجد دومينات مطابقة للبحث في الشاشات التابعة لك</td></tr>';
    if (pagBox) pagBox.innerHTML = '';
    return;
  }

  tbody.innerHTML = slice.map((item, idx) => {
    const rowNum = (safePage - 1) * MY_DOM_PER_PAGE + idx + 1;
    const domStr = item.full_url || ('http://' + item.domain);
    return `
      <tr>
        <td><b>${rowNum}</b></td>
        <td>
          <span class="code-pill" dir="ltr" onclick="copyPureCode('${esc(domStr)}')" title="اضغط لنسخ الدومين">${esc(domStr)}</span>
        </td>
        <td><b style="color:var(--gold-h)">${esc(item.server_name || 'سيرفر شاشاتي')}</b></td>
        <td>
          <span class="badge ok" style="margin-left:4px">📋 ${item.playlists_count || 0} قائمة</span>
          <span class="badge gold">📺 ${item.devices_count || 0} شاشة</span>
        </td>
        <td style="white-space:nowrap">
          <button type="button" class="btn primary" style="padding:6px 11px;font-size:11.5px" onclick="selectMyOldDomainFromTable('${esc(item.domain)}', ${isModal ? 'true' : 'false'})">
            🎯 اختيار كدومين قديم
          </button>
          <button type="button" class="btn outline" style="padding:6px 9px;font-size:11.5px" onclick="copyPureCode('${esc(domStr)}')" title="نسخ الدومين">📋</button>
        </td>
      </tr>
    `;
  }).join('');

  if (pagBox) {
    pagBox.innerHTML = buildMyDomainsPaginationHtml(total, safePage, MY_DOM_PER_PAGE, pageFnName);
  }
}

function onSearchMyDomainsInline() {
  myDomInlinePage = 1;
  renderMyDomainsTableSection('myDomainsInlineBody', 'myDomainsInlinePagination', 'myDomInlineSearch', 'myDomInlineSort', myDomInlinePage, 'goToMyDomInlinePage', false);
}

function goToMyDomInlinePage(p) {
  myDomInlinePage = p;
  renderMyDomainsTableSection('myDomainsInlineBody', 'myDomainsInlinePagination', 'myDomInlineSearch', 'myDomInlineSort', myDomInlinePage, 'goToMyDomInlinePage', false);
}

function onSearchMyDomainsModal() {
  myDomModalPage = 1;
  renderMyDomainsTableSection('myDomainsModalBody', 'myDomainsModalPagination', 'myDomModalSearch', 'myDomModalSort', myDomModalPage, 'goToMyDomModalPage', true);
}

function goToMyDomModalPage(p) {
  myDomModalPage = p;
  renderMyDomainsTableSection('myDomainsModalBody', 'myDomainsModalPagination', 'myDomModalSearch', 'myDomModalSort', myDomModalPage, 'goToMyDomModalPage', true);
}

function openMyDomainsModal() {
  myDomModalPage = 1;
  if ($('myDomModalSearch')) $('myDomModalSearch').value = '';
  renderMyDomainsTableSection('myDomainsModalBody', 'myDomainsModalPagination', 'myDomModalSearch', 'myDomModalSort', myDomModalPage, 'goToMyDomModalPage', true);
  openModal('myDomainsModal');
  setTimeout(() => { if ($('myDomModalSearch')) $('myDomModalSearch').focus(); }, 100);
}

function renderMyActiveDomains() {
  const badge = $('lblOwnedDevicesBadge');
  if (badge) badge.textContent = `📺 شاشاتك المفعّلة: ${STATE.ownedDevicesCount || 0}`;
  const cntModal = $('lblMyDomainsModalCount');
  if (cntModal) cntModal.textContent = (STATE.domains || []).length;
  renderMyDomainsTableSection('myDomainsInlineBody', 'myDomainsInlinePagination', 'myDomInlineSearch', 'myDomInlineSort', myDomInlinePage, 'goToMyDomInlinePage', false);
  renderMyDomainsTableSection('myDomainsModalBody', 'myDomainsModalPagination', 'myDomModalSearch', 'myDomModalSort', myDomModalPage, 'goToMyDomModalPage', true);
}

async function bulkReplaceMyDomainNow() {
  const old_domain = ($('myBulkOldDomain').value || '').trim();
  const new_domain = ($('myBulkNewDomain').value || '').trim();
  if (!old_domain || !new_domain) {
    return toast('⚠️ يرجى إدخال الدومين القديم المستهدف والدومين الجديد المقترح', 'error');
  }
  if (!confirm(`⚠️ تأكيد استبدال الدومين القديم:\\n${old_domain}\\n\\nبالدومين الجديد المقترح:\\n${new_domain}\\n\\nسيتم تطبيق التغيير حصرياً على الشاشات التي قمت أنت بتفعيلها (${STATE.ownedDevicesCount || 0} شاشة). هل تريد المتابعة؟`)) {
    return;
  }
  const btn = $('btnMyBulkDomain');
  const orig = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = '⏳ جارٍ التحديث الجماعي لشاشاتك...';
  try {
    const res = await rpApi('bulk_replace_domain', { old_domain, new_domain });
    toast(res.message || '✅ تم استبدال الدومين بنجاح', 'success');
    $('myBulkOldDomain').value = '';
    $('myBulkNewDomain').value = '';
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    btn.disabled = false;
    btn.innerHTML = orig;
  }
}

function renderSubResellers() {
  const list = STATE.subResellers || [];
  const cntEl = $('lblSubResellersCount');
  if (cntEl) cntEl.textContent = list.length;
  const tbody = $('subResellersBody');
  if (!tbody) return;
  if (!list.length) {
    tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:22px;color:var(--txd)">لا يوجد موزعون فرعيون حالياً — اضغط على "➕ فتح حساب موزع فرعي" لإضافة موزع معك</td></tr>';
    return;
  }
  tbody.innerHTML = list.map(r => `
    <tr>
      <td>
        <b>${esc(r.display_name)}</b>
        ${r.phone ? `<div style="font-size:11px;color:var(--txd)" dir="ltr">${esc(r.phone)}</div>` : ''}
        ${r.notes ? `<div style="font-size:11px;color:var(--txd)">${esc(r.notes)}</div>` : ''}
      </td>
      <td><span class="code-pill" dir="ltr" onclick="copyPureCode('${esc(r.username)}')">${esc(r.username)}</span></td>
      <td><span class="code-pill" dir="ltr" onclick="copyPureCode('${esc(r.password_plain || '')}')">${esc(r.password_plain || '••••••')}</span></td>
      <td><span class="badge gold" style="font-size:13px">💎 ${r.points} نقطة</span></td>
      <td>
        <b>${r.total_cards_generated || 0}</b> قسيمة
        <div style="font-size:11px;color:var(--txd)">متاح: ${r.unused_cards_count || 0} | مفعل: ${r.used_cards_count || 0}</div>
      </td>
      <td><span class="badge ${r.is_active == 1 ? 'ok' : 'dn'}">${r.is_active == 1 ? 'نشط' : 'موقوف'}</span></td>
      <td style="white-space:nowrap">
        <button type="button" class="btn primary" style="padding:6px 10px;font-size:11.5px" onclick="openSubPointsModal(${r.id})">💎 نقاط</button>
        <button type="button" class="btn outline" style="padding:6px 10px;font-size:11.5px" onclick="copySubResellerCredentials(${r.id})" title="نسخ بيانات الدخول لإرسالها للموزع الفرعي">📋 نسخ الدخول</button>
        <button type="button" class="btn outline" style="padding:6px 10px;font-size:11.5px" onclick="openEditSubResellerModal(${r.id})" title="تعديل">✏️</button>
        <button type="button" class="btn outline" style="padding:6px 10px;font-size:11.5px;border-color:var(--dn);color:var(--dn)" onclick="deleteSubReseller(${r.id})" title="حذف واسترداد النقاط المتبقية">🗑</button>
      </td>
    </tr>
  `).join('');
}

function openAddSubResellerModal() {
  $('subAddDisplayName').value = '';
  $('subAddUsername').value = '';
  $('subAddPassword').value = Math.random().toString(36).slice(-6).toUpperCase();
  $('subAddInitialPts').value = '0';
  $('subAddPhone').value = '';
  $('subAddNotes').value = '';
  openModal('addSubResellerModal');
}

async function createSubResellerNow() {
  const display_name = $('subAddDisplayName').value.trim();
  const username = $('subAddUsername').value.trim().toLowerCase();
  const password = $('subAddPassword').value.trim();
  const initial_points = parseInt($('subAddInitialPts').value) || 0;
  const phone = $('subAddPhone').value.trim();
  const notes = $('subAddNotes').value.trim();
  if (!username || !password) return toast('⚠️ يرجى إدخال اسم المستخدم وكلمة المرور للموزع الفرعي', 'error');
  try {
    const res = await rpApi('add_sub_reseller', { display_name, username, password, initial_points, phone, notes });
    toast(res.message || '✅ تم فتح حساب الموزع الفرعي بنجاح', 'success');
    closeModal('addSubResellerModal');
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  }
}

function openSubPointsModal(subId) {
  const sub = (STATE.subResellers || []).find(x => x.id == subId);
  if (!sub) return;
  $('subPtsTargetId').value = sub.id;
  $('subPtsModalTitle').textContent = `💎 إدارة نقاط الموزع الفرعي: ${sub.display_name}`;
  $('subPtsModalSub').textContent = `رصيدك الحالي: (${STATE.stats.points_balance || 0} نقطة) — رصيد الموزع الفرعي الحالي: (${sub.points} نقطة)`;
  $('subPtsMode').value = 'add';
  $('subPtsAmount').value = '5';
  $('subPtsReason').value = '';
  openModal('subPointsModal');
}

async function submitSubPointsTransfer() {
  const sub_reseller_id = $('subPtsTargetId').value;
  const mode = $('subPtsMode').value;
  const points = parseInt($('subPtsAmount').value) || 0;
  const notes = $('subPtsReason').value.trim();
  if (points <= 0) return toast('⚠️ يرجى إدخال عدد نقاط صحيح أكبر من الصفر', 'error');
  try {
    const res = await rpApi('transfer_sub_points', { sub_reseller_id, mode, points, notes });
    toast(res.message || '✅ تمت العملية بنجاح', 'success');
    closeModal('subPointsModal');
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  }
}

function openEditSubResellerModal(subId) {
  const sub = (STATE.subResellers || []).find(x => x.id == subId);
  if (!sub) return;
  $('editSubId').value = sub.id;
  $('editSubDisplayName').value = sub.display_name || '';
  $('editSubPassword').value = '';
  $('editSubPhone').value = sub.phone || '';
  $('editSubActive').value = sub.is_active == 1 ? '1' : '0';
  $('editSubNotes').value = sub.notes || '';
  openModal('editSubResellerModal');
}

async function saveSubResellerEdit() {
  const sub_reseller_id = $('editSubId').value;
  const display_name = $('editSubDisplayName').value.trim();
  const password = $('editSubPassword').value.trim();
  const phone = $('editSubPhone').value.trim();
  const is_active = $('editSubActive').value;
  const notes = $('editSubNotes').value.trim();
  try {
    const res = await rpApi('update_sub_reseller', { sub_reseller_id, display_name, password, phone, is_active, notes });
    toast(res.message || '✅ تم التحديث بنجاح', 'success');
    closeModal('editSubResellerModal');
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  }
}

async function deleteSubReseller(subId) {
  const sub = (STATE.subResellers || []).find(x => x.id == subId);
  if (!sub) return;
  if (!confirm(`⚠️ هل أنت متأكد من حذف حساب الموزع الفرعي (${sub.display_name})؟\\nسيتم استرداد أي نقاط متبقية في رصيده (${sub.points} نقطة) وإعادتها إلى رصيدك تلقائياً.`)) return;
  try {
    const res = await rpApi('delete_sub_reseller', { sub_reseller_id: subId });
    toast(res.message || '🗑 تم حذف الموزع الفرعي بنجاح', 'success');
    await loadDashboard();
  } catch (err) {
    toast(err.message, 'error');
  }
}

function copySubResellerCredentials(subId) {
  const sub = (STATE.subResellers || []).find(x => x.id == subId);
  if (!sub) return;
  const portalUrl = location.origin + location.pathname;
  const msg = `🏪 بيانات الدخول إلى لوحة الموزع — LIVE PRO\n🌐 الرابط: ${portalUrl}\n👤 اسم المستخدم: ${sub.username}\n🔑 كلمة المرور: ${sub.password_plain || '—'}\n💎 الرصيد الحالي: ${sub.points} نقطة`;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(msg).then(() => toast('📋 تم نسخ بيانات دخول الموزع الفرعي مع الرابط', 'success'));
  } else {
    copyPureCode(msg);
  }
}

async function changeMyPassword() {
  const old_password = $('oldResPass').value.trim();
  const new_password = $('newResPass').value.trim();
  if (!old_password || !new_password) return toast('يرجى إدخال كلمة المرور الحالية والجديدة', 'error');
  try {
    const res = await rpApi('change_password', { old_password, new_password });
    toast(res.message, 'success');
    $('oldResPass').value = '';
    $('newResPass').value = '';
    closeModal('passModal');
  } catch (err) {
    toast(err.message, 'error');
  }
}

document.querySelectorAll('.modal-overlay').forEach(m => {
  m.addEventListener('click', e => { if (e.target === m) m.classList.remove('active'); });
});

(function init() {
  if (RESELLER_TOKEN) {
    loadDashboard();
  } else {
    showLoginView();
  }
})();
</script>
</body>
</html>
