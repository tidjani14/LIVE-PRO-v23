#!/usr/bin/env bash
# ============================================================
# Live Pro V24.3 — Universal D-Pad Focus Fix
# ============================================================
# الاستخدام:
#   bash "Live Pro V24 3 Universal Dpad Focus Fix (1).sh" [APP_DIR]
# ============================================================

set -euo pipefail

echo "🔧 تطبيق إصلاح D-Pad Focus الشامل..."

APP_DIR="${1:-.}"

if [ ! -d "$APP_DIR" ]; then
  echo "❌ المجلد غير موجود: $APP_DIR"
  exit 1
fi

cd "$APP_DIR"

mkdir -p patches

cat > patches/universal_dpad_focus_fix.js <<'JSEOF'
// ============================================================
// Universal D-Pad Focus Fix — Live Pro V24.3
// ============================================================
(function () {
  'use strict';

  const FOCUSABLE = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
    '[data-focusable="true"]'
  ].join(',');

  let currentIndex = 0;

  function getFocusables() {
    return Array.from(document.querySelectorAll(FOCUSABLE))
      .filter(el => el.offsetParent !== null);
  }

  function focusByIndex(i) {
    const items = getFocusables();
    if (!items.length) return;
    currentIndex = (i + items.length) % items.length;
    const el = items[currentIndex];
    el.focus();
    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function handleKey(e) {
    switch (e.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        e.preventDefault();
        focusByIndex(currentIndex + 1);
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        e.preventDefault();
        focusByIndex(currentIndex - 1);
        break;
      case 'Enter':
        if (document.activeElement && document.activeElement.click) {
          document.activeElement.click();
        }
        break;
      default:
        break;
    }
  }

  document.addEventListener('keydown', handleKey, true);

  window.addEventListener('load', () => {
    const items = getFocusables();
    if (items.length) items[0].focus();
  });

  console.log('[LivePro] ✅ Universal D-Pad Focus Fix مُفعّل');
})();
JSEOF

echo "✅ تم إنشاء: patches/universal_dpad_focus_fix.js"

if [ -f index.html ]; then
  if ! grep -q "universal_dpad_focus_fix.js" index.html; then
    sed -i 's#</body>#  <script src="patches/universal_dpad_focus_fix.js"></script>\n</body>#' index.html
    echo "✅ تم ربط الإصلاح بـ index.html"
  else
    echo "ℹ️ الإصلاح مرتبط مسبقاً بـ index.html"
  fi
fi

echo "🎉 اكتمل تطبيق إصلاح D-Pad Focus."
