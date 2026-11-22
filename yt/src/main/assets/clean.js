(function () {
  if (window.__ytClean) return;
  window.__ytClean = 1;
  var re = /^(rechazar todo|reject all|rechazar cookies opcionales|decline optional cookies)/i;
  var done = false;
  try {
    var st = document.createElement('style');
    st.textContent = 'ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer,ytm-upsell-dialog-renderer,'
      + 'ytm-mealbar-promo-renderer{display:none!important}body{overflow:hidden!important}';
    document.documentElement.appendChild(st);
  } catch (e) {}
  function tick() {
    if (!done) {
      var bs = document.querySelectorAll('button,div[role="button"],a');
      for (var i = 0; i < bs.length; i++) {
        var t = (bs[i].innerText || bs[i].getAttribute('aria-label') || '').trim();
        if (t.length < 40 && re.test(t)) { done = true; bs[i].click(); break; }
      }
    }
    var sk = document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');
    if (sk) { try { sk.click(); } catch (e) {} }
    var op = document.querySelector('.ytp-ad-overlay-close-button');
    if (op) { try { op.click(); } catch (e) {} }
    window.scrollTo(0, 0);
  }
  setInterval(tick, 1000);
  tick();
})();
