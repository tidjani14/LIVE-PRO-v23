// ============================================================
// LIVE-PRO v24.3 — Shahid HLS Stability Fix
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 Shahid HLS Stability Fix مُفعّل');

  function applyShahidStability() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.shahidFixApplied) {
        video.dataset.shahidFixApplied = 'true';
        
        // 1. إعدادات تقليل التقطيع لشاهد
        video.setAttribute('preload', 'auto');
        video.setAttribute('playsinline', '');
        
        // 2. منع توقف البث بسبب انقطاع مؤقت في الشبكة (Stalled / Waiting)
        video.addEventListener('stalled', () => {
          console.log('[LivePro] ⚠️ (شاهد) البث متوقف مؤقتاً، جاري الاستئناف...');
          setTimeout(() => {
            video.play().catch(() => {});
          }, 1000);
        });

        video.addEventListener('waiting', () => {
          // إذا استمر الانتظار لأكثر من 5 ثوانٍ، قم بإعادة تحميل المقطع
          const timeout = setTimeout(() => {
            if (video.readyState < 3) {
              console.log('[LivePro] 🔄 (شاهد) إعادة تحميل لتفادي التقطيع...');
              video.load();
              video.play().catch(() => {});
            }
          }, 5000);
          
          video.addEventListener('playing', () => clearTimeout(timeout), { once: true });
        });

        // 3. تصحيح أخطاء الشبكة (Network Errors) الخاصة بشاهد
        video.addEventListener('error', () => {
          if (video.error && video.error.code === 2) { // Network Error
            console.log('[LivePro] 🛠️ (شاهد) خطأ في الشبكة، محاولة الاتصال مجدداً...');
            setTimeout(() => {
              video.load();
              video.play().catch(() => {});
            }, 2000);
          }
        });
      }
    });
  }

  // مراقبة ظهور الفيديو في الصفحة
  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        applyShahidStability();
      }
    });
  });

  window.addEventListener('load', () => {
    applyShahidStability();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
