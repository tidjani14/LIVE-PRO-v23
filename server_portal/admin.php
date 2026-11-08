<?php
require_once __DIR__ . '/db.php';
try { $adminPdo = getDB(); } catch (Exception $e) { die('DB Error'); }

// معالج مباشر مدمج داخل admin.php لإعدادات تحديث التطبيق لضمان عمل الحفظ 100% حتى لو كان api.php قديماً أو مقيّداً
if (!empty($_REQUEST['admin_direct_action'])) {
    header('Content-Type: application/json; charset=utf-8');
    $act = trim((string)$_REQUEST['admin_direct_action']);
    try {
        $dbs = [$adminPdo];
        $secPath = __DIR__ . '/livepro_portal.sqlite';
        if (is_file($secPath)) {
            try {
                $dbs[] = new PDO('sqlite:' . $secPath, null, null, [
                    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
                ]);
            } catch (Throwable $e) {}
        }
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
            } catch (Throwable $e) {}
        }

        if ($act === 'admin_get_app_update') {
            $default = [
                'enabled'      => false,
                'version_code' => 27,
                'version_name' => '27.0',
                'apk_url'      => '',
                'title'        => '📲 تحديث جديد متوفر للتطبيق',
                'notes'        => 'يرجى ترقية نسخة التطبيق للحصول على أحدث المزايا وتحسينات الأداء.',
                'is_mandatory' => true,
                'updated_at'   => ''
            ];
            $jsonCandidate = null;
            if (is_file(__DIR__ . '/app_update_config.json')) {
                $j = @json_decode((string)@file_get_contents(__DIR__ . '/app_update_config.json'), true);
                if (is_array($j) && trim((string)($j['apk_url'] ?? '')) !== '') {
                    $jsonCandidate = array_merge($default, $j);
                    $jsonCandidate['enabled'] = !empty($j['enabled']) && trim((string)($j['apk_url'] ?? '')) !== '';
                }
            }
            $dbCandidate = null;
            foreach ($dbs as $pdo) {
                try {
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
            $found = $jsonCandidate ?? $dbCandidate ?? $default;
            if ($jsonCandidate !== null && $dbCandidate !== null) {
                $found = ($jsonCandidate['updated_at'] >= $dbCandidate['updated_at']) ? $jsonCandidate : $dbCandidate;
            }
            echo json_encode([
                'status'     => 'success',
                'success'    => true,
                'app_update' => $found,
                'data'       => ['app_update' => $found]
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }

        if ($act === 'admin_toggle_portal_main') {
            $rawDev = strtoupper(trim((string)($_POST['device_id'] ?? $_REQUEST['device_id'] ?? '')));
            if (preg_match('/^[A-Z0-9]{4}-[A-Z0-9]{4}$/', $rawDev)) $rawDev = 'DEV-' . $rawDev;
            if ($rawDev === '' || !preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $rawDev)) {
                echo json_encode(['success' => false, 'error' => '⚠️ يرجى إدخال رمز الشاشة الصحيح (مثال: DEV-A1B2-C3D4)'], JSON_UNESCAPED_UNICODE);
                exit;
            }
            $enableFlag = !empty($_POST['portal_main_enabled']) ? 1 : 0;
            foreach ($dbs as $pdo) {
                try {
                    $pdo->exec("ALTER TABLE users ADD COLUMN portal_main_enabled INTEGER NOT NULL DEFAULT 0");
                } catch (Throwable $e) {}
                try {
                    $st = $pdo->prepare("SELECT id FROM users WHERE UPPER(device_id) = ? LIMIT 1");
                    $st->execute([$rawDev]);
                    $uid = $st->fetchColumn();
                    if ($uid) {
                        $pdo->prepare("UPDATE users SET portal_main_enabled = ?, updated_at = datetime('now') WHERE id = ?")->execute([$enableFlag, $uid]);
                    } else {
                        $expDef = date('Y-m-d', strtotime('+30 days'));
                        $pdo->prepare("INSERT INTO users (device_id, device_name, expire_date, is_active, portal_main_enabled, trial_used, created_at, updated_at, registration_source) VALUES (?, 'جهاز مشترك', ?, 1, ?, 1, datetime('now'), datetime('now'), 'admin')")->execute([$rawDev, $expDef, $enableFlag]);
                    }
                } catch (Throwable $e) {}
            }
            echo json_encode([
                'success' => true,
                'data'    => [
                    'device_id'           => $rawDev,
                    'portal_main_enabled' => $enableFlag,
                    'message'             => $enableFlag
                        ? "📡 تم تفعيل القائمة الرئيسية للجهاز ({$rawDev}) بنجاح!"
                        : "🚫 تم إيقاف وإخفاء القائمة الرئيسية عن الجهاز ({$rawDev}) بنجاح!"
                ]
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }

        if ($act === 'admin_save_app_update') {
            $enabled     = !empty($_POST['update_enabled']) ? 1 : 0;
            $versionCode = max(1, (int)($_POST['version_code'] ?? 27));
            $versionName = trim((string)($_POST['version_name'] ?? ($versionCode . '.0'))) ?: ($versionCode . '.0');
            $apkUrl      = trim((string)($_POST['apk_url'] ?? ''));
            $apkUrl      = preg_replace('~^https?://(?:www\.)?google\.[^/]+/search\?(?:.*&)?q=~i', '', $apkUrl);
            $title       = trim((string)($_POST['update_title'] ?? '📲 تحديث جديد متوفر للتطبيق')) ?: '📲 تحديث جديد متوفر للتطبيق';
            $notes       = trim((string)($_POST['update_notes'] ?? 'يرجى ترقية نسخة التطبيق للحصول على أحدث المزايا وتحسينات الأداء.'));
            $mandatory   = (!isset($_POST['is_mandatory']) || !empty($_POST['is_mandatory'])) ? 1 : 0;
            $nowStr      = date('Y-m-d H:i:s');

            if ($enabled && ($apkUrl === '' || !preg_match('~^https?://~i', $apkUrl))) {
                echo json_encode(['success' => false, 'error' => '⚠️ عند تفعيل إشعار التحديث، يرجى إدخال رابط مباشر صالح لملف الـ APK يبدأ بـ http:// أو https://'], JSON_UNESCAPED_UNICODE);
                exit;
            }

            foreach ($dbs as $pdo) {
                try {
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

            echo json_encode([
                'success' => true,
                'data'    => [
                    'app_update' => $savedObj,
                    'message'    => $savedObj['enabled']
                        ? "🚀 تم تفعيل إشعار التحديث للإصدار (v{$savedObj['version_name']}) بنجاح! سينبثق تلقائياً لجميع الزبائن عند فتح التطبيق لتحميله وتثبيته."
                        : "✅ تم حفظ إعدادات التحديث (إشعار التحديث متوقف حالياً)"
                ]
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
    } catch (Throwable $e) {
        echo json_encode(['success' => false, 'error' => 'خطأ: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE);
        exit;
    }
}

$appName = htmlspecialchars(getSetting('app_name', 'منصة البث المباشر'));
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>لوحة التحكم | <?= $appName ?></title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📺</text></svg>">
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700;800;900&display=swap" rel="stylesheet">
<style>
:root{
  --bg:#0f172a;--sf:#1e293b;--sf2:#334155;
  --bd:#334155;--tx:#f8fafc;--txd:#94a3b8;
  --gold:#f59e0b;--gold-h:#fbbf24;--gold-bg:rgba(245,158,11,0.15);
  --dn:#ef4444;--dn-bg:rgba(239,68,68,0.15);
  --ok:#10b981;--ok-bg:rgba(16,185,129,0.15);
  --inf:#3b82f6;--inf-bg:rgba(59,130,246,0.15);
  --transition:0.22s ease;
}
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:'Cairo',sans-serif;background:var(--bg);color:var(--tx);min-height:100vh;display:flex;flex-direction:column;overflow-x:hidden}

/* Header */
header{background:var(--sf);border-bottom:1px solid var(--bd);padding:0 24px;display:flex;align-items:center;justify-content:space-between;height:70px;position:sticky;top:0;z-index:100}
.logo{font-size:20px;font-weight:900;color:var(--gold);display:flex;align-items:center;gap:10px}
.top-nav{display:flex;gap:6px;height:100%}
.nav-btn{background:none;border:none;color:var(--txd);font-family:inherit;font-weight:700;font-size:14px;padding:0 20px;cursor:pointer;display:flex;align-items:center;gap:8px;transition:var(--transition);border-bottom:3px solid transparent;height:100%}
.nav-btn:hover{color:var(--tx);background:var(--sf2)}
.nav-btn.active{color:var(--gold);border-bottom-color:var(--gold);background:linear-gradient(to top,var(--gold-bg),transparent)}
.nav-btn.gold-link{color:var(--gold);text-decoration:none;margin-right:10px;border:1px solid var(--gold);border-radius:8px;height:40px;margin-top:15px;padding:0 15px}
.nav-btn.gold-link:hover{background:var(--gold);color:#000}
.header-actions{display:flex;gap:8px}
.icon-btn{background:var(--sf2);border:1px solid var(--bd);color:var(--txd);width:40px;height:40px;border-radius:8px;display:flex;align-items:center;justify-content:center;cursor:pointer;transition:var(--transition);font-size:16px}
.icon-btn:hover{background:var(--bd);color:var(--tx)}
.icon-btn.danger:hover{background:var(--dn);color:#fff;border-color:var(--dn)}

main{flex:1;padding:24px;max-width:1400px;margin:0 auto;width:100%}

/* Stats */
.stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:16px;margin-bottom:24px}
.stat-card{background:var(--sf);border:1px solid var(--bd);border-radius:14px;padding:20px 16px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;transition:var(--transition);position:relative;overflow:hidden}
.stat-card:hover{transform:translateY(-3px);border-color:var(--gold);box-shadow:0 8px 20px rgba(0,0,0,0.2)}
.stat-icon{font-size:28px;margin-bottom:8px}
.stat-val{font-size:26px;font-weight:900;color:var(--tx);line-height:1;margin-bottom:6px}
.stat-lbl{font-size:12px;color:var(--txd);font-weight:700}
.stat-card.ok .stat-val{color:var(--ok)}
.stat-card.dn .stat-val{color:var(--dn)}
.stat-card.inf .stat-val{color:var(--inf)}
.stat-card::before{content:'';position:absolute;top:0;left:0;right:0;height:3px;background:var(--gold);opacity:0.8}
.stat-card.ok::before{background:var(--ok)}
.stat-card.dn::before{background:var(--dn)}
.stat-card.inf::before{background:var(--inf)}

.tab-pane{display:none;animation:fadeIn 0.3s ease}
.tab-pane.active{display:block}
@keyframes fadeIn{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}

.card{background:var(--sf);border:1px solid var(--bd);border-radius:14px;margin-bottom:24px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,0.1)}
.card-header{padding:16px 20px;border-bottom:1px solid var(--bd);display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;background:rgba(255,255,255,0.02)}
.card-title{font-size:18px;font-weight:800;color:var(--gold);display:flex;align-items:center;gap:8px}
.card-body{padding:20px}

/* Category Chips */
.cat-chips{display:flex;gap:8px;flex-wrap:wrap;padding:4px 0 16px;border-bottom:1px solid var(--bd);margin-bottom:16px}
.cat-chip{display:inline-flex;align-items:center;gap:8px;padding:7px 6px 7px 14px;background:var(--bg);border:1.5px solid var(--bd);border-radius:24px;font-size:13px;font-weight:700;cursor:pointer;transition:var(--transition);user-select:none}
.cat-chip:hover{border-color:var(--gold);background:var(--gold-bg);transform:translateY(-1px)}
.cat-chip.active{background:var(--gold);color:#000;border-color:var(--gold);box-shadow:0 4px 12px var(--gold-bg)}
.cat-chip .chip-count{background:rgba(0,0,0,0.15);padding:2px 8px;border-radius:12px;font-size:11px;font-weight:900;min-width:22px;text-align:center}
.cat-chip.active .chip-count{background:rgba(0,0,0,0.25)}
.cat-chip .chip-actions{display:inline-flex;gap:2px;margin-right:2px}
.cat-chip .chip-btn{background:rgba(0,0,0,0.15);border:none;color:inherit;width:24px;height:24px;border-radius:50%;cursor:pointer;font-size:12px;display:flex;align-items:center;justify-content:center;transition:var(--transition);font-family:inherit}
.cat-chip .chip-btn:hover{background:var(--dn);color:#fff;transform:scale(1.1)}
.cat-chip .chip-btn.power:hover{background:var(--ok);color:#fff}

.toolbar{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px}
.search-box{flex:1;min-width:250px;position:relative;display:flex;align-items:center}
.search-box input{width:100%;padding:12px 16px 12px 40px;background:var(--bg);border:1px solid var(--bd);border-radius:10px;color:var(--tx);font-family:inherit;font-size:14px;transition:var(--transition)}
.search-box input:focus{outline:none;border-color:var(--gold);box-shadow:0 0 0 3px var(--gold-bg)}
.search-box .search-icon{position:absolute;right:14px;color:var(--txd);font-size:16px}
.search-box .clear-btn{position:absolute;left:12px;background:none;border:none;color:var(--txd);font-size:18px;cursor:pointer;display:none}
.filter-select{padding:12px 16px;background:var(--bg);border:1px solid var(--bd);border-radius:10px;color:var(--tx);font-family:inherit;font-size:14px;min-width:150px;cursor:pointer}
.filter-select:focus{outline:none;border-color:var(--gold)}

.btn{padding:10px 18px;border-radius:10px;font-weight:700;cursor:pointer;border:none;font-family:inherit;transition:var(--transition);display:inline-flex;align-items:center;justify-content:center;gap:8px;font-size:13px;white-space:nowrap}
.btn.primary{background:var(--gold);color:#000}
.btn.primary:hover{background:var(--gold-h);transform:translateY(-1px);box-shadow:0 4px 12px var(--gold-bg)}
.btn.outline{background:transparent;border:1px solid var(--bd);color:var(--tx)}
.btn.outline:hover{border-color:var(--gold);color:var(--gold);background:var(--gold-bg)}
.btn.danger{background:var(--dn-bg);color:var(--dn);border:1px solid rgba(239,68,68,0.3)}
.btn.danger:hover{background:var(--dn);color:#fff}
.btn.success{background:var(--ok-bg);color:var(--ok);border:1px solid rgba(16,185,129,0.3)}
.btn.success:hover{background:var(--ok);color:#fff}
.btn.icon-only{padding:8px;width:34px;height:34px;border-radius:8px}
.btn:disabled{opacity:0.55;cursor:not-allowed;transform:none !important}

.table-responsive{overflow-x:auto;border-radius:10px;border:1px solid var(--bd)}
table{width:100%;border-collapse:collapse;font-size:13.5px;white-space:nowrap}
th{background:var(--bg);color:var(--txd);font-weight:800;padding:14px 16px;text-align:right;border-bottom:1px solid var(--bd);position:sticky;top:0;z-index:2}
td{padding:12px 16px;border-bottom:1px solid var(--bd);vertical-align:middle;color:var(--tx)}
tr:last-child td{border-bottom:none}
tr:hover td{background:rgba(255,255,255,0.03)}
tr.selected td{background:rgba(245,158,11,0.10) !important}
tr[data-ch-id]{transition:background 0.15s;cursor:pointer}
.checkbox-col{width:40px;text-align:center;padding-left:8px !important;padding-right:8px !important}
.row-check,.select-all-check{width:18px;height:18px;cursor:pointer;accent-color:var(--gold);vertical-align:middle}

.inline-edit{cursor:pointer;padding:2px 8px;border-radius:6px;transition:var(--transition);display:inline-block;min-width:30px;text-align:center;font-weight:700;color:var(--gold);border:1px dashed transparent}
.inline-edit:hover{background:var(--gold-bg);border-color:var(--gold)}
.inline-input{padding:3px 6px !important;background:var(--bg) !important;border:1.5px solid var(--gold) !important;border-radius:6px !important;color:var(--tx) !important;font-family:inherit !important;font-size:13px !important;text-align:center;width:70px}

.toggle-switch{position:relative;display:inline-block;width:44px;height:24px;cursor:pointer;vertical-align:middle}
.toggle-switch input{opacity:0;width:0;height:0;position:absolute}
.toggle-slider{position:absolute;inset:0;background:#475569;border-radius:24px;transition:0.2s}
.toggle-slider::before{content:"";position:absolute;height:18px;width:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:0.2s;box-shadow:0 1px 3px rgba(0,0,0,0.3)}
.toggle-switch input:checked + .toggle-slider{background:var(--ok)}
.toggle-switch input:checked + .toggle-slider::before{transform:translateX(20px)}

.bulk-bar{position:fixed;bottom:20px;left:50%;transform:translateX(-50%) translateY(150px);background:var(--sf);border:1px solid var(--gold);border-radius:14px;padding:12px 18px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;box-shadow:0 10px 40px rgba(0,0,0,0.6);z-index:800;transition:0.3s cubic-bezier(0.175,0.885,0.32,1.275);opacity:0;pointer-events:none;max-width:calc(100vw - 32px)}
.bulk-bar.show{transform:translateX(-50%) translateY(0);opacity:1;pointer-events:auto}
.bulk-count{background:var(--gold);color:#000;padding:6px 14px;border-radius:20px;font-weight:900;font-size:13px;white-space:nowrap}
.bulk-bar .btn{padding:8px 14px;font-size:12.5px}

.badge{display:inline-flex;align-items:center;padding:4px 10px;border-radius:20px;font-size:11px;font-weight:800}
.badge.ok{background:var(--ok-bg);color:var(--ok)}
.badge.dn{background:var(--dn-bg);color:var(--dn)}
.badge.warn{background:var(--gold-bg);color:var(--gold)}
.badge.info{background:var(--inf-bg);color:var(--inf)}
.url-text{font-family:monospace;font-size:12px;color:var(--txd);max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;display:inline-block;vertical-align:middle;direction:ltr;text-align:left}
.code-text{font-family:monospace;font-size:12.5px;background:var(--bg);padding:4px 8px;border-radius:6px;color:var(--gold);cursor:pointer;letter-spacing:1.2px;border:1px solid var(--bd);direction:ltr;unicode-bidi:isolate;display:inline-block;text-align:center;font-weight:800}
.pts-badge{display:inline-flex;align-items:center;gap:5px;background:linear-gradient(135deg,rgba(245,158,11,0.22),rgba(245,158,11,0.08));border:1.5px solid var(--gold);color:var(--gold-h);padding:5px 12px;border-radius:999px;font-weight:900;font-size:13px}
.preset-chips{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}
.preset-chip{background:var(--bg);border:1px solid var(--bd);color:var(--tx);padding:6px 12px;border-radius:8px;font-family:inherit;font-size:12px;font-weight:800;cursor:pointer;transition:var(--transition)}
.preset-chip:hover{border-color:var(--gold);color:var(--gold);background:var(--gold-bg)}

.pagination{display:flex;justify-content:center;align-items:center;gap:6px;margin-top:20px;flex-wrap:wrap}
.page-btn{background:var(--bg);border:1px solid var(--bd);color:var(--txd);width:36px;height:36px;border-radius:8px;display:flex;align-items:center;justify-content:center;cursor:pointer;font-weight:700;transition:var(--transition);font-family:inherit;font-size:13px}
.page-btn:hover:not(:disabled){border-color:var(--gold);color:var(--gold);background:var(--gold-bg)}
.page-btn.active{background:var(--gold);color:#000;border-color:var(--gold)}
.page-btn:disabled{opacity:0.4;cursor:not-allowed}
.page-btn.text-btn{width:auto;padding:0 12px}

.form-group{margin-bottom:16px}
.form-group label{display:block;margin-bottom:6px;font-size:13px;font-weight:700;color:var(--txd)}
.form-control{width:100%;padding:12px 16px;background:var(--bg);border:1px solid var(--bd);border-radius:10px;color:var(--tx);font-family:inherit;font-size:14px;transition:var(--transition)}
.form-control:focus{outline:none;border-color:var(--gold);box-shadow:0 0 0 3px var(--gold-bg)}
.grid-2{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.grid-3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px}

.modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);z-index:1000;display:none;align-items:center;justify-content:center;padding:20px}
.modal-overlay.active{display:flex}
.modal-content{background:var(--sf);border:1px solid var(--bd);border-radius:16px;width:100%;max-width:600px;max-height:90vh;overflow-y:auto;animation:modalIn 0.3s cubic-bezier(0.175,0.885,0.32,1.275)}
@keyframes modalIn{from{opacity:0;transform:scale(0.95) translateY(20px)}to{opacity:1;transform:scale(1) translateY(0)}}
.modal-header{padding:20px;border-bottom:1px solid var(--bd);display:flex;justify-content:space-between;align-items:center;position:sticky;top:0;background:var(--sf);z-index:10}
.modal-header h3{font-size:18px;font-weight:800;color:var(--gold)}
.modal-close{background:none;border:none;color:var(--txd);font-size:28px;cursor:pointer;line-height:1;width:36px;height:36px;border-radius:8px;transition:var(--transition)}
.modal-close:hover{color:var(--dn);background:var(--dn-bg)}
.modal-body{padding:20px}
.modal-footer{padding:20px;border-top:1px solid var(--bd);display:flex;justify-content:flex-end;gap:12px;position:sticky;bottom:0;background:var(--sf);z-index:10}

/* ========== إعادة ترتيب الباقات ========== */
.reorder-list{display:flex;flex-direction:column;gap:6px;max-height:55vh;overflow-y:auto;padding:2px}
.reorder-item{display:flex;align-items:center;gap:10px;padding:11px 14px;background:var(--bg);border:1px solid var(--bd);border-radius:10px;cursor:grab;transition:var(--transition);user-select:none}
.reorder-item:hover{border-color:var(--gold);background:var(--gold-bg)}
.reorder-item.dragging{opacity:.45;border-color:var(--gold);background:var(--gold-bg);cursor:grabbing}
.reorder-item .drag-handle{color:var(--txd);font-size:16px;letter-spacing:-3px;line-height:1}
.reorder-item .drag-label{flex:1;font-weight:700;font-size:14px}
.reorder-item .move-btns{display:flex;gap:4px}
.reorder-item .move-btn{background:var(--sf2);border:1px solid var(--bd);color:var(--txd);width:28px;height:28px;border-radius:6px;cursor:pointer;font-size:13px;display:flex;align-items:center;justify-content:center;transition:var(--transition);font-family:inherit}
.reorder-item .move-btn:hover:not(:disabled){background:var(--gold);color:#000;border-color:var(--gold)}
.reorder-item .move-btn:disabled{opacity:.3;cursor:not-allowed}

/* أزرار ↑↓ داخل الجدول */
.row-move-btns{display:inline-flex;gap:2px;vertical-align:middle;margin-left:4px}
.row-move-btn{background:var(--sf2);border:1px solid var(--bd);color:var(--txd);width:26px;height:26px;border-radius:6px;cursor:pointer;font-size:11px;display:inline-flex;align-items:center;justify-content:center;padding:0;transition:var(--transition);font-family:inherit}
.row-move-btn:hover:not(:disabled){background:var(--gold);color:#000;border-color:var(--gold)}
.row-move-btn:disabled{opacity:.25;cursor:not-allowed}

.bottom-nav{display:none;position:fixed;bottom:0;left:0;right:0;background:rgba(30,41,59,0.95);backdrop-filter:blur(10px);border-top:1px solid var(--bd);z-index:900;padding-bottom:env(safe-area-inset-bottom);justify-content:space-around;box-shadow:0 -4px 20px rgba(0,0,0,0.3)}
.bottom-nav-item{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:12px 8px;background:none;border:none;color:var(--txd);font-family:inherit;font-size:11px;font-weight:700;cursor:pointer;transition:var(--transition);text-decoration:none;position:relative}
.bottom-nav-item .icon{font-size:22px;margin-bottom:4px;transition:var(--transition)}
.bottom-nav-item.active{color:var(--gold)}
.bottom-nav-item.active .icon{transform:translateY(-3px)}
.bottom-nav-item.active::before{content:'';position:absolute;top:0;left:20%;right:20%;height:3px;background:var(--gold);border-radius:0 0 4px 4px}

.toast{position:fixed;bottom:80px;left:50%;transform:translateX(-50%) translateY(20px);background:var(--sf);border:1px solid var(--gold);color:var(--tx);padding:12px 24px;border-radius:10px;font-weight:700;font-size:14px;opacity:0;transition:0.3s;z-index:9999;box-shadow:0 10px 30px rgba(0,0,0,0.5);pointer-events:none;text-align:center;width:max-content;max-width:90vw}
.toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
.toast.error{border-color:var(--dn);background:var(--dn-bg);color:#fff}
.toast.success{border-color:var(--ok);background:var(--ok-bg);color:#fff}

@media (max-width: 900px) {
  .top-nav { display: none; }
  .bottom-nav { display: flex; }
  main { padding: 16px; padding-bottom: 90px; }
  .stats-grid { grid-template-columns: repeat(2, 1fr); gap: 12px; }
  .stat-card { padding: 16px 12px; }
  .stat-val { font-size: 22px; }
  .grid-2, .grid-3 { grid-template-columns: 1fr; }
  .hide-mobile { display: none !important; }
  header { padding: 0 16px; height: 60px; }
  .logo { font-size: 18px; }
  .bulk-bar { bottom: 90px; }
}
@media (max-width: 480px) {
  .toolbar { flex-direction: column; }
  .search-box, .filter-select { width: 100%; }
  .btn { width: 100%; }
  .card-header .btn { width: auto; }
  .bulk-bar { padding: 10px 12px; gap: 6px; }
  .bulk-bar .btn { padding: 7px 10px; font-size: 11.5px; }
  .bulk-count { padding: 5px 10px; font-size: 12px; }
}
</style>
</head>
<body>

<header>
  <div class="logo">📺 لوحة التحكم</div>
  <nav class="top-nav">
    <button class="nav-btn active" id="nav-channels" onclick="switchTab('channels', this)">📡 القنوات</button>
    <button class="nav-btn" id="nav-users" onclick="switchTab('users', this)">👥 المشتركين</button>
    <button class="nav-btn" onclick="switchTab('users'); setTimeout(()=>{const el=document.getElementById('bulkOldDomain'); if(el) el.focus();},120);" style="color:var(--inf)">🌐 تغيير الدومين</button>
    <button class="nav-btn" id="nav-cards" onclick="switchTab('cards', this)">🎫 البطاقات</button>
    <button class="nav-btn" id="nav-resellers" onclick="switchTab('resellers', this)">💼 الموزعين</button>
    <button class="nav-btn" id="nav-datatxt" onclick="switchTab('datatxt', this)" style="color:var(--gold)">🖥️ سيرفرات data.txt</button>
    <button class="nav-btn" id="nav-appupdate" onclick="switchTab('appupdate', this)" style="color:var(--ok)">📲 تحديث التطبيق</button>
    <a href="reseller_portal.php" target="_blank" class="nav-btn gold-link" style="border-color:var(--ok);color:var(--ok)">🏪 فضاء الموزعين ↗</a>
    <a href="categories.php" class="nav-btn gold-link">🎯 ترتيب الباقات ↗</a>
  </nav>
  <div class="header-actions">
    <button class="icon-btn" onclick="openModal('passModal')" title="تغيير كلمة المرور">🔑</button>
    <button class="icon-btn danger" onclick="doLogout()" title="خروج">🚪</button>
  </div>
</header>

<main>
  <div class="stats-grid">
    <div class="stat-card"><div class="stat-icon">📡</div><div class="stat-val" id="stChannels">—</div><div class="stat-lbl">إجمالي القنوات</div></div>
    <div class="stat-card ok"><div class="stat-icon">✅</div><div class="stat-val" id="stActiveCh">—</div><div class="stat-lbl">قنوات نشطة</div></div>
    <div class="stat-card inf"><div class="stat-icon">👥</div><div class="stat-val" id="stUsers">—</div><div class="stat-lbl">المشتركين</div></div>
    <div class="stat-card ok"><div class="stat-icon">🟢</div><div class="stat-val" id="stActiveUsers">—</div><div class="stat-lbl">اشتراكات نشطة</div></div>
    <div class="stat-card dn"><div class="stat-icon">🔴</div><div class="stat-val" id="stExpiredUsers">—</div><div class="stat-lbl">اشتراكات منتهية</div></div>
    <div class="stat-card"><div class="stat-icon">🎫</div><div class="stat-val" id="stCardsUnused">—</div><div class="stat-lbl">بطاقات متاحة</div></div>
    <div class="stat-card inf" style="cursor:pointer" onclick="switchTab('resellers')"><div class="stat-icon">💼</div><div class="stat-val" id="stResellers">—</div><div class="stat-lbl">الموزعين النشطين</div></div>
    <div class="stat-card" style="cursor:pointer" onclick="switchTab('resellers')"><div class="stat-icon">💎</div><div class="stat-val" id="stResellerPoints">—</div><div class="stat-lbl">نقاط الموزعين</div></div>
  </div>

  <!-- CHANNELS -->
  <div id="tab-channels" class="tab-pane active">
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">📋 قائمة القنوات</h2>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn outline" onclick="openReorderCatsModal()">🔀 ترتيب الباقات</button>
          <button class="btn outline" onclick="openModal('m3uModal')">📥 M3U</button>
          <button class="btn primary" onclick="openModal('addChannelModal')">➕ إضافة قناة</button>
        </div>
      </div>
      <div class="card-body">
        <div class="cat-chips" id="catChips"></div>
        <div class="toolbar">
          <div class="search-box">
            <span class="search-icon">🔍</span>
            <input type="text" id="chSearch" placeholder="بحث ذكي: الاسم، الرقم، أو الباقة..." oninput="onSearchChannels()">
            <button class="clear-btn" id="chClearBtn" onclick="clearSearch()">×</button>
          </div>
          <select id="chFilterCat" class="filter-select" onchange="onSearchChannels()">
            <option value="">كل الباقات</option>
          </select>
        </div>
        <div class="table-responsive">
          <table>
            <thead>
              <tr>
                <th class="checkbox-col"><input type="checkbox" class="select-all-check" id="selectAllCh" onchange="toggleSelectAll()"></th>
                <th style="width:80px">الرقم</th>
                <th>اسم القناة</th>
                <th>الباقة</th>
                <th class="hide-mobile">الرابط الأصلي</th>
                <th style="width:70px">الحالة</th>
                <th style="width:190px">إجراءات</th>
              </tr>
            </thead>
            <tbody id="channelsBody"><tr><td colspan="7" style="text-align:center;padding:30px">جارٍ التحميل...</td></tr></tbody>
          </table>
        </div>
        <div class="pagination" id="chPagination"></div>
      </div>
    </div>
  </div>

  <!-- USERS -->
  <div id="tab-users" class="tab-pane">
    <div class="card" style="border-color:var(--inf);margin-bottom:20px">
      <div class="card-header" style="flex-wrap:wrap;gap:10px">
        <h2 class="card-title" style="color:var(--inf)">🌐 التغيير التلقائي الجماعي للدومين (لجميع المستخدمين دفعة واحدة)</h2>
        <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
          <button class="btn primary" onclick="openActiveDomainsModal()" style="font-size:12.5px;padding:7px 14px;background:var(--inf);color:#fff">
            📋 نافذة كشف الدومينات المسجلة (<span id="lblAdminDomainsCount">0</span>)
          </button>
          <button class="btn outline" onclick="loadActiveDomains(true)" style="font-size:12px;padding:7px 12px">🔄 تحديث الكشف</button>
        </div>
      </div>
      <div class="card-body">
        <p style="font-size:13px;color:var(--txd);margin-bottom:12px;line-height:1.7">
          يتيح لك هذا القسم استبدال الدومين القديم المتوقف بدومين جديد لجميع المشتركين (في قوائمهم المشفرة، القنوات، المفضلة، وملف <b>data.txt</b>) بضغطة واحدة. استعرض الدومينات المسجلة في الجدول المنظّم أدناه (10 دومينات في كل صفحة) أو افتح <b>النافذة المنبثقة</b> للبحث والاختيار الفوري:
        </p>

        <!-- جدول كشف الدومينات المسجلة داخل الصفحة (كل 10 دومينات في جدول مع محرك بحث ذكي) -->
        <div style="background:rgba(10,14,26,0.75);border:1px solid var(--bd);border-radius:12px;padding:14px;margin-bottom:16px">
          <div class="toolbar" style="margin-bottom:12px">
            <div class="search-box" style="flex:1;min-width:220px">
              <span class="search-icon">🔍</span>
              <input type="text" id="admDomInlineSearch" placeholder="بحث ذكي بالدومين، البورت، أو اسم السيرفر..." oninput="onSearchAdminDomainsInline()">
              <button class="clear-btn" onclick="document.getElementById('admDomInlineSearch').value=''; onSearchAdminDomainsInline();" style="display:block">×</button>
            </div>
            <select id="admDomInlineSort" class="filter-select" onchange="onSearchAdminDomainsInline()">
              <option value="usage">📊 الترتيب: الأكثر استخداماً</option>
              <option value="devices">📺 الترتيب: الأكثر شاشات</option>
              <option value="channels">📡 الترتيب: الأكثر قنوات</option>
              <option value="az">🔤 الترتيب: الأبجدي (A-Z)</option>
            </select>
          </div>
          <div class="table-responsive">
            <table>
              <thead>
                <tr>
                  <th style="width:45px">#</th>
                  <th>🌐 الدومين / الهوست المسجل</th>
                  <th>🏷️ اسم السيرفر المرتبط</th>
                  <th>📊 الإحصائيات (القوائم / الشاشات / القنوات)</th>
                  <th style="width:210px">⚡ إجراء سريع</th>
                </tr>
              </thead>
              <tbody id="admDomainsInlineBody">
                <tr><td colspan="5" style="text-align:center;padding:22px;color:var(--txd)">جارٍ كشف الدومينات المسجلة...</td></tr>
              </tbody>
            </table>
          </div>
          <div class="pagination" id="admDomainsInlinePagination"></div>
        </div>
        <div class="grid-3">
          <div class="form-group">
            <label>🎯 الدومين القديم المستهدف *</label>
            <input type="text" id="bulkOldDomain" class="form-control" dir="ltr" style="text-align:left;font-family:monospace;font-weight:700" placeholder="http://old-domain.com:8080">
          </div>
          <div class="form-group">
            <label>🚀 الدومين الجديد المقترح *</label>
            <input type="text" id="bulkNewDomain" class="form-control" dir="ltr" style="text-align:left;font-family:monospace;font-weight:700" placeholder="http://new-domain.com:8080">
          </div>
          <div class="form-group" style="display:flex;align-items:flex-end;margin-bottom:16px">
            <button class="btn primary" id="btnReplaceDomain" style="width:100%;background:var(--inf);color:#fff" onclick="bulkReplaceDomainNow()">⚡ تغيير الدومين للجميع الآن</button>
          </div>
        </div>
        <div id="domainReplaceResult" style="display:none;padding:12px 16px;border-radius:10px;background:var(--ok-bg);border:1px solid var(--ok);color:var(--ok);font-weight:800;font-size:13.5px;margin-top:4px"></div>
      </div>
    </div>
        <div class="card" style="border-color:rgba(16,185,129,0.55);background:linear-gradient(135deg,rgba(16,185,129,0.08),rgba(17,24,39,0.96))">
      <div class="card-header">
        <h2 class="card-title">🔓 نزع تشفير رقم PIN للقوائم (عند نسيان المستخدم للرقم السري)</h2>
      </div>
      <div class="card-body">
        <p style="font-size:13px;color:var(--txd);margin-bottom:12px;line-height:1.7">
          إذا نسي مشترك رقم PIN الخاص بقوائمه وطلب منك فتح القفل، أدخل رمز جهازه هنا أو اضغط على زر <b>"🔓 نزع PIN"</b> بجانب اسمه في جدول المشتركين بالأسفل ليتم إلغاء قفل PIN فوراً.
        </p>
        <div class="grid-3">
          <div class="form-group" style="grid-column: span 2">
            <label>📺 رمز جهاز المشترك (Device ID)</label>
            <input type="text" id="pinUnlockDeviceInput" class="form-control" dir="ltr" style="text-align:left;font-family:monospace;font-size:15px" placeholder="DEV-XXXX-XXXX">
          </div>
          <div class="form-group" style="display:flex;align-items:flex-end;margin-bottom:16px">
            <button class="btn primary" style="width:100%;background:linear-gradient(135deg,#10b981,#059669);border:none;color:#fff;font-weight:800" onclick="unlockUserPinByInput()">🔓 نزع قفل PIN فوراً</button>
          </div>
        </div>
      </div>
    </div>
    <div class="card" style="border-color:#3b82f6;background:linear-gradient(135deg,rgba(59,130,246,0.08),rgba(15,23,42,0.92))">
      <div class="card-header">
        <h2 class="card-title">📡 التحكم في إظهار/تفعيل (القائمة الرئيسية) لمستخدم محدد حسب رمز الشاشة</h2>
        <span class="badge inf">مخفية افتراضياً لعدم التشويش على الزبائن</span>
      </div>
      <div class="card-body">
        <p style="font-size:13px;color:var(--txd);margin-bottom:12px;line-height:1.7">
          القائمة الرئيسية لا تفتح ولا تظهر تلقائياً لجميع المستخدمين لعدم التشويش عليهم، وإنما تتفعل فقط للمستخدم الذي يحدد الأدمن رمز شاشته (<code dir="ltr">DEV-XXXX-XXXX</code>) هنا أو من زر <b>(📡 الرئيسية)</b> في جدول المشتركين بالأسفل.
        </p>
        <div class="grid-3">
          <div class="form-group" style="grid-column:span 2">
            <label>📺 رمز شاشة المشترك المستهدف (Screen Code)</label>
            <input type="text" id="portalMainDeviceInput" class="form-control" dir="ltr" placeholder="DEV-XXXX-XXXX" style="text-transform:uppercase;font-weight:800;letter-spacing:1px">
          </div>
          <div class="form-group" style="display:flex;gap:8px;align-items:flex-end;margin-bottom:16px">
            <button class="btn primary" style="flex:1;background:linear-gradient(135deg,#10b981,#059669);border:none;color:#fff;font-weight:800" onclick="setDevicePortalMainByInput(1)">📡 تفعيل الرئيسية له</button>
            <button class="btn danger" style="flex:1;font-weight:800" onclick="setDevicePortalMainByInput(0)">🚫 إيقافها عنه</button>
          </div>
        </div>
      </div>
    </div>
    <div class="card" style="border-color:var(--gold)">
      <div class="card-header"><h2 class="card-title">🔄 التجديد الجماعي</h2></div>
      <div class="card-body">
        <div class="grid-3">
          <div class="form-group">
            <label>عدد الأيام</label>
            <input type="number" id="bulkDays" class="form-control" value="30" min="1">
          </div>
          <div class="form-group">
            <label>الفئة المستهدفة</label>
            <select id="bulkFilter" class="form-control">
              <option value="active">النشطون فقط</option>
              <option value="expired">المنتهية اشتراكاتهم</option>
              <option value="expiring7">ينتهي خلال 7 أيام</option>
              <option value="expiring30">ينتهي خلال 30 يوم</option>
              <option value="all">الكل بدون استثناء</option>
            </select>
          </div>
          <div class="form-group" style="display:flex;align-items:flex-end;margin-bottom:16px">
            <button class="btn primary" style="width:100%" onclick="bulkRenew()">تطبيق التجديد</button>
          </div>
        </div>
      </div>
    </div>
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">👥 قائمة المشتركين</h2>
        <button class="btn primary" onclick="openModal('addUserModal')">➕ إضافة مشترك</button>
      </div>
      <div class="card-body">
        <div class="toolbar">
          <div class="search-box">
            <span class="search-icon">🔍</span>
            <input type="text" id="uSearch" placeholder="بحث برمز الجهاز أو الاسم..." oninput="onSearchUsers()">
            <button class="clear-btn" id="uClearBtn" onclick="document.getElementById('uSearch').value=''; onSearchUsers();">×</button>
          </div>
          <select id="uFilterStatus" class="filter-select" onchange="onSearchUsers()">
            <option value="">كل الحالات</option>
            <option value="active">نشط</option>
            <option value="expired">منتهي</option>
            <option value="disabled">معطّل</option>
          </select>
        </div>
        <div class="table-responsive">
          <table>
            <thead>
              <tr><th>رمز الجهاز</th><th>الاسم</th><th>الانتهاء</th><th>المتبقي</th><th>الحالة</th><th>إجراءات</th></tr>
            </thead>
            <tbody id="usersBody"><tr><td colspan="6" style="text-align:center;padding:30px">جارٍ التحميل...</td></tr></tbody>
          </table>
        </div>
        <div class="pagination" id="uPagination"></div>
      </div>
    </div>
  </div>

  <!-- CARDS -->
  <div id="tab-cards" class="tab-pane">
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">🎫 إدارة البطاقات والقسائم</h2>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn outline" onclick="openBatchesModal()">📦 الدفعات</button>
          <button class="btn primary" onclick="openModal('genCardsModal')">➕ توليد بطاقات</button>
        </div>
      </div>
      <div class="card-body">
        <div class="table-responsive">
          <table>
            <thead><tr><th>الرمز (LTR)</th><th>النوع</th><th>الأيام</th><th>المصدر / الموزع</th><th class="hide-mobile">الدفعة</th><th>الحالة</th><th>إجراءات</th></tr></thead>
            <tbody id="cardsBody"><tr><td colspan="7" style="text-align:center;padding:30px">جارٍ التحميل...</td></tr></tbody>
          </table>
        </div>
      </div>
    </div>
  </div>

  <!-- RESELLERS (فضاء إدارة الموزعين والنقاط) -->
  <div id="tab-resellers" class="tab-pane">
    <div class="card" style="border-color:var(--gold)">
      <div class="card-header">
        <h2 class="card-title">💎 نظام نقاط الموزعين والباقات المعتمدة</h2>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <a href="reseller_portal.php" target="_blank" class="btn success" style="text-decoration:none">🏪 فتح لوحة فضاء الموزعين ↗</a>
          <button class="btn primary" onclick="openAddResellerModal()">➕ فتح حساب موزع جديد</button>
        </div>
      </div>
      <div class="card-body">
        <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px">
          <div style="background:var(--bg);border:1px solid var(--bd);border-radius:12px;padding:14px;display:flex;align-items:center;justify-content:space-between">
            <div><div style="font-weight:900;color:var(--tx)">📅 اشتراك شهر واحد</div><div style="font-size:12px;color:var(--txd)">صلاحية 30 يوم كاملة</div></div>
            <span class="pts-badge">1 نقطة</span>
          </div>
          <div style="background:var(--bg);border:1px solid var(--bd);border-radius:12px;padding:14px;display:flex;align-items:center;justify-content:space-between">
            <div><div style="font-weight:900;color:var(--tx)">📅 اشتراك 3 أشهر</div><div style="font-size:12px;color:var(--txd)">صلاحية 90 يوم كاملة</div></div>
            <span class="pts-badge">3 نقاط</span>
          </div>
          <div style="background:var(--bg);border:1px solid var(--bd);border-radius:12px;padding:14px;display:flex;align-items:center;justify-content:space-between">
            <div><div style="font-weight:900;color:var(--tx)">📅 اشتراك 6 أشهر</div><div style="font-size:12px;color:var(--txd)">صلاحية 180 يوم كاملة</div></div>
            <span class="pts-badge">6 نقاط</span>
          </div>
          <div style="background:var(--bg);border:1px solid var(--gold);border-radius:12px;padding:14px;display:flex;align-items:center;justify-content:space-between">
            <div><div style="font-weight:900;color:var(--gold)">👑 اشتراك سنة كاملة</div><div style="font-size:12px;color:var(--txd)">صلاحية 365 يوم كاملة</div></div>
            <span class="pts-badge">12 نقطة</span>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <h2 class="card-title">💼 قائمة حسابات الموزعين</h2>
        <button class="btn primary" onclick="openAddResellerModal()">➕ فتح حساب موزع</button>
      </div>
      <div class="card-body">
        <div class="toolbar">
          <div class="search-box">
            <span class="search-icon">🔍</span>
            <input type="text" id="resSearch" placeholder="بحث باسم الموزع، اسم المستخدم، أو الهاتف..." oninput="renderResellersTable()">
          </div>
          <select id="resFilterStatus" class="filter-select" onchange="renderResellersTable()">
            <option value="">كل الموزعين</option>
            <option value="active">نشط فقط</option>
            <option value="disabled">موقوف فقط</option>
          </select>
        </div>
        <div class="table-responsive">
          <table>
            <thead>
              <tr>
                <th>الموزع / المتجر</th>
                <th>اسم المستخدم</th>
                <th>كلمة المرور</th>
                <th class="hide-mobile">الهاتف</th>
                <th>رصيد النقاط</th>
                <th class="hide-mobile">المشحون / المستهلك</th>
                <th>القسائم المستخرجة</th>
                <th>الحالة</th>
                <th>إجراءات التحكم</th>
              </tr>
            </thead>
            <tbody id="resellersBody"><tr><td colspan="9" style="text-align:center;padding:30px">جارٍ التحميل...</td></tr></tbody>
          </table>
        </div>
      </div>
    </div>
  </div>

  <!-- DATA.TXT SERVERS MANAGEMENT -->
  <div id="tab-datatxt" class="tab-pane">
    <div class="card" style="border-color:rgba(14,165,233,0.55);background:linear-gradient(135deg,rgba(14,165,233,0.08),rgba(17,24,39,0.96))">
      <div class="card-header">
        <h2 class="card-title">➕ إضافة سيرفر جديد إلى ملف data.txt</h2>
        <button class="btn outline" onclick="loadDataTxtServers(true)">🔄 تحديث القائمة</button>
      </div>
      <div class="card-body">
        <p style="font-size:13px;color:var(--txd);margin-bottom:14px;line-height:1.7">
          أضف أي سيرفر جديد مع اسمه ورابط الدومين الخاص به إلى ملف <b>data.txt</b> ليتم التعرّف عليه تلقائياً وإخفاء رابطه عند الزبائن، مع تعميم أي تحديث للدومين على جميع الزبائن فور إعادة إقلاع التطبيق.
        </p>
        <div class="grid-3">
          <div class="form-group">
            <label>🏷️ اسم السيرفر (Server Name)</label>
            <input type="text" id="dtAddName" class="form-control" placeholder="مثال: IRON PRO MAX">
          </div>
          <div class="form-group">
            <label>🌐 رابط الدومين / الهوست (Server URL)</label>
            <input type="text" id="dtAddUrl" class="form-control" dir="ltr" style="text-align:left;font-family:monospace;font-size:14px" placeholder="http://host-domain.com:8080">
          </div>
          <div class="form-group" style="display:flex;align-items:flex-end;margin-bottom:16px">
            <button class="btn primary" style="width:100%" onclick="addDataTxtServerNow()">➕ إضافة السيرفر إلى data.txt</button>
          </div>
        </div>
      </div>
    </div>

    <div class="card" style="border-color:var(--gold)">
      <div class="card-header">
        <h2 class="card-title">🖥️ التحكم في أسماء السيرفرات وتعديل الدومينات في data.txt (<span id="dtCountBadge">0</span> سيرفر)</h2>
      </div>
      <div class="card-body">
        <div class="toolbar">
          <div class="search-box" style="flex:1">
            <span class="search-icon">🔍</span>
            <input type="text" id="dtSearch" placeholder="بحث ذكي باسم السيرفر أو الدومين أو البورت في data.txt..." oninput="onSearchDataTxtTable()">
          </div>
          <select id="dtSortSelect" class="filter-select" onchange="onSearchDataTxtTable()">
            <option value="default">🔢 الترتيب الافتراضي</option>
            <option value="playlists">📊 الأكثر قوائم مشتركين</option>
            <option value="name_az">🏷️ اسم السيرفر (A-Z)</option>
            <option value="domain_az">🌐 الدومين (A-Z)</option>
          </select>
        </div>
        <div class="table-responsive">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>اسم السيرفر (Server Name)</th>
                <th>رابط الدومين / الهوست (Server URL)</th>
                <th>الدومين الصافي</th>
                <th>قوائم المشتركين المرتبطة</th>
                <th>إجراءات التحكم</th>
              </tr>
            </thead>
            <tbody id="dataTxtBody">
              <tr><td colspan="6" style="text-align:center;padding:24px;color:var(--txd)">جارٍ تحميل بيانات data.txt...</td></tr>
            </tbody>
          </table>
        </div>
        <div class="pagination" id="dtPagination"></div>
      </div>
    </div>

    <div class="card" style="border-color:rgba(16,185,129,0.5)">
      <div class="card-header">
        <h2 class="card-title">📝 المحرر اليدوي المباشر لملف data.txt (تعديل الأسماء والدومينات يدوياً)</h2>
        <button class="btn primary" onclick="saveRawDataTxtNow()">💾 حفظ التعديلات اليدوية في data.txt وتحديث قاعدة البيانات</button>
      </div>
      <div class="card-body">
        <p style="font-size:13px;color:var(--txd);margin-bottom:10px;line-height:1.7">
          يمكنك تعديل أسماء السيرفرات أو روابط الدومينات يدوياً مباشرة في النص بالأسفل (الصيغة: <code dir="ltr">ServerName|http://domain:port</code> في كل سطر). عند الضغط على <b>"حفظ التعديلات اليدوية"</b> يتم تحديث ملف <b>data.txt</b> وتحديث جميع قوائم الزبائن في قاعدة البيانات تلقائياً بالدومينات الجديدة!
        </p>
        <textarea id="dtRawEditor" class="form-control" rows="16" dir="ltr" style="text-align:left;font-family:monospace;font-size:13.5px;line-height:1.65;background:#060911;color:#a7f3d0;border:1px solid rgba(16,185,129,0.4)"></textarea>
        <div style="display:flex;justify-content:flex-end;margin-top:12px">
          <button class="btn primary" style="padding:12px 24px;font-size:14.5px" onclick="saveRawDataTxtNow()">💾 حفظ التعديلات اليدوية في data.txt وتعميم الدومينات الجديدة على الزبائن</button>
        </div>
      </div>
    </div>
  </div>

  <!-- APP OTA DIRECT UPDATE MANAGEMENT -->
  <div id="tab-appupdate" class="tab-pane">
    <div class="card" style="border-color:rgba(16,185,129,0.55);background:linear-gradient(135deg,rgba(16,185,129,0.08),rgba(17,24,39,0.96))">
      <div class="card-header">
        <h2 class="card-title">📲 إشعار وترقية نسخة التطبيق للزبائن برابط مباشر (OTA Direct APK Update)</h2>
        <span class="badge" id="appUpdateStatusBadge">جارٍ التحميل...</span>
      </div>
      <div class="card-body">
        <p style="font-size:13px;color:var(--txd);margin-bottom:16px;line-height:1.75">
          عند تفعيل هذا الإشعار ووضع <b>الرابط المباشر لملف الـ APK</b>، ستنبثق صفحة التحديث تلقائياً عند دخول الزبون للتطبيق، فيقوم التطبيق بتنزيل النسخة الجديدة مباشرة مع شريط تقدّم حي ثم يطلب من المستخدم تثبيت الترقية فوراً فوق النسخة الحالية بسلاسة (بفضل التوقيع الموحّد الثابت دون حذف النسخة السابقة).
        </p>

        <div class="grid-3">
          <div class="form-group">
            <label>⚡ حالة إشعار التحديث عند إقلاع التطبيق</label>
            <select id="updEnabled" class="form-control">
              <option value="1">🟢 مفعّل — يظهر للزبائن عند الدخول للتطبيق</option>
              <option value="0">🔴 متوقف — لا يظهر إشعار تحديث</option>
            </select>
          </div>
          <div class="form-group">
            <label>🔒 نوع الترقية</label>
            <select id="updMandatory" class="form-control">
              <option value="1">🚨 ترقية إجبارية (يبدأ التنزيل والتثبيت مباشرة عند الدخول)</option>
              <option value="0">🔔 ترقية اختيارية (يمكن للزبون تأجيلها)</option>
            </select>
          </div>
          <div class="form-group">
            <label>🔢 رقم الإصدار البرمجي (Version Code — أكبر من 26)</label>
            <input type="number" id="updVersionCode" class="form-control" dir="ltr" min="1" value="27" style="font-weight:900;text-align:center">
          </div>
        </div>

        <div class="grid-3">
          <div class="form-group">
            <label>🏷️ اسم الإصدار الظاهر للزبون (Version Name)</label>
            <input type="text" id="updVersionName" class="form-control" dir="ltr" value="27.0" placeholder="27.0">
          </div>
          <div class="form-group" style="grid-column: span 2">
            <label>🔗 الرابط المباشر لملف التطبيق (Direct APK Download URL)</label>
            <input type="url" id="updApkUrl" class="form-control" dir="ltr" style="text-align:left;font-family:monospace;font-size:14px" placeholder="https://your-domain.com/LIVE-PRO-latest.apk">
          </div>
        </div>

        <div class="form-group">
          <label>📢 عنوان نافذة التحديث</label>
          <input type="text" id="updTitle" class="form-control" value="📲 تحديث جديد متوفر لتطبيق LIVE PRO" placeholder="عنوان نافذة التحديث">
        </div>

        <div class="form-group">
          <label>📝 رسالة ومميزات التحديث للزبون</label>
          <textarea id="updNotes" class="form-control" rows="3" placeholder="أدخل ملاحظات التحديث التي ستظهر للزبون...">يرجى ترقية نسخة التطبيق الآن للحصول على أحدث المزايا وأفضل ثبات وسرعة في التشغيل.</textarea>
        </div>

        <div style="display:flex;justify-content:flex-end;gap:10px;margin-top:14px">
          <button class="btn primary" style="padding:12px 26px;font-size:15px;font-weight:800" onclick="saveAppUpdateConfigNow()">
            🚀 حفظ وإرسال إشعار التحديث للزبائن
          </button>
        </div>
      </div>
    </div>
  </div>

</main>

<!-- Bottom Nav -->
<nav class="bottom-nav">
  <button class="bottom-nav-item active" id="bnav-channels" onclick="switchTab('channels')"><span class="icon">📡</span><span>القنوات</span></button>
  <button class="bottom-nav-item" id="bnav-users" onclick="switchTab('users')"><span class="icon">👥</span><span>المشتركين</span></button>
  <button class="bottom-nav-item" id="bnav-cards" onclick="switchTab('cards')"><span class="icon">🎫</span><span>البطاقات</span></button>
  <button class="bottom-nav-item" id="bnav-resellers" onclick="switchTab('resellers')"><span class="icon">💼</span><span>الموزعين</span></button>
  <button class="bottom-nav-item" id="bnav-datatxt" onclick="switchTab('datatxt')"><span class="icon">🖥️</span><span>data.txt</span></button>
  <button class="bottom-nav-item" id="bnav-appupdate" onclick="switchTab('appupdate')"><span class="icon">📲</span><span>تحديث التطبيق</span></button>
  <a href="reseller_portal.php" target="_blank" class="bottom-nav-item" style="text-decoration:none"><span class="icon">🏪</span><span>بوابة الموزع</span></a>
</nav>

<!-- ============ Reorder Categories Modal ============ -->
<div class="modal-overlay" id="reorderCatsModal">
  <div class="modal-content">
    <div class="modal-header">
      <h3>🔀 ترتيب الباقات</h3>
      <button class="modal-close" onclick="closeModal('reorderCatsModal')">×</button>
    </div>
    <div class="modal-body">
      <p style="font-size:13px;color:var(--txd);margin-bottom:12px;line-height:1.7">
        اسحب الباقة لإعادة ترتيبها، أو استخدم أزرار ↑↓.<br>
        يُحفظ الترتيب كما هو في ملف M3U عند الاستيراد، ويمكنك تعديله هنا.
      </p>
      <div class="reorder-list" id="reorderCatsList"></div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('reorderCatsModal')">إلغاء</button>
      <button class="btn primary" onclick="saveCategoryOrder()">💾 حفظ الترتيب</button>
    </div>
  </div>
</div>

<!-- Modals -->
<div class="modal-overlay" id="m3uModal">
  <div class="modal-content">
    <div class="modal-header"><h3>📥 استيراد قنوات من M3U</h3><button class="modal-close" onclick="closeModal('m3uModal')">×</button></div>
    <div class="modal-body">
      <div class="form-group">
        <label>رابط ملف M3U</label>
        <input type="url" id="m3uUrl" class="form-control" placeholder="https://..." dir="ltr">
      </div>
      <div id="m3uPreview" style="display:none;margin-top:20px">
        <p style="margin-bottom:10px;font-weight:700;color:var(--gold)" id="m3uSummary"></p>
        <div id="m3uGroups" style="max-height:200px;overflow-y:auto;background:var(--bg);padding:10px;border-radius:8px;border:1px solid var(--bd)"></div>
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('m3uModal')">إلغاء</button>
      <button class="btn primary" id="btnPrevM3U" onclick="previewM3U()">🔍 معاينة</button>
      <button class="btn primary" id="btnImportM3U" onclick="importM3U()" style="display:none">📥 استيراد</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="addChannelModal">
  <div class="modal-content">
    <div class="modal-header"><h3>➕ إضافة قناة</h3><button class="modal-close" onclick="closeModal('addChannelModal')">×</button></div>
    <div class="modal-body">
      <div class="form-group"><label>اسم القناة *</label><input type="text" id="addChName" class="form-control"></div>
      <div class="grid-2">
        <div class="form-group"><label>الرقم (اختياري)</label><input type="number" id="addChNumber" class="form-control"></div>
        <div class="form-group"><label>الباقة (التصنيف)</label><input type="text" id="addChCat" class="form-control" value="عام"></div>
      </div>
      <div class="form-group"><label>رابط البث *</label><input type="url" id="addChUrl" class="form-control" dir="ltr"></div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('addChannelModal')">إلغاء</button>
      <button class="btn primary" onclick="addChannel()">💾 حفظ</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="editChannelModal">
  <div class="modal-content">
    <div class="modal-header"><h3>✏️ تعديل القناة</h3><button class="modal-close" onclick="closeModal('editChannelModal')">×</button></div>
    <div class="modal-body">
      <input type="hidden" id="editChId">
      <div class="form-group"><label>اسم القناة *</label><input type="text" id="editChName" class="form-control"></div>
      <div class="grid-2">
        <div class="form-group"><label>الرقم</label><input type="number" id="editChNumber" class="form-control"></div>
        <div class="form-group"><label>الباقة</label><input type="text" id="editChCat" class="form-control"></div>
      </div>
      <div class="form-group"><label>الرابط الأصلي (اتركه فارغاً للإبقاء على الحالي)</label><input type="url" id="editChUrl" class="form-control" dir="ltr"></div>
      <div class="form-group"><label>الحالة</label><select id="editChActive" class="form-control"><option value="1">✅ نشط</option><option value="0">⛔ معطّل</option></select></div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('editChannelModal')">إلغاء</button>
      <button class="btn primary" onclick="saveChannelEdit()">💾 حفظ</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="bulkMoveModal">
  <div class="modal-content" style="max-width:450px">
    <div class="modal-header"><h3>📦 نقل القنوات إلى باقة</h3><button class="modal-close" onclick="closeModal('bulkMoveModal')">×</button></div>
    <div class="modal-body">
      <div class="form-group">
        <label>الباقة الهدف</label>
        <input type="text" id="bulkMoveTarget" class="form-control" list="existingCats" placeholder="اكتب اسم الباقة...">
        <datalist id="existingCats"></datalist>
      </div>
      <p style="font-size:13px;color:var(--txd)">سيتم نقل <b id="bulkMoveCount" style="color:var(--gold)">0</b> قناة.</p>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('bulkMoveModal')">إلغاء</button>
      <button class="btn primary" onclick="bulkMove()">📦 نقل</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="addUserModal">
  <div class="modal-content">
    <div class="modal-header"><h3>➕ إضافة مشترك</h3><button class="modal-close" onclick="closeModal('addUserModal')">×</button></div>
    <div class="modal-body">
      <div class="form-group"><label>رمز الجهاز *</label><input type="text" id="addUserId" class="form-control" dir="ltr" placeholder="DEV-"></div>
      <div class="form-group"><label>اسم المشترك</label><input type="text" id="addUserName" class="form-control"></div>
      <div class="form-group"><label>تاريخ الانتهاء *</label><input type="date" id="addUserExpire" class="form-control"></div>
      <div class="form-group"><label>ملاحظات</label><input type="text" id="addUserNotes" class="form-control"></div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('addUserModal')">إلغاء</button>
      <button class="btn primary" onclick="addUser()">💾 حفظ</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="editUserModal">
  <div class="modal-content">
    <div class="modal-header"><h3>✏️ تعديل المشترك</h3><button class="modal-close" onclick="closeModal('editUserModal')">×</button></div>
    <div class="modal-body">
      <input type="hidden" id="editUserId">
      <div class="form-group"><label>رمز الجهاز</label><input type="text" id="editUserDevice" class="form-control" disabled style="opacity:0.6"></div>
      <div class="form-group"><label>تاريخ الانتهاء</label><input type="date" id="editUserExpire" class="form-control"></div>
      <div class="form-group"><label>الحالة</label><select id="editUserActive" class="form-control"><option value="1">✅ نشط</option><option value="0">⛔ معطّل</option></select></div>
      <div class="form-group"><label>ملاحظات</label><input type="text" id="editUserNotes" class="form-control"></div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('editUserModal')">إلغاء</button>
      <button class="btn primary" onclick="saveUserEdit()">💾 حفظ</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="genCardsModal">
  <div class="modal-content">
    <div class="modal-header"><h3>🎫 توليد بطاقات</h3><button class="modal-close" onclick="closeModal('genCardsModal')">×</button></div>
    <div class="modal-body">
      <div class="grid-2">
        <div class="form-group">
          <label>نوع البطاقة</label>
          <select id="genCardType" class="form-control" onchange="document.getElementById('customDaysField').style.display = this.value === 'custom' ? 'block' : 'none'">
            <option value="trial">🎁 تجربة</option>
            <option value="1month" selected>📅 شهر</option>
            <option value="3months">📅 3 أشهر</option>
            <option value="6months">📅 6 أشهر</option>
            <option value="1year">📅 سنة</option>
            <option value="custom">⚙️ مخصص</option>
          </select>
        </div>
        <div class="form-group"><label>الكمية</label><input type="number" id="genCardCount" class="form-control" value="10" min="1" max="1000"></div>
      </div>
      <div class="form-group" id="customDaysField" style="display:none"><label>عدد الأيام المخصص</label><input type="number" id="genCustomDays" class="form-control" value="30"></div>
      <div class="grid-2">
        <div class="form-group"><label>اسم الدفعة</label><input type="text" id="genBatchName" class="form-control"></div>
        <div class="form-group"><label>صلاحية البطاقة</label><input type="date" id="genCardExpires" class="form-control"></div>
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('genCardsModal')">إلغاء</button>
      <button class="btn primary" onclick="generateCards()">🎫 توليد</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="batchesModal">
  <div class="modal-content">
    <div class="modal-header"><h3>📦 الدفعات</h3><button class="modal-close" onclick="closeModal('batchesModal')">×</button></div>
    <div class="modal-body">
      <div class="table-responsive">
        <table>
          <thead><tr><th>الدفعة</th><th>النوع</th><th>الإجمالي</th><th>متاح</th><th>مستخدم</th><th>حذف</th></tr></thead>
          <tbody id="batchesBody"></tbody>
        </table>
      </div>
    </div>
    <div class="modal-footer"><button class="btn outline" onclick="closeModal('batchesModal')">إغلاق</button></div>
  </div>
</div>

<div class="modal-overlay" id="passModal">
  <div class="modal-content">
    <div class="modal-header"><h3>🔑 تغيير كلمة المرور</h3><button class="modal-close" onclick="closeModal('passModal')">×</button></div>
    <div class="modal-body">
      <div class="form-group"><label>كلمة المرور الحالية</label><input type="password" id="oldPass" class="form-control"></div>
      <div class="form-group"><label>كلمة المرور الجديدة</label><input type="password" id="newPass" class="form-control"></div>
      <div class="form-group"><label>تأكيد كلمة المرور</label><input type="password" id="newPass2" class="form-control"></div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('passModal')">إلغاء</button>
      <button class="btn primary" onclick="changePassword()">💾 حفظ</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="resultModal">
  <div class="modal-content">
    <div class="modal-header"><h3>🎉 تم التوليد</h3><button class="modal-close" onclick="closeModal('resultModal')">×</button></div>
    <div class="modal-body">
      <p id="resultInfo" style="margin-bottom:10px;font-weight:700;color:var(--gold)"></p>
      <textarea id="resultCodes" class="form-control" rows="10" readonly style="font-family:monospace;direction:ltr;text-align:left;font-size:14px;resize:none"></textarea>
    </div>
    <div class="modal-footer">
      <button class="btn primary" onclick="copyCodes()">📋 نسخ</button>
      <button class="btn outline" onclick="closeModal('resultModal')">إغلاق</button>
    </div>
  </div>
</div>

<!-- ============ نوافذ إدارة الموزعين والنقاط ============ -->
<div class="modal-overlay" id="addResellerModal">
  <div class="modal-content">
    <div class="modal-header">
      <h3>➕ فتح حساب موزع جديد</h3>
      <button class="modal-close" onclick="closeModal('addResellerModal')">×</button>
    </div>
    <div class="modal-body">
      <div class="grid-2">
        <div class="form-group">
          <label>اسم الموزع / اسم المتجر *</label>
          <input type="text" id="addResName" class="form-control" placeholder="مثال: موزع الأمل سات">
        </div>
        <div class="form-group">
          <label>رقم الهاتف / واتساب</label>
          <input type="text" id="addResPhone" class="form-control" dir="ltr" placeholder="00213...">
        </div>
      </div>
      <div class="grid-2">
        <div class="form-group">
          <label>اسم المستخدم للدخول (إنجليزي بدون مسافات) *</label>
          <input type="text" id="addResUser" class="form-control" dir="ltr" placeholder="example_reseller">
        </div>
        <div class="form-group">
          <label>كلمة المرور *</label>
          <div style="display:flex;gap:6px">
            <input type="text" id="addResPass" class="form-control" dir="ltr" placeholder="كلمة المرور">
            <button type="button" class="btn outline" onclick="genRandomResPass('addResPass')" title="توليد كلمة مرور">🎲</button>
          </div>
        </div>
      </div>
      <div class="form-group">
        <label>💎 رصيد النقاط الابتدائي (1 نقطة = 1 شهر اشتراك)</label>
        <input type="number" id="addResPoints" class="form-control" value="10" min="0">
        <div class="preset-chips">
          <button type="button" class="preset-chip" onclick="$('addResPoints').value=0">0 نقطة</button>
          <button type="button" class="preset-chip" onclick="$('addResPoints').value=10">10 نقاط</button>
          <button type="button" class="preset-chip" onclick="$('addResPoints').value=25">25 نقطة</button>
          <button type="button" class="preset-chip" onclick="$('addResPoints').value=50">50 نقطة</button>
          <button type="button" class="preset-chip" onclick="$('addResPoints').value=100">100 نقطة</button>
        </div>
      </div>
      <div class="form-group">
        <label>ملاحظات إدارية</label>
        <input type="text" id="addResNotes" class="form-control" placeholder="الولاية / طريقة الدفع / ملاحظة...">
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('addResellerModal')">إلغاء</button>
      <button class="btn primary" onclick="createReseller()">💾 فتح الحساب وشحن النقاط</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="editResellerModal">
  <div class="modal-content">
    <div class="modal-header">
      <h3>✏️ تعديل حساب الموزع</h3>
      <button class="modal-close" onclick="closeModal('editResellerModal')">×</button>
    </div>
    <div class="modal-body">
      <input type="hidden" id="editResId">
      <div class="grid-2">
        <div class="form-group">
          <label>اسم الموزع / المتجر *</label>
          <input type="text" id="editResName" class="form-control">
        </div>
        <div class="form-group">
          <label>رقم الهاتف</label>
          <input type="text" id="editResPhone" class="form-control" dir="ltr">
        </div>
      </div>
      <div class="grid-2">
        <div class="form-group">
          <label>اسم المستخدم للدخول *</label>
          <input type="text" id="editResUser" class="form-control" dir="ltr">
        </div>
        <div class="form-group">
          <label>كلمة مرور جديدة (اتركها فارغة للإبقاء على الحالية)</label>
          <div style="display:flex;gap:6px">
            <input type="text" id="editResPass" class="form-control" dir="ltr" placeholder="بدون تغيير">
            <button type="button" class="btn outline" onclick="genRandomResPass('editResPass')">🎲</button>
          </div>
        </div>
      </div>
      <div class="grid-2">
        <div class="form-group">
          <label>حالة الحساب</label>
          <select id="editResActive" class="form-control">
            <option value="1">✅ نشط (يمكنه الدخول واستخراج القسائم)</option>
            <option value="0">⛔ موقوف مؤقتاً</option>
          </select>
        </div>
        <div class="form-group">
          <label>ملاحظات</label>
          <input type="text" id="editResNotes" class="form-control">
        </div>
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('editResellerModal')">إلغاء</button>
      <button class="btn primary" onclick="saveResellerEdit()">💾 حفظ التعديلات</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="resellerPointsModal">
  <div class="modal-content" style="max-width:520px">
    <div class="modal-header">
      <h3>💎 إرسال / إدارة نقاط الموزع</h3>
      <button class="modal-close" onclick="closeModal('resellerPointsModal')">×</button>
    </div>
    <div class="modal-body">
      <input type="hidden" id="ptsResId">
      <div style="background:var(--bg);border:1px solid var(--gold);border-radius:12px;padding:14px;margin-bottom:16px;display:flex;justify-content:space-between;align-items:center">
        <div>
          <div style="font-size:12px;color:var(--txd)">الموزع المحدد:</div>
          <div id="ptsResName" style="font-size:16px;font-weight:900;color:var(--tx)">—</div>
        </div>
        <div style="text-align:left">
          <div style="font-size:12px;color:var(--txd)">الرصيد الحالي:</div>
          <div class="pts-badge"><span id="ptsResCurrent">0</span> نقطة</div>
        </div>
      </div>
      <div class="grid-2">
        <div class="form-group">
          <label>نوع العملية</label>
          <select id="ptsOperation" class="form-control">
            <option value="add">➕ إرسال / شحن نقاط للموزع</option>
            <option value="deduct">➖ خصم / سحب نقاط من الموزع</option>
          </select>
        </div>
        <div class="form-group">
          <label>عدد النقاط *</label>
          <input type="number" id="ptsAmount" class="form-control" value="10" min="1">
        </div>
      </div>
      <div class="form-group">
        <label>اختيار سريع لعدد النقاط:</label>
        <div class="preset-chips">
          <button type="button" class="preset-chip" onclick="$('ptsAmount').value=1">1 نقطة (شهر)</button>
          <button type="button" class="preset-chip" onclick="$('ptsAmount').value=3">3 نقاط (3 أشهر)</button>
          <button type="button" class="preset-chip" onclick="$('ptsAmount').value=6">6 نقاط (6 أشهر)</button>
          <button type="button" class="preset-chip" onclick="$('ptsAmount').value=12">12 نقطة (سنة)</button>
          <button type="button" class="preset-chip" onclick="$('ptsAmount').value=25">25 نقطة</button>
          <button type="button" class="preset-chip" onclick="$('ptsAmount').value=50">50 نقطة</button>
          <button type="button" class="preset-chip" onclick="$('ptsAmount').value=100">100 نقطة</button>
        </div>
      </div>
      <div class="form-group">
        <label>ملاحظة العملية (تظهر في كشف حساب الموزع)</label>
        <input type="text" id="ptsNotes" class="form-control" placeholder="مثال: شحن رصيد دفعة جديدة...">
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('resellerPointsModal')">إلغاء</button>
      <button class="btn primary" onclick="submitResellerPoints()">💎 تنفيذ العملية فوراً</button>
    </div>
  </div>
</div>

<div class="modal-overlay" id="resellerDetailsModal">
  <div class="modal-content" style="max-width:860px">
    <div class="modal-header">
      <h3 id="resDetailsTitle">📊 سجل الموزع والقسائم المستخرجة</h3>
      <button class="modal-close" onclick="closeModal('resellerDetailsModal')">×</button>
    </div>
    <div class="modal-body">
      <div id="resDetailsSummary" style="display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:18px"></div>
      <h4 style="color:var(--gold);margin-bottom:8px;font-size:15px">🎫 أحدث القسائم المستخرجة بواسطة الموزع</h4>
      <div class="table-responsive" style="max-height:240px;margin-bottom:18px">
        <table>
          <thead><tr><th>رمز القسيمة (LTR)</th><th>الباقة</th><th>النقاط</th><th>الحالة</th><th>الشاشة المفعلة</th><th>التاريخ</th><th>نسخ</th></tr></thead>
          <tbody id="resDetailsCardsBody"></tbody>
        </table>
      </div>
      <h4 style="color:var(--gold);margin-bottom:8px;font-size:15px">💳 كشف حساب النقاط والعمليات</h4>
      <div class="table-responsive" style="max-height:240px">
        <table>
          <thead><tr><th>العملية</th><th>التغير</th><th>الرصيد بعد العملية</th><th>التفاصيل / الملاحظات</th><th>التاريخ</th></tr></thead>
          <tbody id="resDetailsTxBody"></tbody>
        </table>
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn outline" onclick="closeModal('resellerDetailsModal')">إغلاق</button>
    </div>
  </div>
</div>

<div class="bulk-bar" id="bulkBar">
  <span class="bulk-count"><span id="bulkCount">0</span> محدد</span>
  <button class="btn success" onclick="bulkAction('activate')">✅ تفعيل</button>
  <button class="btn outline" onclick="bulkAction('deactivate')">⏸ تعطيل</button>
  <button class="btn outline" onclick="openBulkMoveModal()">📦 نقل</button>
  <button class="btn danger" onclick="bulkAction('delete')">🗑 حذف</button>
  <button class="btn outline" onclick="clearSelection()">✕ إلغاء</button>
</div>

<div class="toast" id="toast"></div>

<script>
const API = 'api.php';
let TOKEN = localStorage.getItem('admin_token') || sessionStorage.getItem('admin_token') || '';
if (!TOKEN) { window.location.href = 'login.php'; }

const STATE = { channels: [], users: [], cards: [], categories: [], resellers: [] };
let chPage = 1, chPer = 20, filteredChannels = [];
let uPage = 1, uPer = 20, filteredUsers = [];
let selectedChannels = new Set();
let chipCategories = [];

const $ = id => document.getElementById(id);
const esc = s => String(s||'').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function toast(msg, type = '') {
  const el = $('toast');
  el.textContent = msg;
  el.className = 'toast show' + (type ? ' ' + type : '');
  clearTimeout(el.timer);
  el.timer = setTimeout(() => el.className = 'toast', 3500);
}

function openModal(id) { $(id).classList.add('active'); }
function closeModal(id) { $(id).classList.remove('active'); }

// 🛡️ دالة الاتصال الآمنة (تمنع خطأ Unexpected Token)
async function api(action, data = {}) {
  const url = new URL(API, location.href);
  url.searchParams.set('action', action);
  if (TOKEN) url.searchParams.set('token', TOKEN);
  const fd = new FormData();
  for (const k in data) {
    if (Array.isArray(data[k])) data[k].forEach(v => fd.append(k+'[]', v));
    else fd.append(k, data[k] == null ? '' : data[k]);
  }
  
  try {
    const res = await fetch(url.toString(), { method: 'POST', body: fd });
    const text = await res.text();
    let json;
    
    try {
      json = JSON.parse(text);
    } catch (err) {
      console.error("Server Response Error:", text);
      // استخراج الخطأ إذا كان HTML
      const tempDiv = document.createElement('div');
      tempDiv.innerHTML = text;
      const errorText = tempDiv.textContent || tempDiv.innerText || text;
      throw new Error('خطأ من السيرفر: ' + errorText.substring(0, 150));
    }

    if (!json.success) {
      if (res.status === 401) {
        localStorage.removeItem('admin_token'); sessionStorage.removeItem('admin_token');
        window.location.href = 'login.php'; throw new Error('انتهت الجلسة');
      }
      throw new Error(json.error || 'خطأ غير معروف');
    }
    return json.data;
  } catch (e) {
    throw e;
  }
}

function daysLeft(date) {
  try { return Math.floor((new Date(date+'T23:59:59') - new Date()) / 86400000); } catch(e) { return 0; }
}

async function bulkOperation(type, ids, extra = {}) {
  try {
    return await api('admin_bulk_channels', { bulk_type: type, ids: JSON.stringify(ids), ...extra });
  } catch (e) {
    let count = 0, failed = 0;
    for (const id of ids) {
      try {
        if (type === 'delete') await api('admin_delete_channel', { id });
        else if (type === 'activate' || type === 'deactivate') {
          const c = STATE.channels.find(x => x.id == id); if (!c) { failed++; continue; }
          await api('admin_update_channel', { id: c.id, name: c.name, number: c.number, category: c.category, url: c.original_url || '', is_active: type === 'activate' ? 1 : 0 });
        } else if (type === 'move') {
          const c = STATE.channels.find(x => x.id == id); if (!c) { failed++; continue; }
          await api('admin_update_channel', { id: c.id, name: c.name, number: c.number, category: extra.target_category, url: c.original_url || '', is_active: c.is_active });
        }
        count++;
      } catch(_) { failed++; }
    }
    return { affected: count, message: failed ? `تم على ${count} (فشل ${failed})` : `تم على ${count}` };
  }
}

function switchTab(tab, el) {
  document.querySelectorAll('.top-nav .nav-btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.bottom-nav-item').forEach(b => b.classList.remove('active'));
  const topBtn = $('nav-' + tab); if (topBtn) topBtn.classList.add('active');
  const botBtn = $('bnav-' + tab); if (botBtn) botBtn.classList.add('active');
  document.querySelectorAll('.tab-pane').forEach(c => c.classList.remove('active'));
  $('tab-' + tab).classList.add('active');
  if (tab !== 'channels') $('bulkBar').classList.remove('show');
  localStorage.setItem('admin_tab', tab);
}

async function refreshAll() {
  try {
    const [stats, chs, usrs, cats, cardStats, resData] = await Promise.all([
      api('admin_stats'), api('admin_list_channels'), api('admin_list_users'), api('admin_get_categories'), api('admin_card_stats'),
      api('admin_list_resellers').catch(() => ({ resellers: [] }))
    ]);
    STATE.channels = chs.channels || [];
    STATE.users = usrs.users || [];
    STATE.categories = cats.categories || [];
    STATE.resellers = (resData && resData.resellers) || [];

    $('stChannels').textContent = stats.total_channels || 0;
    $('stActiveCh').textContent = stats.active_channels || 0;
    $('stUsers').textContent = stats.total_users || 0;
    $('stActiveUsers').textContent = stats.active_users || 0;
    $('stExpiredUsers').textContent = stats.expired_users || 0;
    $('stCardsUnused').textContent = (cardStats && cardStats.unused) || 0;
    if ($('stResellers')) $('stResellers').textContent = stats.active_resellers ?? STATE.resellers.filter(r => r.is_active == 1).length;
    if ($('stResellerPoints')) $('stResellerPoints').textContent = stats.total_reseller_points ?? STATE.resellers.reduce((a, r) => a + (parseInt(r.points)||0), 0);

    const catSel = $('chFilterCat');
    const cur = catSel.value;
    const sortedCats = getSortedCategories();
    catSel.innerHTML = '<option value="">كل الباقات</option>' +
      sortedCats.map(c => `<option value="${esc(c.name)}">${esc(c.name)}</option>`).join('');
    if (sortedCats.some(c => c.name === cur)) catSel.value = cur;

    renderCategoryChips();
    filterAndPaginateChannels();
    filterAndPaginateUsers();
    loadCards();
    renderResellersTable();
    loadActiveDomains();
    loadDataTxtServers();
    loadAppUpdateConfig();

    const validIds = new Set(STATE.channels.map(c => c.id));
    [...selectedChannels].forEach(id => { if (!validIds.has(id)) selectedChannels.delete(id); });
    updateChannelSelectionUI();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

/* ============ الترتيب: دوال مساعدة ============ */
function getSortedCategories() {
  return [...STATE.categories].sort((a, b) => {
    const sa = parseInt(a.sort_order) || 0;
    const sb = parseInt(b.sort_order) || 0;
    return sa - sb || String(a.name).localeCompare(String(b.name), 'ar');
  });
}

function renderCategoryChips() {
  const container = $('catChips');
  const catCounts = {};
  STATE.channels.forEach(c => {
    const cat = c.category || 'عام';
    catCounts[cat] = (catCounts[cat] || 0) + 1;
  });

  const currentFilter = $('chFilterCat').value;
  const sortedCats = getSortedCategories().filter(c => catCounts[c.name] > 0);
  const knownNames = new Set(sortedCats.map(c => c.name));
  Object.keys(catCounts).forEach(n => {
    if (!knownNames.has(n)) sortedCats.push({ name: n, sort_order: 99999 });
  });
  chipCategories = sortedCats.map(c => c.name);

  let html = `<div class="cat-chip ${!currentFilter ? 'active' : ''}" data-cat="">
    <span>🎯 الكل</span><span class="chip-count">${STATE.channels.length}</span>
  </div>`;

  sortedCats.forEach((cat, idx) => {
    const name = cat.name;
    const count = catCounts[name] || 0;
    const allActive = STATE.channels.filter(c => c.category === name).every(c => c.is_active == 1);
    html += `
      <div class="cat-chip ${currentFilter === name ? 'active' : ''}" data-idx="${idx}">
        <span>${esc(name)}</span>
        <span class="chip-count">${count}</span>
        <div class="chip-actions">
          <button class="chip-btn power" data-action="toggle" data-idx="${idx}" title="${allActive ? 'تعطيل' : 'تفعيل'}">⚡</button>
          <button class="chip-btn" data-action="delete" data-idx="${idx}" title="حذف الباقة">✕</button>
        </div>
      </div>`;
  });
  container.innerHTML = html;

  container.querySelectorAll('.cat-chip').forEach(chip => {
    chip.addEventListener('click', (e) => {
      if (e.target.closest('.chip-actions')) return;
      $('chFilterCat').value = chip.dataset.cat || '';
      chPage = 1;
      filterAndPaginateChannels();
      renderCategoryChips();
    });
  });

  container.querySelectorAll('[data-action]').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const cat = chipCategories[parseInt(btn.dataset.idx)];
      if (!cat) return;
      if (btn.dataset.action === 'delete') {
        const channels = STATE.channels.filter(c => c.category === cat);
        if (!confirm(`⚠️ حذف باقة "${cat}" كاملة (${channels.length} قناة)؟`)) return;
        let result; try { result = await api('admin_bulk_channels', { bulk_type: 'delete_category', category: cat }); }
        catch(e) { let count = 0; for (const c of channels) { try { await api('admin_delete_channel', { id: c.id }); count++; } catch(_) {} } result = { affected: count }; }
        toast(`🗑 تم حذف ${result.affected} قناة`, 'success');
        if ($('chFilterCat').value === cat) $('chFilterCat').value = '';
        refreshAll();
      } else if (btn.dataset.action === 'toggle') {
        const channels = STATE.channels.filter(c => c.category === cat);
        const targetState = channels.every(c => c.is_active == 1) ? 0 : 1;
        if (!confirm(`${targetState ? 'تفعيل' : 'تعطيل'} ${channels.length} قناة في "${cat}"؟`)) return;
        const res = await bulkOperation(targetState ? 'activate' : 'deactivate', channels.map(c => c.id));
        toast(res.message, 'success');
        refreshAll();
      }
    });
  });
}

function onSearchChannels() {
  $('chClearBtn').style.display = $('chSearch').value ? 'block' : 'none';
  chPage = 1; filterAndPaginateChannels(); renderCategoryChips();
}
function clearSearch() { $('chSearch').value = ''; onSearchChannels(); }

function filterAndPaginateChannels() {
  const q = ($('chSearch').value || '').toLowerCase().trim();
  const cat = $('chFilterCat').value;
  filteredChannels = STATE.channels.filter(c => {
    const matchQ = !q || c.name.toLowerCase().includes(q) || String(c.number).includes(q) || (c.category||'').toLowerCase().includes(q);
    return matchQ && (!cat || c.category === cat);
  });
  
  const catOrder = {};
  getSortedCategories().forEach((c, i) => catOrder[c.name] = i);
  
  filteredChannels.sort((a,b) => {
    const ca = catOrder[a.category] ?? 99999;
    const cb = catOrder[b.category] ?? 99999;
    if (ca !== cb) return ca - cb;
    const sa = parseInt(a.sort_order) || 0;
    const sb = parseInt(b.sort_order) || 0;
    if (sa !== sb) return sa - sb;
    return (a.number || 0) - (b.number || 0);
  });
  
  renderChannelsTable();
}

function renderChannelsTable() {
  const total = filteredChannels.length;
  const totalPages = Math.ceil(total / chPer) || 1;
  if (chPage > totalPages) chPage = totalPages;
  const pageItems = filteredChannels.slice((chPage - 1) * chPer, (chPage - 1) * chPer + chPer);

  const catMembers = {};
  STATE.channels.forEach(c => {
    const k = c.category || 'عام';
    (catMembers[k] = catMembers[k] || []).push(c);
  });
  Object.values(catMembers).forEach(arr => arr.sort((a,b) => {
    const sa = parseInt(a.sort_order)||0, sb = parseInt(b.sort_order)||0;
    return sa - sb || (a.number||0) - (b.number||0);
  }));

  $('channelsBody').innerHTML = pageItems.length ? pageItems.map(c => {
    const url = c.original_url || '';
    const short = url.length > 30 ? url.substring(0,30)+'...' : url;
    const isSelected = selectedChannels.has(c.id);
    const siblings = catMembers[c.category || 'عام'] || [];
    const pos = siblings.findIndex(x => x.id == c.id);
    const isFirst = pos === 0;
    const isLast  = pos === siblings.length - 1;

    return `
    <tr data-ch-id="${c.id}" class="${isSelected ? 'selected' : ''}">
      <td class="checkbox-col"><input type="checkbox" class="row-check" ${isSelected ? 'checked' : ''} onchange="toggleChannelSelect(${c.id}, this.checked)"></td>
      <td><span class="inline-edit" data-id="${c.id}">${c.number}</span></td>
      <td><b>${esc(c.name)}</b></td>
      <td><span class="badge info">${esc(c.category)}</span></td>
      <td class="hide-mobile"><span class="url-text" title="${esc(url)}">${esc(short)}</span></td>
      <td><label class="toggle-switch"><input type="checkbox" ${c.is_active==1?'checked':''} onchange="toggleChannelActive(${c.id}, this)"><span class="toggle-slider"></span></label></td>
      <td style="white-space:nowrap">
        <div class="row-move-btns">
          <button class="row-move-btn" onclick="event.stopPropagation();moveChannel(${c.id},-1)" ${isFirst?'disabled':''} title="أعلى">↑</button>
          <button class="row-move-btn" onclick="event.stopPropagation();moveChannel(${c.id},1)"  ${isLast?'disabled':''}  title="أسفل">↓</button>
        </div>
        <button class="btn outline icon-only" onclick="event.stopPropagation();openEditCh(${c.id})">✏️</button>
        <button class="btn danger icon-only" onclick="event.stopPropagation();delChannel(${c.id})">🗑</button>
      </td>
    </tr>`;
  }).join('') : '<tr><td colspan="7" style="text-align:center;padding:20px;color:var(--txd)">لا توجد نتائج</td></tr>';
  renderPagination('chPagination', total, chPage, chPer, 'goToChPage');
  updateChannelSelectionUI();
}

function goToChPage(p) { chPage = p; renderChannelsTable(); }

/* ============ تحريك قناة داخل باقتها ============ */
async function moveChannel(id, dir) {
  const c = STATE.channels.find(x => x.id == id);
  if (!c) return;

  const siblings = STATE.channels
    .filter(x => (x.category || 'عام') === (c.category || 'عام'))
    .sort((a,b) => {
      const sa = parseInt(a.sort_order)||0, sb = parseInt(b.sort_order)||0;
      return sa - sb || (a.number||0) - (b.number||0);
    });

  const idx = siblings.findIndex(x => x.id == id);
  const newIdx = idx + dir;
  if (newIdx < 0 || newIdx >= siblings.length) return;

  [siblings[idx], siblings[newIdx]] = [siblings[newIdx], siblings[idx]];
  siblings.forEach((s, i) => s.sort_order = i);

  filterAndPaginateChannels();

  try {
    await api('admin_reorder_channels', {
      category: c.category || 'عام',
      order: JSON.stringify(siblings.map(s => s.id))
    });
  } catch(e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
    refreshAll();
  }
}

/* ============ نافذة ترتيب الباقات ============ */
function openReorderCatsModal() {
  const cats = getSortedCategories();
  if (!cats.length) return toast('لا توجد باقات', 'error');

  const counts = {};
  STATE.channels.forEach(c => {
    const k = c.category || 'عام';
    counts[k] = (counts[k] || 0) + 1;
  });

  $('reorderCatsList').innerHTML = cats.map((c, i) => `
    <div class="reorder-item" draggable="true" data-name="${esc(c.name)}">
      <span class="drag-handle">⋮⋮</span>
      <span class="drag-label">${esc(c.name)}</span>
      <span class="badge info">${counts[c.name] || 0}</span>
      <div class="move-btns">
        <button class="move-btn" onclick="moveCatBtn(this,-1)" ${i===0?'disabled':''} title="أعلى">↑</button>
        <button class="move-btn" onclick="moveCatBtn(this,1)"  ${i===cats.length-1?'disabled':''} title="أسفل">↓</button>
      </div>
    </div>
  `).join('');

  setupCatDragDrop();
  openModal('reorderCatsModal');
}

function setupCatDragDrop() {
  const list = $('reorderCatsList');
  let dragEl = null;
  list.querySelectorAll('.reorder-item').forEach(item => {
    item.addEventListener('dragstart', e => {
      dragEl = item; item.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
    });
    item.addEventListener('dragend', () => {
      item.classList.remove('dragging');
      dragEl = null;
      refreshMoveBtns(list);
    });
    item.addEventListener('dragover', e => {
      e.preventDefault();
      if (!dragEl || dragEl === item) return;
      const r = item.getBoundingClientRect();
      const before = e.clientY < r.top + r.height / 2;
      item.parentNode.insertBefore(dragEl, before ? item : item.nextSibling);
    });
  });
}

function refreshMoveBtns(list) {
  const items = [...list.querySelectorAll('.reorder-item')];
  items.forEach((it, i) => {
    const [up, dn] = it.querySelectorAll('.move-btn');
    if (up) up.disabled = i === 0;
    if (dn) dn.disabled = i === items.length - 1;
  });
}

function moveCatBtn(btn, dir) {
  const item = btn.closest('.reorder-item');
  const list = item.parentNode;
  if (dir === -1) {
    const prev = item.previousElementSibling;
    if (prev) list.insertBefore(item, prev);
  } else {
    const next = item.nextElementSibling;
    if (next) list.insertBefore(next, item);
  }
  refreshMoveBtns(list);
}

async function saveCategoryOrder() {
  const order = [...document.querySelectorAll('#reorderCatsList .reorder-item')].map(el => el.dataset.name);
  try {
    await api('admin_reorder_categories', { order: JSON.stringify(order) });
    toast('✅ تم حفظ ترتيب الباقات', 'success');
    closeModal('reorderCatsModal');
    refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function toggleChannelActive(id, input) {
  const c = STATE.channels.find(x => x.id == id); if (!c) return;
  const newState = input.checked ? 1 : 0; const oldState = c.is_active;
  try {
    await api('admin_update_channel', { id: c.id, name: c.name, number: c.number, category: c.category, url: c.original_url || '', is_active: newState });
    c.is_active = newState; $('stActiveCh').textContent = STATE.channels.filter(ch => ch.is_active == 1).length;
    toast(newState ? '✅ تم التفعيل' : '⏸ تم التعطيل');
  } catch(e) { input.checked = oldState == 1; if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function toggleSelectAll() {
  const pageItems = filteredChannels.slice((chPage - 1) * chPer, (chPage - 1) * chPer + chPer);
  if ($('selectAllCh').checked) pageItems.forEach(c => selectedChannels.add(c.id));
  else pageItems.forEach(c => selectedChannels.delete(c.id));
  renderChannelsTable();
}
function toggleChannelSelect(id, checked) { if (checked) selectedChannels.add(id); else selectedChannels.delete(id); updateChannelSelectionUI(); }
function clearSelection() { selectedChannels.clear(); renderChannelsTable(); }
function updateChannelSelectionUI() {
  $('bulkCount').textContent = selectedChannels.size;
  $('bulkBar').classList.toggle('show', selectedChannels.size > 0);
  document.querySelectorAll('tr[data-ch-id]').forEach(tr => tr.classList.toggle('selected', selectedChannels.has(parseInt(tr.dataset.chId))));
  const pageItems = filteredChannels.slice((chPage - 1) * chPer, (chPage - 1) * chPer + chPer);
  const allOnPage = pageItems.length > 0 && pageItems.every(c => selectedChannels.has(c.id));
  const someOnPage = pageItems.some(c => selectedChannels.has(c.id));
  const sa = $('selectAllCh'); if (sa) { sa.checked = allOnPage; sa.indeterminate = someOnPage && !allOnPage; }
}

async function bulkAction(type) {
  const ids = [...selectedChannels]; if (!ids.length) return;
  const labels = { activate: 'تفعيل', deactivate: 'تعطيل', delete: 'حذف' };
  if (!confirm(`${labels[type]} ${ids.length} قناة؟${type === 'delete' ? '\n⚠️ لا يمكن التراجع!' : ''}`)) return;
  try {
    const result = await bulkOperation(type, ids);
    toast(result.message, 'success'); clearSelection(); refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function openBulkMoveModal() {
  if (selectedChannels.size === 0) return toast('اختر قنوات أولاً', 'error');
  $('bulkMoveCount').textContent = selectedChannels.size;
  $('existingCats').innerHTML = getSortedCategories().map(c => `<option value="${esc(c.name)}">`).join('');
  $('bulkMoveTarget').value = ''; openModal('bulkMoveModal');
}

async function bulkMove() {
  const target = $('bulkMoveTarget').value.trim(); if (!target) return toast('أدخل اسم الباقة', 'error');
  const ids = [...selectedChannels];
  if (!confirm(`نقل ${ids.length} قناة إلى "${target}"؟`)) return;
  try {
    const result = await bulkOperation('move', ids, { target_category: target });
    toast(result.message, 'success'); closeModal('bulkMoveModal'); clearSelection(); refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function addChannel() {
  if(!$('addChName').value.trim() || !$('addChUrl').value.trim()) return toast('أدخل الاسم والرابط', 'error');
  try {
    await api('admin_add_channel', { name: $('addChName').value.trim(), url: $('addChUrl').value.trim(), number: $('addChNumber').value, category: $('addChCat').value.trim() || 'عام' });
    toast('✅ تمت الإضافة', 'success'); closeModal('addChannelModal'); $('addChName').value = ''; $('addChUrl').value = ''; refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function openEditCh(id) {
  const c = STATE.channels.find(x => x.id == id); if (!c) return;
  $('editChId').value = c.id; $('editChName').value = c.name; $('editChNumber').value = c.number; $('editChCat').value = c.category; $('editChUrl').value = ''; $('editChActive').value = c.is_active; openModal('editChannelModal');
}

async function saveChannelEdit() {
  try {
    const data = { id: $('editChId').value, name: $('editChName').value, number: $('editChNumber').value, category: $('editChCat').value, is_active: $('editChActive').value };
    const newUrl = $('editChUrl').value.trim(); if (newUrl) data.url = newUrl;
    await api('admin_update_channel', data); toast('✅ تم التحديث', 'success'); closeModal('editChannelModal'); refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function delChannel(id) {
  const c = STATE.channels.find(x => x.id == id); if (!c) return;
  if(!confirm(`حذف القناة "${c.name}"؟`)) return;
  try { await api('admin_delete_channel', { id }); selectedChannels.delete(id); toast('🗑 تم الحذف', 'success'); refreshAll(); }
  catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function previewM3U() {
  const url = $('m3uUrl').value.trim(); if(!url) return toast('أدخل رابط M3U', 'error');
  const btn = $('btnPrevM3U'); btn.disabled = true; btn.textContent = 'جارٍ الجلب...';
  try {
    const d = await api('admin_preview_m3u', { url });
    $('m3uPreview').style.display = 'block';
    $('m3uSummary').textContent = `تم العثور على ${d.total} قناة في ${d.groups.length} باقة`;
    $('m3uGroups').innerHTML = d.groups.map(g => `
      <label style="display:flex;align-items:center;gap:8px;padding:8px;border-bottom:1px solid var(--bd);cursor:pointer">
        <input type="checkbox" class="m3u-g" value="${esc(g.name)}" checked>
        <span style="flex:1;font-size:13px">${esc(g.name)}</span>
        <span class="badge info">${g.count}</span>
      </label>
    `).join('');
    $('btnImportM3U').style.display = 'inline-flex'; $('btnImportM3U').dataset.url = url;
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
  finally { btn.disabled = false; btn.textContent = '🔍 معاينة'; }
}

async function importM3U() {
  const url = $('btnImportM3U').dataset.url;
  const groups = [...document.querySelectorAll('.m3u-g:checked')].map(el => el.value);
  if(!groups.length) return toast('اختر باقة', 'error');
  const btn = $('btnImportM3U'); btn.disabled = true; btn.textContent = 'جارٍ الاستيراد...';
  try {
    const d = await api('admin_import_m3u', { url, groups });
    toast(d.message, 'success'); closeModal('m3uModal'); $('m3uPreview').style.display = 'none'; $('btnImportM3U').style.display = 'none'; $('m3uUrl').value = ''; refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
  finally { btn.disabled = false; btn.textContent = '📥 استيراد'; }
}

function onSearchUsers() { uPage = 1; filterAndPaginateUsers(); }

function filterAndPaginateUsers() {
  const q = ($('uSearch').value || '').toLowerCase().trim(); const st = $('uFilterStatus').value;
  filteredUsers = STATE.users.filter(u => {
    const isExp = daysLeft(u.expire_date) < 0;
    const matchQ = !q || u.device_id.toLowerCase().includes(q) || (u.device_name||'').toLowerCase().includes(q);
    let matchSt = true; if (st === 'active') matchSt = u.is_active == 1 && !isExp; else if (st === 'expired') matchSt = isExp; else if (st === 'disabled') matchSt = u.is_active == 0;
    return matchQ && matchSt;
  });
  renderUsersTable();
}

function renderUsersTable() {
  const total = filteredUsers.length; const totalPages = Math.ceil(total / uPer) || 1; if (uPage > totalPages) uPage = totalPages;
  const pageItems = filteredUsers.slice((uPage - 1) * uPer, (uPage - 1) * uPer + uPer);
  $('usersBody').innerHTML = pageItems.length ? pageItems.map(u => {
    const d = daysLeft(u.expire_date); const isExp = d < 0;
    return `
    <tr>
      <td><span class="code-text" style="color:var(--inf)">${esc(u.device_id)}</span></td>
      <td>
        ${esc(u.device_name || '—')}
        ${u.has_pin == 1 ? '<span class="badge wn" style="margin-right:6px;font-size:10.5px">🔒 مقفل بـ PIN</span>' : ''}
      </td>
      <td>${u.expire_date}</td>
      <td><span class="badge ${d>7?'ok':'dn'}">${isExp?'منتهي':d+' يوم'}</span></td>
      <td><span class="badge ${u.is_active==1&&!isExp?'ok':'dn'}">${u.is_active==1?(isExp?'منتهي':'نشط'):'معطّل'}</span></td>
      <td style="white-space:nowrap">
        <button class="btn outline" style="padding:6px 10px;font-size:11.5px;border-color:${u.portal_main_enabled==1?'var(--ok)':'var(--bd)'};color:${u.portal_main_enabled==1?'var(--ok)':'var(--txd)'}" onclick="toggleUserPortalMain('${esc(u.device_id)}', ${u.portal_main_enabled==1?0:1})" title="تفعيل أو إيقاف القائمة الرئيسية لهذا المشترك فقط حسب رمز الشاشة">${u.portal_main_enabled==1?'📡 الرئيسية: مفعّلة ✓':'📡 الرئيسية: متوقفة'}</button>
        <button class="btn outline" style="padding:6px 10px;font-size:11.5px;border-color:${u.has_pin==1?'var(--wn)':'var(--bd)'};color:${u.has_pin==1?'var(--wn)':'var(--tx)'}" onclick="unlockUserPin('${esc(u.device_id)}')" title="نزع تشفير PIN للقوائم لهذا المشترك">🔓 نزع PIN</button>
        <button class="btn outline icon-only" onclick="openEditUser(${u.id})" title="تعديل المشترك">✏️</button>
        <button class="btn danger icon-only" onclick="delUser(${u.id})" title="حذف المشترك">🗑</button>
      </td>
    </tr>`;
  }).join('') : '<tr><td colspan="6" style="text-align:center;padding:20px;color:var(--txd)">لا يوجد مشتركون</td></tr>';
  renderPagination('uPagination', total, uPage, uPer, 'goToUPage');
}

function goToUPage(p) { uPage = p; renderUsersTable(); }
async function addUser() {
  try {
    await api('admin_add_user', { device_id: $('addUserId').value.trim().toUpperCase(), device_name: $('addUserName').value.trim(), expire_date: $('addUserExpire').value, notes: $('addUserNotes').value.trim() });
    toast('✅ تمت الإضافة', 'success'); closeModal('addUserModal'); refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function openEditUser(id) {
  const u = STATE.users.find(x => x.id == id); if (!u) return;
  $('editUserId').value = u.id; $('editUserDevice').value = u.device_id; $('editUserExpire').value = u.expire_date; $('editUserActive').value = u.is_active; $('editUserNotes').value = u.notes || ''; openModal('editUserModal');
}

async function saveUserEdit() {
  try {
    await api('admin_update_user', { id: $('editUserId').value, expire_date: $('editUserExpire').value, is_active: $('editUserActive').value, notes: $('editUserNotes').value });
    toast('✅ تم التحديث', 'success'); closeModal('editUserModal'); refreshAll();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}


async function unlockUserPin(deviceId) {
  const cleanDev = String(deviceId || '').trim().toUpperCase();
  if (!cleanDev) return toast('⚠️ يرجى تحديد رمز الجهاز أولاً', 'error');
  if (!confirm(`🔓 هل تريد نزع قفل تشفير PIN للقوائم الخاصة بالجهاز:\n${cleanDev} ؟\n\nسيتمكن المشترك من الدخول وتعديل قوائمه مباشرة دون طلب رقم سري.`)) return;
  try {
    const res = await api('admin_remove_user_pin', { device_id: cleanDev });
    toast(res.message || `✅ تم نزع قفل PIN للجهاز ${cleanDev}`, 'success');
    refreshAll();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  }
}

async function toggleUserPortalMain(deviceId, enableFlag) {
  const cleanDev = String(deviceId || '').trim().toUpperCase();
  if (!cleanDev) return toast('⚠️ يرجى تحديد رمز الجهاز أولاً', 'error');
  try {
    const fd = new URLSearchParams();
    fd.append('admin_direct_action', 'admin_toggle_portal_main');
    fd.append('device_id', cleanDev);
    fd.append('portal_main_enabled', enableFlag ? '1' : '0');
    const r = await fetch(window.location.pathname + '?admin_direct_action=admin_toggle_portal_main&_t=' + Date.now(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: fd.toString()
    });
    const j = await r.json();
    if (!j.success) throw new Error(j.error || 'فشل تحديث حالة القائمة الرئيسية');
    try { await api('admin_toggle_portal_main', { device_id: cleanDev, portal_main_enabled: enableFlag ? 1 : 0 }); } catch (_e) {}
    toast((j.data && j.data.message) || '✅ تم تحديث حالة القائمة الرئيسية للجهاز', 'success');
    refreshAll();
  } catch (e) {
    toast(e.message || 'تعذّر تحديث حالة القائمة الرئيسية', 'error');
  }
}

async function setDevicePortalMainByInput(enableFlag) {
  const inp = $('portalMainDeviceInput');
  const raw = (inp ? inp.value : '').trim().toUpperCase();
  if (!raw) return toast('⚠️ يرجى إدخال رمز جهاز المشترك (مثال: DEV-A1B2-C3D4)', 'error');
  await toggleUserPortalMain(raw, enableFlag);
  if (inp) inp.value = '';
}

async function unlockUserPinByInput() {
  const inp = $('pinUnlockDeviceInput');
  const raw = (inp ? inp.value : '').trim().toUpperCase();
  if (!raw) return toast('⚠️ يرجى إدخال رمز جهاز المشترك (مثال: DEV-A1B2-C3D4)', 'error');
  try {
    const res = await api('admin_remove_user_pin', { device_id: raw });
    toast(res.message || '✅ تم نزع قفل PIN بنجاح', 'success');
    if (inp) inp.value = '';
    refreshAll();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  }
}

async function delUser(id) {
  if(!confirm('حذف المشترك؟')) return;
  try { await api('admin_delete_user', { id }); toast('🗑 تم الحذف', 'success'); refreshAll(); } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function bulkRenew() {
  if(!confirm('تأكيد التجديد الجماعي؟')) return;
  try { const d = await api('admin_bulk_renew', { days: $('bulkDays').value, filter: $('bulkFilter').value }); toast(d.message, 'success'); refreshAll(); } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}


STATE.dataTxtEntries = [];


async function callAdminAppUpdateApi(action, payload = {}) {
  const fd = new FormData();
  fd.append('admin_direct_action', action);
  for (const k in payload) {
    fd.append(k, payload[k] == null ? '' : payload[k]);
  }
  try {
    const res = await fetch(location.pathname, { method: 'POST', body: fd });
    const j = await res.json();
    if (j && j.success && j.data) return j.data;
    if (j && !j.success && j.error) throw new Error(j.error);
  } catch (directErr) {
    if (directErr && String(directErr.message || '').includes('⚠️')) throw directErr;
  }
  return await api(action, payload);
}

async function loadAppUpdateConfig() {
  try {
    const res = await callAdminAppUpdateApi('admin_get_app_update');
    const u = res.app_update || {};
    if ($('updEnabled')) $('updEnabled').value = u.enabled ? '1' : '0';
    if ($('updMandatory')) $('updMandatory').value = (u.is_mandatory !== false) ? '1' : '0';
    if ($('updVersionCode')) $('updVersionCode').value = u.version_code || 27;
    if ($('updVersionName')) $('updVersionName').value = u.version_name || '27.0';
    if ($('updApkUrl')) $('updApkUrl').value = u.apk_url || '';
    if ($('updTitle') && u.title) $('updTitle').value = u.title;
    if ($('updNotes') && u.notes) $('updNotes').value = u.notes;
    const badge = $('appUpdateStatusBadge');
    if (badge) {
      badge.className = 'badge ' + (u.enabled ? 'ok' : 'dn');
      badge.textContent = u.enabled ? `🟢 مفعّل (v${u.version_name || u.version_code})` : '🔴 غير مفعّل';
    }
  } catch (e) {}
}

async function saveAppUpdateConfigNow() {
  const update_enabled = $('updEnabled').value;
  const is_mandatory   = $('updMandatory').value;
  const version_code   = $('updVersionCode').value;
  const version_name   = $('updVersionName').value.trim();
  const apk_url        = $('updApkUrl').value.trim();
  const update_title   = $('updTitle').value.trim();
  const update_notes   = $('updNotes').value.trim();

  if (update_enabled === '1' && !apk_url) {
    return toast('⚠️ يرجى إدخال الرابط المباشر لملف الـ APK لتفعيل إشعار التحديث', 'error');
  }
  try {
    const res = await callAdminAppUpdateApi('admin_save_app_update', {
      update_enabled,
      is_mandatory,
      version_code,
      version_name,
      apk_url,
      update_title,
      update_notes
    });
    // مزامنة مع api.php في الخلفية أيضاً إن أمكن
    api('admin_save_app_update', {
      update_enabled, is_mandatory, version_code, version_name, apk_url, update_title, update_notes
    }).catch(() => {});
    toast(res.message || '✅ تم حفظ إعدادات تحديث التطبيق بنجاح', 'success');
    loadAppUpdateConfig();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  }
}

async function loadDataTxtServers(showToastMsg = false) {
  try {
    const res = await api('admin_get_data_txt');
    STATE.dataTxtEntries = res.entries || [];
    const editor = $('dtRawEditor');
    if (editor && document.activeElement !== editor) {
      editor.value = res.raw_content || '';
    }
    renderDataTxtTable();
    if (showToastMsg) toast('✅ تم تحديث بيانات ملف data.txt', 'success');
  } catch (e) {}
}

let dtPage = 1;
const DT_PER_PAGE = 10;

function onSearchDataTxtTable() {
  dtPage = 1;
  renderDataTxtTable();
}

function goToDtPage(p) {
  dtPage = p;
  renderDataTxtTable();
}

function renderDataTxtTable() {
  const tbody = $('dataTxtBody');
  const badge = $('dtCountBadge');
  const all = STATE.dataTxtEntries || [];
  if (badge) badge.textContent = all.length;
  if (!tbody) return;

  const q = (($('dtSearch') && $('dtSearch').value) || '').toLowerCase().trim();
  const sortMode = ($('dtSortSelect') && $('dtSortSelect').value) || 'default';

  let filtered = all.filter(item => {
    if (!q) return true;
    return String(item.name || '').toLowerCase().includes(q) ||
           String(item.url || '').toLowerCase().includes(q) ||
           String(item.domain || '').toLowerCase().includes(q);
  });

  filtered = [...filtered].sort((a, b) => {
    if (sortMode === 'playlists') return (b.playlists_count || 0) - (a.playlists_count || 0);
    if (sortMode === 'name_az') return String(a.name || '').localeCompare(String(b.name || ''));
    if (sortMode === 'domain_az') return String(a.domain || '').localeCompare(String(b.domain || ''));
    return (a.index || 0) - (b.index || 0);
  });

  const total = filtered.length;
  const totalPages = Math.ceil(total / DT_PER_PAGE) || 1;
  if (dtPage > totalPages) dtPage = totalPages;
  const pageItems = filtered.slice((dtPage - 1) * DT_PER_PAGE, dtPage * DT_PER_PAGE);

  if (!pageItems.length) {
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:22px;color:var(--txd)">لا توجد سيرفرات مطابقة في data.txt</td></tr>';
    renderPagination('dtPagination', 0, 1, DT_PER_PAGE, 'goToDtPage');
    return;
  }

  tbody.innerHTML = pageItems.map((item, idx) => {
    const rowNum = (dtPage - 1) * DT_PER_PAGE + idx + 1;
    return `
    <tr>
      <td><b>${rowNum}</b></td>
      <td><b style="color:var(--gold)">${esc(item.name)}</b></td>
      <td><span class="code-text" dir="ltr" style="color:var(--inf)" onclick="copyCleanCode('${esc(item.url)}')">${esc(item.url)}</span></td>
      <td><span class="code-text" dir="ltr">${esc(item.domain)}</span></td>
      <td>
        <span class="badge ${item.playlists_count > 0 ? 'ok' : ''}">
          ${item.playlists_count || 0} قائمة مشترك
        </span>
      </td>
      <td style="white-space:nowrap">
        <button class="btn primary" style="padding:6px 12px;font-size:12px" onclick="openEditDataTxtModal(${item.index})">✏️ تعديل الاسم / الدومين</button>
        <button class="btn danger icon-only" onclick="deleteDataTxtServer(${item.index})" title="حذف من data.txt">🗑</button>
      </td>
    </tr>`;
  }).join('');

  renderPagination('dtPagination', total, dtPage, DT_PER_PAGE, 'goToDtPage');
}

async function addDataTxtServerNow() {
  const new_name = ($('dtAddName').value || '').trim();
  const new_url  = ($('dtAddUrl').value || '').trim();
  if (!new_name || !new_url) {
    return toast('⚠️ يرجى إدخال اسم السيرفر ورابط الدومين / الهوست', 'error');
  }
  try {
    const res = await api('admin_save_data_txt_entry', { mode: 'add', new_name, new_url });
    toast(res.message || '✅ تمت الإضافة إلى data.txt', 'success');
    $('dtAddName').value = '';
    $('dtAddUrl').value = '';
    STATE.dataTxtEntries = res.entries || [];
    if ($('dtRawEditor') && res.raw_content) $('dtRawEditor').value = res.raw_content;
    renderDataTxtTable();
    loadActiveDomains();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  }
}

function openEditDataTxtModal(entryIndex) {
  const item = (STATE.dataTxtEntries || []).find(x => x.index == entryIndex);
  if (!item) return;
  $('dtEditOldName').value = item.name;
  $('dtEditOldUrl').value = item.url;
  $('dtEditNewName').value = item.name;
  $('dtEditNewUrl').value = item.url;
  openModal('editDataTxtServerModal');
}

async function saveEditDataTxtModal() {
  const old_name = ($('dtEditOldName').value || '').trim();
  const old_url  = ($('dtEditOldUrl').value || '').trim();
  const new_name = ($('dtEditNewName').value || '').trim();
  const new_url  = ($('dtEditNewUrl').value || '').trim();
  if (!new_name || !new_url) {
    return toast('⚠️ يرجى إدخال اسم السيرفر ورابط الدومين الجديد', 'error');
  }
  try {
    const res = await api('admin_save_data_txt_entry', {
      mode: 'edit',
      old_name,
      old_url,
      new_name,
      new_url
    });
    toast(res.message || '✅ تم التحديث بنجاح', 'success');
    closeModal('editDataTxtServerModal');
    STATE.dataTxtEntries = res.entries || [];
    if ($('dtRawEditor') && res.raw_content) $('dtRawEditor').value = res.raw_content;
    renderDataTxtTable();
    loadActiveDomains();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  }
}

async function deleteDataTxtServer(entryIndex) {
  const item = (STATE.dataTxtEntries || []).find(x => x.index == entryIndex);
  if (!item) return;
  if (!confirm(`⚠️ هل تريد حذف السيرفر (${item.name} — ${item.url}) من ملف data.txt؟`)) return;
  try {
    const res = await api('admin_delete_data_txt_entry', { name: item.name, url: item.url });
    toast(res.message || '🗑 تم الحذف من data.txt', 'success');
    STATE.dataTxtEntries = res.entries || [];
    if ($('dtRawEditor') && res.raw_content) $('dtRawEditor').value = res.raw_content;
    renderDataTxtTable();
    loadActiveDomains();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  }
}

async function saveRawDataTxtNow() {
  const editor = $('dtRawEditor');
  const raw_content = editor ? editor.value : '';
  if (!raw_content.trim()) {
    return toast('⚠️ محتوى ملف data.txt فارغ', 'error');
  }
  if (!confirm('💾 تأكيد حفظ التعديلات اليدوية في ملف data.txt وتحديث دومينات وأسماء السيرفرات عند جميع الزبائن في قاعدة البيانات؟')) return;
  try {
    const res = await api('admin_save_data_txt_raw', { raw_content });
    toast(res.message || '✅ تم حفظ ملف data.txt وتحديث الدومينات بنجاح', 'success');
    STATE.dataTxtEntries = res.entries || [];
    if (editor && res.raw_content) editor.value = res.raw_content;
    renderDataTxtTable();
    loadActiveDomains();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  }
}

/* ============ كشف الدومينات المسجلة للأدمن (جدول 10 دومينات في الصفحة + نافذة منبثقة + محرك بحث ذكي) ============ */
STATE.activeDomains = [];
let admDomInlinePage = 1;
let admDomModalPage = 1;
const ADM_DOM_PER_PAGE = 10;

function getFilteredAndSortedAdminDomains(queryStr, sortMode) {
  const q = String(queryStr || '').toLowerCase().trim();
  const list = (STATE.activeDomains || []).filter(item => {
    if (!q) return true;
    return String(item.domain || '').toLowerCase().includes(q) ||
           String(item.host || '').toLowerCase().includes(q) ||
           String(item.server_name || '').toLowerCase().includes(q);
  });
  return [...list].sort((a, b) => {
    if (sortMode === 'devices') {
      if ((b.devices_count || 0) !== (a.devices_count || 0)) return (b.devices_count || 0) - (a.devices_count || 0);
    } else if (sortMode === 'channels') {
      if ((b.channels_count || 0) !== (a.channels_count || 0)) return (b.channels_count || 0) - (a.channels_count || 0);
    } else if (sortMode === 'az') {
      return String(a.host || a.domain || '').localeCompare(String(b.host || b.domain || ''));
    }
    const scoreA = ((a.playlists_count || 0) * 100) + ((a.devices_count || 0) * 10) + (a.channels_count || 0);
    const scoreB = ((b.playlists_count || 0) * 100) + ((b.devices_count || 0) * 10) + (b.channels_count || 0);
    if (scoreB !== scoreA) return scoreB - scoreA;
    return String(a.host || a.domain || '').localeCompare(String(b.host || b.domain || ''));
  });
}

function selectAdminOldDomain(domainVal, fromModal = false) {
  const inp = $('bulkOldDomain');
  if (inp) inp.value = domainVal;
  if (fromModal) closeModal('activeDomainsModal');
  const newInp = $('bulkNewDomain');
  if (newInp) {
    newInp.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setTimeout(() => newInp.focus(), 120);
  }
  toast(`🎯 تم اختيار الدومين القديم: ${domainVal} — أدخل الدومين الجديد المقترح الآن`, 'success');
}

function renderAdminDomainsSection(tbodyId, pagId, searchId, sortId, pageNum, pageFnName, isModal) {
  const tbody = $(tbodyId);
  if (!tbody) return;
  const q = ($(searchId) && $(searchId).value) || '';
  const sortMode = ($(sortId) && $(sortId).value) || 'usage';
  const filtered = getFilteredAndSortedAdminDomains(q, sortMode);
  const total = filtered.length;
  const totalPages = Math.ceil(total / ADM_DOM_PER_PAGE) || 1;
  const safePage = Math.min(Math.max(1, pageNum), totalPages);
  const pageItems = filtered.slice((safePage - 1) * ADM_DOM_PER_PAGE, safePage * ADM_DOM_PER_PAGE);

  if (!pageItems.length) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:22px;color:var(--txd)">لا توجد دومينات مطابقة للبحث</td></tr>';
    renderPagination(pagId, 0, 1, ADM_DOM_PER_PAGE, pageFnName);
    return;
  }

  tbody.innerHTML = pageItems.map((item, idx) => {
    const rowNum = (safePage - 1) * ADM_DOM_PER_PAGE + idx + 1;
    const badges = [];
    badges.push(`<span class="badge ${item.playlists_count > 0 ? 'ok' : ''}">📋 ${item.playlists_count || 0} قائمة</span>`);
    if ((item.devices_count || 0) > 0) badges.push(`<span class="badge wn">📺 ${item.devices_count} شاشة</span>`);
    if ((item.channels_count || 0) > 0) badges.push(`<span class="badge">📡 ${item.channels_count} قناة</span>`);
    if (item.in_data_txt) badges.push(`<span class="badge ok" style="font-size:10.5px">✓ data.txt</span>`);

    return `
      <tr>
        <td><b>${rowNum}</b></td>
        <td>
          <span class="code-text" dir="ltr" style="color:var(--inf)" onclick="copyCleanCode('${esc(item.domain)}')" title="اضغط لنسخ الدومين">${esc(item.domain)}</span>
        </td>
        <td><b style="color:var(--gold)">${esc(item.server_name || 'سيرفر مشتركين')}</b></td>
        <td><div style="display:flex;gap:5px;flex-wrap:wrap">${badges.join('')}</div></td>
        <td style="white-space:nowrap">
          <button type="button" class="btn primary" style="padding:6px 11px;font-size:11.5px;background:var(--inf);color:#fff" onclick="selectAdminOldDomain('${esc(item.domain)}', ${isModal ? 'true' : 'false'})">
            🎯 اختيار كدومين قديم
          </button>
          <button type="button" class="btn outline icon-only" onclick="copyCleanCode('${esc(item.domain)}')" title="نسخ الدومين">📋</button>
        </td>
      </tr>
    `;
  }).join('');

  renderPagination(pagId, total, safePage, ADM_DOM_PER_PAGE, pageFnName);
}

function onSearchAdminDomainsInline() {
  admDomInlinePage = 1;
  renderAdminDomainsSection('admDomainsInlineBody', 'admDomainsInlinePagination', 'admDomInlineSearch', 'admDomInlineSort', admDomInlinePage, 'goToAdmDomInlinePage', false);
}

function goToAdmDomInlinePage(p) {
  admDomInlinePage = p;
  renderAdminDomainsSection('admDomainsInlineBody', 'admDomainsInlinePagination', 'admDomInlineSearch', 'admDomInlineSort', admDomInlinePage, 'goToAdmDomInlinePage', false);
}

function onSearchAdminDomainsModal() {
  admDomModalPage = 1;
  renderAdminDomainsSection('admDomainsModalBody', 'admDomainsModalPagination', 'admDomModalSearch', 'admDomModalSort', admDomModalPage, 'goToAdmDomModalPage', true);
}

function goToAdmDomModalPage(p) {
  admDomModalPage = p;
  renderAdminDomainsSection('admDomainsModalBody', 'admDomainsModalPagination', 'admDomModalSearch', 'admDomModalSort', admDomModalPage, 'goToAdmDomModalPage', true);
}

function openActiveDomainsModal() {
  admDomModalPage = 1;
  if ($('admDomModalSearch')) $('admDomModalSearch').value = '';
  renderAdminDomainsSection('admDomainsModalBody', 'admDomainsModalPagination', 'admDomModalSearch', 'admDomModalSort', admDomModalPage, 'goToAdmDomModalPage', true);
  openModal('activeDomainsModal');
  setTimeout(() => { if ($('admDomModalSearch')) $('admDomModalSearch').focus(); }, 100);
}

async function loadActiveDomains(showMsg = false) {
  try {
    const d = await api('admin_list_active_domains');
    STATE.activeDomains = d.domains || [];
    if ($('lblAdminDomainsCount')) $('lblAdminDomainsCount').textContent = STATE.activeDomains.length;
    renderAdminDomainsSection('admDomainsInlineBody', 'admDomainsInlinePagination', 'admDomInlineSearch', 'admDomInlineSort', admDomInlinePage, 'goToAdmDomInlinePage', false);
    renderAdminDomainsSection('admDomainsModalBody', 'admDomainsModalPagination', 'admDomModalSearch', 'admDomModalSort', admDomModalPage, 'goToAdmDomModalPage', true);
    if (showMsg) toast(`✅ تم كشف وتحديث (${STATE.activeDomains.length}) دومين مسجل`, 'success');
  } catch (e) {}
}

async function bulkReplaceDomainNow() {
  const old_domain = ($('bulkOldDomain').value || '').trim();
  const new_domain = ($('bulkNewDomain').value || '').trim();
  if (!old_domain || !new_domain) {
    return toast('⚠️ يرجى إدخال الدومين القديم المستهدف والدومين الجديد المقترح', 'error');
  }
  if (!confirm(`⚠️ تأكيد استبدال الدومين القديم:
${old_domain}

بالدومين الجديد:
${new_domain}

لجميع المستخدمين والقوائم والقنوات دفعة واحدة؟`)) return;

  const btn = $('btnReplaceDomain');
  btn.disabled = true;
  btn.textContent = '⏳ جارٍ التحديث للجميع...';
  const resBox = $('domainReplaceResult');
  if (resBox) resBox.style.display = 'none';

  try {
    const d = await api('admin_bulk_replace_domain', { old_domain, new_domain });
    toast(d.message, 'success');
    if (resBox) {
      resBox.style.display = 'block';
      resBox.textContent = `${d.message} (${d.old_domain} ⬅ ${d.new_domain})`;
    }
    $('bulkOldDomain').value = '';
    $('bulkNewDomain').value = '';
    await refreshAll();
  } catch (e) {
    if (e.message !== 'انتهت الجلسة') toast(e.message, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = '⚡ تغيير الدومين للجميع الآن';
  }
}

function copyCleanCode(code) {
  const clean = String(code || '').replace(/[\u200B-\u200F\u202A-\u202E\u2060-\u2069\uFEFF]/g, '').trim().toUpperCase();
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(clean).then(() => toast('📋 تم نسخ رمز القسيمة بالتنسيق الصحيح: ' + clean, 'success'));
  } else {
    const ta = document.createElement('textarea');
    ta.value = clean;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    toast('📋 تم نسخ رمز القسيمة: ' + clean, 'success');
  }
}

async function loadCards() {
  try {
    const d = await api('admin_list_cards', { status: '', type: '' });
    const pkgLabels = { trial: 'تجربة 3 أيام', '1month': 'شهر (30 يوم)', '3months': '3 أشهر (90 يوم)', '6months': '6 أشهر (180 يوم)', '1year': 'سنة (365 يوم)' };
    $('cardsBody').innerHTML = d.cards.length ? d.cards.slice(0, 80).map(c => {
      const srcBadge = c.reseller_name
        ? `<span class="badge warn">💼 ${esc(c.reseller_name)} (${c.points_cost || 0} ن)</span>`
        : `<span class="badge info">👑 الإدارة</span>`;
      const devUsed = c.used_by_device || c.redemption_device || '';
      return `
      <tr>
        <td><span class="code-text" dir="ltr" onclick="copyCleanCode('${esc(c.code_formatted)}')" title="اضغط للنسخ بالتنسيق الصحيح">${esc(c.code_formatted)}</span></td>
        <td><span class="badge info">${esc(pkgLabels[c.type_label] || c.type_label)}</span></td>
        <td><b>${c.duration_days}</b> يوم</td>
        <td>${srcBadge}</td>
        <td class="hide-mobile" style="font-size:12px">${esc(c.batch_id)}</td>
        <td>
          <span class="badge ${c.status==='unused'?'ok':'dn'}">${c.status==='unused'?'متاح':'مستخدم'}</span>
          ${devUsed ? `<div style="font-size:11px;color:var(--txd);margin-top:3px;direction:ltr">${esc(devUsed)}</div>` : ''}
        </td>
        <td>
          <button class="btn outline icon-only" onclick="copyCleanCode('${esc(c.code_formatted)}')" title="نسخ الرمز الصافي">📋</button>
          <button class="btn danger icon-only" onclick="delCard(${c.id})" title="حذف">🗑</button>
        </td>
      </tr>`;
    }).join('') : '<tr><td colspan="7" style="text-align:center;padding:20px;color:var(--txd)">لا توجد بطاقات</td></tr>';
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

/* ============ إدارة حسابات الموزعين والنقاط ============ */
function genRandomResPass(inputId) {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  let out = '';
  for (let i = 0; i < 8; i++) out += chars.charAt(Math.floor(Math.random() * chars.length));
  $(inputId).value = out;
}

function openAddResellerModal() {
  $('addResName').value = '';
  $('addResPhone').value = '';
  $('addResUser').value = '';
  genRandomResPass('addResPass');
  $('addResPoints').value = 10;
  $('addResNotes').value = '';
  openModal('addResellerModal');
}

async function createReseller() {
  const display_name = $('addResName').value.trim();
  const username = $('addResUser').value.trim();
  const password = $('addResPass').value.trim();
  const phone = $('addResPhone').value.trim();
  const points = $('addResPoints').value;
  const notes = $('addResNotes').value.trim();
  if (!display_name || !username || !password) return toast('يرجى إدخال اسم الموزع واسم المستخدم وكلمة المرور', 'error');
  try {
    const res = await api('admin_add_reseller', { display_name, username, password, phone, points, notes });
    toast(res.message || '✅ تم فتح حساب الموزع بنجاح', 'success');
    closeModal('addResellerModal');
    refreshAll();
  } catch (e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function renderResellersTable() {
  const tbody = $('resellersBody');
  if (!tbody) return;
  const q = (($('resSearch') && $('resSearch').value) || '').toLowerCase().trim();
  const st = ($('resFilterStatus') && $('resFilterStatus').value) || '';
  const list = (STATE.resellers || []).filter(r => {
    const matchQ = !q ||
      String(r.display_name || '').toLowerCase().includes(q) ||
      String(r.username || '').toLowerCase().includes(q) ||
      String(r.phone || '').toLowerCase().includes(q) ||
      String(r.notes || '').toLowerCase().includes(q);
    let matchSt = true;
    if (st === 'active') matchSt = r.is_active == 1;
    else if (st === 'disabled') matchSt = r.is_active == 0;
    return matchQ && matchSt;
  });

  if (!list.length) {
    tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:26px;color:var(--txd)">لا يوجد موزعون حالياً — اضغط على "➕ فتح حساب موزع" لإضافة أول موزع</td></tr>';
    return;
  }

  tbody.innerHTML = list.map(r => `
    <tr>
      <td>
        <b>${esc(r.display_name)}</b>
        ${r.parent_reseller_id ? `<div style="font-size:11px;color:var(--inf);font-weight:700">↳ موزع فرعي تابع لـ: ${esc(r.parent_reseller_name || ('#' + r.parent_reseller_id))}</div>` : ''}
        ${r.notes ? `<div style="font-size:11px;color:var(--txd)">${esc(r.notes)}</div>` : ''}
      </td>
      <td><span class="code-text" dir="ltr" style="color:var(--inf)" onclick="copyCleanCode('${esc(r.username)}')">${esc(r.username)}</span></td>
      <td><span class="code-text" dir="ltr" onclick="copyCleanCode('${esc(r.password_plain || '')}')">${esc(r.password_plain || '••••••')}</span></td>
      <td class="hide-mobile" dir="ltr">${esc(r.phone || '—')}</td>
      <td><span class="pts-badge">💎 ${r.points} نقطة</span></td>
      <td class="hide-mobile">
        <span style="color:var(--ok);font-weight:800">+${r.total_points_received || 0}</span> /
        <span style="color:var(--gold);font-weight:800">-${r.total_points_spent || 0}</span>
      </td>
      <td>
        <b>${r.total_cards_generated || 0}</b> قسيمة
        <div style="font-size:11px;color:var(--txd)">متاح: ${r.unused_cards_count || 0} | مفعل: ${r.used_cards_count || 0}</div>
      </td>
      <td><span class="badge ${r.is_active == 1 ? 'ok' : 'dn'}">${r.is_active == 1 ? 'نشط' : 'موقوف'}</span></td>
      <td style="white-space:nowrap">
        <button class="btn primary" style="padding:7px 12px;font-size:12px" onclick="openPointsModal(${r.id})" title="إرسال أو خصم نقاط">💎 نقاط</button>
        <button class="btn outline icon-only" onclick="openResellerDetailsModal(${r.id})" title="سجل الموزع والقسائم">📊</button>
        <button class="btn outline icon-only" onclick="copyResellerCredentials(${r.id})" title="نسخ بيانات الدخول لإرسالها للموزع">📋</button>
        <button class="btn outline icon-only" onclick="openEditResellerModal(${r.id})" title="تعديل">✏️</button>
        <button class="btn danger icon-only" onclick="delReseller(${r.id})" title="حذف">🗑</button>
      </td>
    </tr>
  `).join('');
}

function copyResellerCredentials(id) {
  const r = STATE.resellers.find(x => x.id == id);
  if (!r) return;
  const portalUrl = new URL('reseller_portal.php', location.href).toString();
  const msg = `🏪 حساب موزع LIVE PRO
👤 الموزع: ${r.display_name}
🔗 رابط لوحة الموزع: ${portalUrl}
🔑 اسم المستخدم: ${r.username}
🔒 كلمة المرور: ${r.password_plain || '(محفوظة لديكم)'}
💎 رصيد النقاط الحالي: ${r.points} نقطة`;
  navigator.clipboard.writeText(msg).then(() => toast('📋 تم نسخ بيانات دخول الموزع بالكامل لإرسالها له!', 'success'));
}

function openPointsModal(id) {
  const r = STATE.resellers.find(x => x.id == id);
  if (!r) return;
  $('ptsResId').value = r.id;
  $('ptsResName').textContent = `${r.display_name} (@${r.username})`;
  $('ptsResCurrent').textContent = r.points;
  $('ptsOperation').value = 'add';
  $('ptsAmount').value = 10;
  $('ptsNotes').value = '';
  openModal('resellerPointsModal');
}

async function submitResellerPoints() {
  const reseller_id = $('ptsResId').value;
  const operation = $('ptsOperation').value;
  const points = $('ptsAmount').value;
  const notes = $('ptsNotes').value.trim();
  if (!points || parseInt(points) <= 0) return toast('يرجى إدخال عدد نقاط صحيح', 'error');
  try {
    const res = await api('admin_add_reseller_points', { reseller_id, operation, points, notes });
    toast(res.message || '✅ تم تحديث النقاط بنجاح', 'success');
    closeModal('resellerPointsModal');
    refreshAll();
  } catch (e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function openEditResellerModal(id) {
  const r = STATE.resellers.find(x => x.id == id);
  if (!r) return;
  $('editResId').value = r.id;
  $('editResName').value = r.display_name || '';
  $('editResPhone').value = r.phone || '';
  $('editResUser').value = r.username || '';
  $('editResPass').value = '';
  $('editResActive').value = r.is_active;
  $('editResNotes').value = r.notes || '';
  openModal('editResellerModal');
}

async function saveResellerEdit() {
  try {
    const res = await api('admin_update_reseller', {
      id: $('editResId').value,
      display_name: $('editResName').value.trim(),
      phone: $('editResPhone').value.trim(),
      username: $('editResUser').value.trim(),
      password: $('editResPass').value.trim(),
      is_active: $('editResActive').value,
      notes: $('editResNotes').value.trim()
    });
    toast(res.message || '✅ تم التحديث', 'success');
    closeModal('editResellerModal');
    refreshAll();
  } catch (e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function delReseller(id) {
  const r = STATE.resellers.find(x => x.id == id);
  if (!r) return;
  if (!confirm(`⚠️ هل أنت متأكد من حذف حساب الموزع "${r.display_name}"؟
(ستبقى القسائم التي استخرجها سابقاً صالحة ولن تتأثر)`)) return;
  try {
    const res = await api('admin_delete_reseller', { id });
    toast(res.message || '🗑 تم حذف الموزع', 'success');
    refreshAll();
  } catch (e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function openResellerDetailsModal(id) {
  try {
    const d = await api('admin_reseller_details', { id });
    const r = d.reseller;
    $('resDetailsTitle').textContent = `📊 سجل الموزع: ${r.display_name} (@${r.username})`;
    $('resDetailsSummary').innerHTML = `
      <div class="stat-card" style="padding:12px"><div class="stat-val" style="color:var(--gold);font-size:20px">${r.points}</div><div class="stat-lbl">الرصيد الحالي</div></div>
      <div class="stat-card ok" style="padding:12px"><div class="stat-val" style="font-size:20px">+${r.total_points_received}</div><div class="stat-lbl">إجمالي النقاط المشحونة</div></div>
      <div class="stat-card dn" style="padding:12px"><div class="stat-val" style="font-size:20px">-${r.total_points_spent}</div><div class="stat-lbl">النقاط المستهلكة</div></div>
      <div class="stat-card inf" style="padding:12px"><div class="stat-val" style="font-size:20px">${r.total_cards_generated}</div><div class="stat-lbl">القسائم المستخرجة</div></div>
    `;
    const pkgLabels = { '1month': 'شهر (30 يوم)', '3months': '3 أشهر (90 يوم)', '6months': '6 أشهر (180 يوم)', '1year': 'سنة (365 يوم)' };
    $('resDetailsCardsBody').innerHTML = (d.cards && d.cards.length) ? d.cards.map(c => `
      <tr>
        <td><span class="code-text" dir="ltr" onclick="copyCleanCode('${esc(c.code)}')">${esc(c.code)}</span></td>
        <td><span class="badge info">${esc(pkgLabels[c.card_type] || c.card_type)}</span></td>
        <td><span class="badge warn">${c.points_cost || 1} نقطة</span></td>
        <td><span class="badge ${c.status === 'unused' ? 'ok' : 'dn'}">${c.status === 'unused' ? 'متاح' : 'مفعل'}</span></td>
        <td dir="ltr">${esc(c.used_by_device || c.redemption_device || '—')}</td>
        <td style="font-size:12px">${esc(c.created_at || '')}</td>
        <td><button class="btn outline icon-only" onclick="copyCleanCode('${esc(c.code)}')">📋</button></td>
      </tr>
    `).join('') : '<tr><td colspan="7" style="text-align:center;padding:16px;color:var(--txd)">لم يستخرج أي قسائم بعد</td></tr>';

    const txLabels = { credit_add: '➕ شحن نقاط', credit_deduct: '➖ خصم إداري', generate_card: '🎫 استخراج قسيمة', direct_activate: '🖥️ تفعيل مباشر لشاشة' };
    $('resDetailsTxBody').innerHTML = (d.transactions && d.transactions.length) ? d.transactions.map(t => `
      <tr>
        <td><span class="badge ${t.points_delta > 0 ? 'ok' : 'warn'}">${esc(txLabels[t.type] || t.type)}</span></td>
        <td dir="ltr" style="font-weight:900;color:${t.points_delta > 0 ? 'var(--ok)' : 'var(--dn)'}">${t.points_delta > 0 ? '+' + t.points_delta : t.points_delta}</td>
        <td><b>${t.balance_after}</b> نقطة</td>
        <td style="font-size:12.5px">${esc(t.notes || '')} ${t.card_code ? `<span class="code-text" dir="ltr" onclick="copyCleanCode('${esc(t.card_code)}')">${esc(t.card_code)}</span>` : ''}</td>
        <td style="font-size:12px">${esc(t.created_at || '')}</td>
      </tr>
    `).join('') : '<tr><td colspan="5" style="text-align:center;padding:16px;color:var(--txd)">لا توجد عمليات مسجلة</td></tr>';

    openModal('resellerDetailsModal');
  } catch (e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function generateCards() {
  try {
    const d = await api('admin_generate_cards', { card_type: $('genCardType').value, count: $('genCardCount').value, custom_days: $('genCustomDays').value, batch_name: $('genBatchName').value, expires_at: $('genCardExpires').value });
    closeModal('genCardsModal'); $('resultInfo').textContent = `${d.count} بطاقة (${d.card_label})`; $('resultCodes').value = d.cards.join('\n'); openModal('resultModal'); loadCards();
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function copyCodes() { navigator.clipboard.writeText($('resultCodes').value); toast('📋 تم النسخ', 'success'); }

async function delCard(id) {
  if(!confirm('حذف البطاقة؟')) return;
  try { await api('admin_delete_card', { id }); toast('🗑 تم الحذف', 'success'); loadCards(); } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function openBatchesModal() {
  try {
    const d = await api('admin_list_batches');
    $('batchesBody').innerHTML = d.batches.map(b => `<tr><td style="font-size:12px">${esc(b.batch_id)}</td><td>${b.type_label}</td><td>${b.total}</td><td><span style="color:var(--ok)">${b.unused}</span></td><td><span style="color:var(--inf)">${b.used}</span></td><td><button class="btn danger icon-only" onclick="delBatch('${esc(b.batch_id)}')">🗑</button></td></tr>`).join('');
    openModal('batchesModal');
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function delBatch(id) {
  if(!confirm('حذف كل البطاقات غير المستخدمة من هذه الدفعة؟')) return;
  try { await api('admin_delete_batch', { batch_id: id }); toast('🗑 تم الحذف', 'success'); closeModal('batchesModal'); loadCards(); } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

async function changePassword() {
  if ($('newPass').value !== $('newPass2').value) return toast('كلمتا المرور غير متطابقتين', 'error');
  if ($('newPass').value.length < 6) return toast('كلمة المرور 6 أحرف على الأقل', 'error');
  try {
    await api('admin_change_password', { old_password: $('oldPass').value, new_password: $('newPass').value });
    toast('✅ تم التغيير. جارٍ الخروج...', 'success');
    setTimeout(() => { localStorage.removeItem('admin_token'); sessionStorage.removeItem('admin_token'); window.location.href = 'login.php'; }, 1500);
  } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); }
}

function doLogout() {
  if (!confirm('تسجيل الخروج؟')) return;
  if (TOKEN) api('admin_logout').catch(() => {});
  localStorage.removeItem('admin_token'); sessionStorage.removeItem('admin_token');
  setTimeout(() => window.location.href = 'login.php', 150);
}

document.addEventListener('DOMContentLoaded', () => {
  const tbody = $('channelsBody'); if (!tbody) return;
  tbody.addEventListener('click', (e) => {
    const inlineEdit = e.target.closest('.inline-edit');
    if (inlineEdit) {
      e.stopPropagation();
      const id = parseInt(inlineEdit.dataset.id);
      const c = STATE.channels.find(x => x.id == id); if (!c) return;
      const current = c.number;
      const input = document.createElement('input'); input.type = 'number'; input.value = current; input.className = 'inline-input';
      const original = inlineEdit.innerHTML; inlineEdit.innerHTML = ''; inlineEdit.appendChild(input); input.focus(); input.select();
      let saved = false;
      const doSave = async () => {
        if (saved) return; saved = true;
        const newVal = parseInt(input.value);
        if (!newVal || newVal === current) { inlineEdit.innerHTML = original; return; }
        try {
          await api('admin_update_channel', { id: c.id, name: c.name, number: newVal, category: c.category, url: c.original_url || '', is_active: c.is_active });
          c.number = newVal; toast('✅ تم تحديث الرقم'); renderChannelsTable();
        } catch(e) { if (e.message !== 'انتهت الجلسة') toast(e.message, 'error'); inlineEdit.innerHTML = original; }
      };
      input.onblur = doSave;
      input.onkeydown = (ev) => { if (ev.key === 'Enter') { ev.preventDefault(); input.blur(); } if (ev.key === 'Escape') { saved = true; inlineEdit.innerHTML = original; } };
      return;
    }
    if (e.target.closest('button, input, a, .toggle-switch, .code-text')) return;
    const row = e.target.closest('tr[data-ch-id]'); if (!row) return;
    toggleChannelSelect(parseInt(row.dataset.chId), !selectedChannels.has(parseInt(row.dataset.chId)));
  });

  document.querySelectorAll('.modal-overlay').forEach(m => {
    m.addEventListener('click', (e) => { if (e.target === m) m.classList.remove('active'); });
  });
});

document.addEventListener('keydown', (e) => { if (e.key === 'Escape') document.querySelectorAll('.modal-overlay.active').forEach(m => m.classList.remove('active')); });

function renderPagination(containerId, totalItems, currentPage, perPage, callbackName) {
  const container = $(containerId); const totalPages = Math.ceil(totalItems / perPage) || 1;
  if (totalPages <= 1) { container.innerHTML = ''; return; }
  let html = `<button class="page-btn text-btn" ${currentPage === 1 ? 'disabled' : ''} onclick="${callbackName}(${currentPage - 1})">السابق</button>`;
  let startPage = Math.max(1, currentPage - 2); let endPage = Math.min(totalPages, startPage + 4);
  if (endPage - startPage < 4) startPage = Math.max(1, endPage - 4);
  if (startPage > 1) html += `<button class="page-btn" onclick="${callbackName}(1)">1</button>`;
  if (startPage > 2) html += `<span style="color:var(--txd)">...</span>`;
  for (let i = startPage; i <= endPage; i++) html += `<button class="page-btn ${i === currentPage ? 'active' : ''}" onclick="${callbackName}(${i})">${i}</button>`;
  if (endPage < totalPages - 1) html += `<span style="color:var(--txd)">...</span>`;
  if (endPage < totalPages) html += `<button class="page-btn" onclick="${callbackName}(${totalPages})">${totalPages}</button>`;
  html += `<button class="page-btn text-btn" ${currentPage === totalPages ? 'disabled' : ''} onclick="${callbackName}(${currentPage + 1})">التالي</button>`;
  container.innerHTML = html;
}

(function init() {
  if (!TOKEN) return;
  refreshAll();
  switchTab(localStorage.getItem('admin_tab') || 'channels');
})();

setInterval(() => { if (TOKEN && !document.hidden) refreshAll().catch(() => {}); }, 60000);
</script>
</body>
</html>
