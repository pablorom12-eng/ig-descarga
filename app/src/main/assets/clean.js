(function () {
  if (window.__igClean) return;
  window.__igClean = 1;
  var reBanner = /(abrir|open|usa|use|get|descarga|download).{0,25}(app|aplicaci)|reg[ií]strate|sign up/i;
  function clean() {
    var els = document.querySelectorAll('div,section,aside,footer,header,nav');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.style.display === 'none') continue;
      var cs = getComputedStyle(e);
      var fixed = cs.position === 'fixed' || cs.position === 'sticky';
      var dialog = e.getAttribute('role') === 'dialog';
      if (!fixed && !dialog) continue;
      if (!dialog && e.offsetHeight > window.innerHeight * 0.45) continue;
      var t = e.innerText || '';
      if (t.length > 0 && t.length < 400 && reBanner.test(t)) {
        e.style.display = 'none';
        if (dialog) {
          document.body.style.overflow = 'auto';
          document.documentElement.style.overflow = 'auto';
        }
      }
    }
  }
  var reCont = /^(continuar en el sitio web|continue on web|continue to web|continuar en la web)/i;
  var clicked = false;
  function extras() {
    if (!clicked) {
      var cs = document.querySelectorAll('a,button,div[role="button"],span');
      for (var i = 0; i < cs.length; i++) {
        var t = (cs[i].innerText || '').trim();
        if (t.length < 40 && reCont.test(t)) { clicked = true; cs[i].click(); break; }
      }
    }
    var vs = document.querySelectorAll('video');
    for (var j = 0; j < vs.length; j++) {
      if (vs[j].muted) { vs[j].muted = false; vs[j].volume = 0.05; }
    }
  }
  setInterval(function () { clean(); extras(); }, 1500);
  clean();
  extras();
})();
