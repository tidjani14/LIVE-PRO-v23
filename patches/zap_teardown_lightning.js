// ============================================================
// LIVE-PRO v24.3.2 — Zap Teardown Lightning
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] ⚡ ZAP Teardown Lightning Fix مُفعّل');

  function applyZapTeardown() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.zapTeardownApplied) {
        video.dataset.zapTeardownApplied = 'true';
        
        // 1. التنظيف الصاعق (Zap Teardown) عند تبديل القنوات أو إغلاق المشغل
        const zapTeardown = () => {
          console.log('[LivePro] ⚡ (ZAP) تنظيف الموارد وإغلاق الاتصالات العالقة...');
          video.pause();
          video.removeAttribute('src'); // قطع الاتصال بالسيرفر فوراً
          video.load(); // إعادة تهيئة المشغل من الصفر
        };

        // مراقبة إغلاق أو تغيير مصدر الفيديو (إذا تم تغيير الـ src، نفكك القديم)
        const observerSrc = new MutationObserver((mutations) => {
          mutations.forEach((mutation) => {
            if (mutation.attributeName === 'src') {
              zapTeardown();
            }
          });
        });
        observerSrc.observe(video, { attributes: true });

        // 2. استجابة البرق (Lightning) للأخطاء والتقطيع
        video.addEventListener('error', () => {
          console.log('[LivePro] ⚡ (Lightning) استجابة سريعة لخطأ الفيديو...');
          zapTeardown();
          // إعادة المحاولة فوراً بعد التنظيف
          setTimeout(() => {
             video.load();
             video.play().catch(() => {});
          }, 500);
        });

        video.addEventListener('stalled', () => {
          console.log('[LivePro] ⚡ (Lightning) استجابة فورية لتباطؤ الشبكة...');
          // إذا توقف البث لأكثر من 3 ثوانٍ، قم بعمل Teardown سريع
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
