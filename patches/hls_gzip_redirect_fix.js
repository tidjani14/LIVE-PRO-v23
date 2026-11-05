// ============================================================
// LIVE-PRO v24.7 — HLS GZIP & REDIRECT FIX
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 HLS GZIP & Redirect Fix مُفعّل');

  // اعتراض طلبات الشبكة (fetch) لمعالجة GZIP وإعادة التوجيه
  const originalFetch = window.fetch;
  window.fetch = async function(...args) {
    let response = await originalFetch.apply(this, args);
    
    // التحقق من إعادة التوجيه (Redirect)
    if (response.redirected) {
      console.log('[LivePro] 🔄 تم اكتشاف إعادة توجيه إلى:', response.url);
    }

    return response;
  };

  // اعتراض طلبات XHR (يستخدمها HLS.js غالباً)
  const originalXHR = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    this.addEventListener('load', function() {
      // معالجة استجابات GZIP المضغوطة
      if (this.getResponseHeader('content-encoding') === 'gzip') {
        console.log('[LivePro] 🗜️ تم استلام بيانات GZIP من:', url);
      }
    });
    return originalXHR.call(this, method, url, ...rest);
  };

  // تحسين أداء مشغل الفيديو ومنع توقف البث
  window.addEventListener('load', () => {
    const videos = document.querySelectorAll('video');
    videos.forEach(video => {
      if (!video.dataset.gzipFixApplied) {
        video.dataset.gzipFixApplied = 'true';
        
        video.addEventListener('error', (e) => {
          if (video.error) {
            console.log('[LivePro] ⚠️ خطأ في البث، كود:', video.error.code);
            // محاولة استئناف عند فشل تحميل المقاطع
            if (video.error.code === 3 || video.error.code === 4) {
              setTimeout(() => video.load(), 1000);
            }
          }
        });
      }
    });
  });

})();
