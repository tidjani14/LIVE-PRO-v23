// ============================================================
// LIVE-PRO v24.5 — Native HLS Fast-Start
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 Native HLS Fast-Start مُفعّل');

  // مراقبة الفيديو وتحسين سرعة البدء
  function applyFastStart() {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.fastStartApplied) {
        video.dataset.fastStartApplied = 'true';
        
        // إعدادات تسريع بدء التشغيل
        video.setAttribute('playsinline', '');
        video.setAttribute('preload', 'auto');
        
        // تقليل وقت الانتظار الأولي للبث
        if (video.buffered.length > 0) {
          video.currentTime = video.buffered.start(0); 
        }

        // محاولة التشغيل التلقائي بمجرد توفر بيانات كافية
        video.addEventListener('loadedmetadata', () => {
          video.play().catch(() => {
             console.log('[LivePro] ⚠️ التشغيل التلقائي محظور، في انتظار تفاعل المستخدم');
          });
        });

        // إذا كان البث يعاني من تأخير، قم بتخطي الإطارات المعلقة
        video.addEventListener('waiting', () => {
          setTimeout(() => {
            if (video.readyState < 3) {
              video.load(); // إعادة تحميل سريعة لتفادي التقطيع
            }
          }, 2000);
        });
      }
    });
  }

  // مراقبة إضافة فيديوهات جديدة
  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      if (mutation.addedNodes.length) {
        applyFastStart();
      }
    });
  });

  window.addEventListener('load', () => {
    applyFastStart();
    observer.observe(document.body, { childList: true, subtree: true });
  });

})();
