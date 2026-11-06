// ============================================================
// LIVE-PRO v24.3 — HLS Empty Edge & Lightning Speed
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] ⚡ HLS Empty Edge Lightning Fix مُفعّل');

  function applyLightningFix() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.lightningFixApplied) {
        video.dataset.lightningFixApplied = 'true';
        
        // 1. تقليل التأخير الأولي (Fast Start)
        video.setAttribute('preload', 'auto');
        
        // 2. تخطي المقاطع الفارغة (Empty Edges) التي تسبب الشاشة السوداء
        video.addEventListener('loadedmetadata', () => {
          // إذا كانت بداية المقطع فارغة، اقفز فوراً إلى البيانات الفعلية
          if (video.buffered.length > 0) {
            const startEdge = video.buffered.start(0);
            const endEdge = video.buffered.end(video.buffered.length - 1);
            
            if (video.currentTime < startEdge || video.currentTime >= endEdge) {
              console.log('[LivePro] ⏭️ (Lightning) تخطي المقطع الفارغ...');
              video.currentTime = endEdge - 0.5; // الانتقال إلى اللحظة الحالية مباشرة
            }
          }
        });

        // 3. معالجة فترات الانتظار الشبكية بسرعة البرق
        video.addEventListener('waiting', () => {
          // إذا كان المشغل ينتظر الشبكة، قم بإجباره على القراءة من المخزن المؤقت
          if (video.buffered.length > 0) {
             const end = video.buffered.end(video.buffered.length - 1);
             // إذا كانت البيانات متوفرة ولم يتم استخدامها، اقفز إليها
             if (end > video.currentTime + 1) {
                 video.currentTime = end - 0.5;
             }
          }
        });

        // 4. التعامل مع أخطاء الفهرسة (Empty Segments)
        video.addEventListener('error', (e) => {
          if (video.error) {
             // خطأ في فك التشفير أو المقطع
             if (video.error.code === 3 || video.error.code === 4) {
                 console.log('[LivePro] 🔄 (Lightning) إعادة مزامنة البث...');
                 video.load();
                 video.play().catch(() => {});
             }
          }
        });
      }
    });
  }

  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        applyLightningFix();
      }
    });
  });

  window.addEventListener('load', () => {
    applyLightningFix();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
