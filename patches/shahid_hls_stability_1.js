// ============================================================
// LIVE-PRO v24.3 — Shahid HLS Stability Fix 1
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 Shahid HLS Stability Fix 1 مُفعّل');

  function applyShahidStability() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.shahidFix1Applied) {
        video.dataset.shahidFix1Applied = 'true';
        
        // 1. إعدادات تقليل التقطيع لشاهد
        video.setAttribute('preload', 'auto');
        video.setAttribute('playsinline', '');
        
        // 2. منع توقف البث بسبب انقطاع مؤقت في الشبكة
        video.addEventListener('stalled', () => {
          console.log('[LivePro] ⚠️ (شاهد 1) البث متوقف مؤقتاً، جاري الاستئناف...');
          setTimeout(() => {
            video.play().catch(() => {});
          }, 1000);
        });

        video.addEventListener('waiting', () => {
          const timeout = setTimeout(() => {
            if (video.readyState < 3) {
              console.log('[LivePro] 🔄 (شاهد 1) إعادة تحميل لتفادي التقطيع...');
              video.load();
              video.play().catch(() => {});
            }
          }, 5000);
          
          video.addEventListener('playing', () => clearTimeout(timeout), { once: true });
        });

        // 3. تصحيح أخطاء الشبكة
        video.addEventListener('error', () => {
          if (video.error && video.error.code === 2) { // Network Error
            console.log('[LivePro] 🛠️ (شاهد 1) خطأ في الشبكة، محاولة الاتصال مجدداً...');
            setTimeout(() => {
              video.load();
              video.play().catch(() => {});
            }, 2000);
          }
        });
      }
    });
  }

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
