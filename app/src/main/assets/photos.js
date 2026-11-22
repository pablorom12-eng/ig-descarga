(function () {
  var out = [];
  var imgs = document.querySelectorAll('img');
  for (var i = 0; i < imgs.length; i++) {
    var im = imgs[i];
    var s = im.currentSrc || im.src;
    if (!s) continue;
    if (s.indexOf('cdninstagram') < 0 && s.indexOf('fbcdn') < 0) continue;
    if (im.naturalWidth < 400) continue;
    if (out.indexOf(s) < 0) out.push(s);
  }
  if (out.length === 0) {
    var m = document.querySelector('meta[property="og:image"]');
    if (m && m.content) out.push(m.content);
  }
  return JSON.stringify(out);
})()
