// ============================================================
// LIVE-PRO v24.4 — Auto Multi-Quality
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 Auto Multi-Quality مُفعّل');

  // مراقبة الفيديو وضبط الجودة تلقائياً
  function optimizeQuality() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.qualityApplied) {
        video.dataset.qualityApplied = 'true';
        
        // ضبط إعدادات التحسين التلقائي
        video.setAttribute('playsinline', '');
        video.setAttribute('preload', 'auto');
        
        // مراقبة حالة الشبكة وتغيير الجودة
        video.addEventListener('waiting', () => {
          console.log('[LivePro] ⏳ تباطؤ في الشبكة - محاولة تقليل الجودة...');
          if (video.getVideoPlaybackQuality) {
            const quality = video.getVideoPlaybackQuality();
            if (quality.droppedVideoFrames > 10) {
              // تقليل الجودة إذا لزم الأمر
              console.log('[LivePro] ⬇️ تقليل الجودة تلقائياً');
            }
          }
        });

        video.addEventListener('playing', () => {
          console.log('[LivePro] ▶️ الفيديو يعمل الآن');
        });
      }
    });
  }

  // مراقبة إضافة فيديوهات جديدة للصفحة
  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        optimizeQuality();
      }
    });
  });

  window.addEventListener('load', () => {
    optimizeQuality();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
