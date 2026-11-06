// ============================================================
// Live Pro V24.3.2 — Zap Teardown Lightning (1)
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] ⚡ Zap Teardown Lightning (v1) مُفعّل');

  function applyZapTeardown() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.zapTeardownV1Applied) {
        video.dataset.zapTeardownV1Applied = 'true';
        
        // 1. التنظيف الصاعق (Zap Teardown) عند تبديل القنوات
        const zapTeardown = () => {
          console.log('[LivePro] ⚡ (ZAP) تنظيف الموارد وإغلاق الاتصالات العالقة...');
          video.pause();
          video.removeAttribute('src');
          video.load();
        };

        const observerSrc = new MutationObserver((mutations) => {
          mutations.forEach((mutation) => {
            if (mutation.attributeName === 'src') {
              zapTeardown();
            }
          });
        });
        observerSrc.observe(video, { attributes: true });

        // 2. استجابة البرق (Lightning) للأخطاء
        video.addEventListener('error', () => {
          console.log('[LivePro] ⚡ (Lightning) استجابة سريعة لخطأ الفيديو...');
          zapTeardown();
          setTimeout(() => {
             video.load();
             video.play().catch(() => {});
          }, 500);
        });

        video.addEventListener('stalled', () => {
          console.log('[LivePro] ⚡ (Lightning) استجابة فورية لتباطؤ الشبكة...');
          setTimeout(() => {
            if (video.readyState < 3) {
              zapTeardown();
              video.play().catch(() => {});
            }
          }, 3000);
        });
      }
    });
  }

  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        applyZapTeardown();
      }
    });
  });

  window.addEventListener('load', () => {
    applyZapTeardown();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
