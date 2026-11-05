// ============================================================
// LIVE-PRO v24.6 — ExoPlayer Direct Start
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 ExoPlayer Direct Start مُفعّل');

  function optimizeForExoPlayer() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.exoApplied) {
        video.dataset.exoApplied = 'true';
        
        // إعدادات التوافق مع ExoPlayer (WebView/Android)
        video.setAttribute('playsinline', 'true');
        video.setAttribute('webkit-playsinline', 'true');
        video.setAttribute('x5-playsinline', 'true');
        video.setAttribute('preload', 'auto');
        
        // إجبار التشغيل المباشر وتخطي الانتظار
        video.addEventListener('loadedmetadata', () => {
           if (video.paused) {
               const playPromise = video.play();
               if (playPromise !== undefined) {
                   playPromise.catch(error => {
                       console.log('[LivePro] ⚠️ التشغيل التلقائي ممنوع من المتصفح:', error);
                   });
               }
           }
        });

        // معالجة مشاكل ExoPlayer الشائعة (التوقف المفاجئ)
        video.addEventListener('stalled', () => {
           console.log('[LivePro] ⏳ البث متوقف، محاولة استئناف...');
           video.play().catch(() => {});
        });
      }
    });
  }

  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        optimizeForExoPlayer();
      }
    });
  });

  window.addEventListener('load', () => {
    optimizeForExoPlayer();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
