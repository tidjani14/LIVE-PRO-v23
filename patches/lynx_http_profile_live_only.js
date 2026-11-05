// ============================================================
// LIVE-PRO v24.9 — Lynx HTTP Profile Live Only
// ============================================================
(function () {
  'use strict';

  console.log('[LivePro] 🚀 Lynx HTTP Profile Live Only مُفعّل');

  // اعتراض طلبات جلب قوائم القنوات/البثوث
  const originalFetch = window.fetch;
  window.fetch = async function(...args) {
    const response = await originalFetch.apply(this, args);
    
    // التحقق مما إذا كان الطلب يخص قوائم البث
    const url = args[0] instanceof Request ? args[0].url : args[0];
    if (url && (url.includes('playlist') || url.includes('channels') || url.includes('m3u'))) {
      
      // استنساخ الاستجابة لمعالجتها دون إتلاف الأصل
      const clone = response.clone();
      try {
        const text = await clone.text();
        
        // معالجة قوائم M3U لضمان احتواء البث المباشر فقط
        if (text.includes('#EXTM3U') || text.includes('#EXTINF')) {
          console.log('[LivePro] 📡 تم اكتشاف قائمة تشغيل، جاري تصفية البث المباشر...');
          
          // تصفية القنوات الميتة (يمكن تخصيصها لاحقاً حسب البروتوكول)
          const lines = text.split('\n');
          const liveLines = lines.filter(line => !line.includes('VOD') && !line.includes('SERIES'));
          
          if (liveLines.length !== lines.length) {
            console.log('[LivePro] ✅ تم حذف القنوات غير المباشرة.');
            const modifiedBlob = new Blob([liveLines.join('\n')], { type: 'text/plain' });
            return new Response(modifiedBlob, {
              status: response.status,
              statusText: response.statusText,
              headers: response.headers
            });
          }
        }
      } catch (e) {
        console.log('[LivePro] ⚠️ تعذر معالجة الاستجابة:', e);
      }
    }
    return response;
  };

  // معالجة بروتوكول Lynx HTTP (إن وجد)
  if (window.location.protocol === 'http:' || window.location.protocol === 'https:') {
    console.log('[LivePro] 🌐 البيئة تعمل عبر HTTP/HTTPS - متوافقة مع Lynx.');
  }

})();
