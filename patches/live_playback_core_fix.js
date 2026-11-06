// ============================================================
// LIVE-PRO v25.0 — Live Playback Core Fix
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 Live Playback Core Fix v25.0 مُفعّل');

  function corePlaybackFix() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.corePlaybackFixed) {
        video.dataset.corePlaybackFixed = 'true';
        
        // 1. إعدادات أساسية لتشغيل البث المباشر
        video.setAttribute('playsinline', 'true');
        video.setAttribute('preload', 'auto');
        video.muted = false; // ضمان عدم كتم الصوت
        
        // 2. معالجة مشكلة الشاشة السوداء (Black Screen)
        video.addEventListener('loadeddata', () => {
           if (video.videoWidth === 0 || video.videoHeight === 0) {
               console.log('[LivePro] ⚠️ (Core) شاشة سوداء، محاولة إعادة التحميل...');
               setTimeout(() => video.load(), 500);
           }
        });

        // 3. معالجة تجمد الصورة (Freezing)
        let lastTime = 0;
        let freezeCount = 0;
        const watchdog = setInterval(() => {
           if (!video.paused && !video.ended) {
               if (video.currentTime === lastTime) {
                   freezeCount++;
                   if (freezeCount >= 3) { // تجمد لأكثر من 3 ثوانٍ
                       console.log('[LivePro] ❄️ (Core) تجمد في الصورة، إعادة التشغيل...');
                       video.load();
                       video.play().catch(() => {});
                       freezeCount = 0;
                   }
               } else {
                   freezeCount = 0;
                   lastTime = video.currentTime;
               }
           }
        }, 1000);

        // 4. تنظيف الـ Watchdog عند إغلاق الفيديو
        video.addEventListener('emptied', () => clearInterval(watchdog));
      }
    });
  }

  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        corePlaybackFix();
      }
    });
  });

  window.addEventListener('load', () => {
    corePlaybackFix();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
