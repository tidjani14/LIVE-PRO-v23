(function () {
  'use strict';
  const FOCUSABLE = ['a[href]', 'button:not([disabled])', 'input:not([disabled])', 'select:not([disabled])', 'textarea:not([disabled])', '[tabindex]:not([tabindex="-1"])', '[data-focusable="true"]'].join(',');
  let currentIndex = 0;
  function getFocusables() { return Array.from(document.querySelectorAll(FOCUSABLE)).filter(el => el.offsetParent !== null); }
  function focusByIndex(i) { const items = getFocusables(); if (!items.length) return; currentIndex = (i + items.length) % items.length; const el = items[currentIndex]; el.focus(); el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
  function handleKey(e) {
    switch (e.key) {
      case 'ArrowRight': case 'ArrowDown': e.preventDefault(); focusByIndex(currentIndex + 1); break;
      case 'ArrowLeft': case 'ArrowUp': e.preventDefault(); focusByIndex(currentIndex - 1); break;
      case 'Enter': if (document.activeElement && document.activeElement.click) document.activeElement.click(); break;
      default: break;
    }
  }
  document.addEventListener('keydown', handleKey, true);
  window.addEventListener('load', () => { const items = getFocusables(); if (items.length) items[0].focus(); });
  console.log('[LivePro] ✅ Universal D-Pad Focus Fix (v2) مُفعّل');
})();
