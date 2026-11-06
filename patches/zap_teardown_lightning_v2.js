// ============================================================
// Live Pro V24.3.2 — Zap Teardown Lightning (2)
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] ⚡ Zap Teardown Lightning (v2) مُفعّل');

  function applyZapTeardownV2() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.zapTeardownV2Applied) {
        video.dataset.zapTeardownV2Applied = 'true';
        
        // 1. التنظيف الصاعق المحسن (Zap Teardown V2) عند تبديل القنوات
        const zapTeardown = () => {
          console.log('[LivePro] ⚡ (ZAP V2) تنظيف الموارد وإغلاق الاتصالات العالقة...');
          video.pause();
          video.removeAttribute('src');
          video.load();
          // تنظيف إضافي للذاكرة (إن أمكن)
          if (video.srcObject) {
            video.srcObject = null;
          }
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
        video.addEventListener('error', (e) => {
          console.log('[LivePro] ⚡ (Lightning V2) استجابة سريعة لخطأ الفيديو...');
          zapTeardown();
          setTimeout(() => {
             video.load();
             video.play().catch(() => {});
          }, 500);
        });

        // 3. استجابة البرق لتباطؤ الشبكة (Stalled)
        video.addEventListener('stalled', () => {
          console.log('[LivePro] ⚡ (Lightning V2) استجابة فورية لتباطؤ الشبكة...');
          setTimeout(() => {
            if (video.readyState < 3) {
              zapTeardown();
              video.play().catch(() => {});
            }
          }, 2500); // تحسين إضافي: تقليل وقت الانتظار إلى 2.5 ثانية
        });

        // 4. التعامل مع نهاية البث المفاجئة
        video.addEventListener('ended', () => {
            console.log('[LivePro] 🛑 البث انتهى، إعادة الاتصال...');
            zapTeardown();
            video.play().catch(() => {});
        });
      }
    });
  }

  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        applyZapTeardownV2();
      }
    });
  });

  window.addEventListener('load', () => {
    applyZapTeardownV2();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
