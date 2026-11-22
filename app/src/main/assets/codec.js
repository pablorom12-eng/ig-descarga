(function () {
  if (window.__codecPatched) return;
  window.__codecPatched = 1;
  var bad = /vp0?9|vp09|av01|av1/i;
  try {
    if (window.MediaSource && MediaSource.isTypeSupported) {
      var o = MediaSource.isTypeSupported.bind(MediaSource);
      MediaSource.isTypeSupported = function (t) { return bad.test(String(t)) ? false : o(t); };
    }
  } catch (e) {}
  try {
    var ct = HTMLMediaElement.prototype.canPlayType;
    HTMLMediaElement.prototype.canPlayType = function (t) {
      return bad.test(String(t)) ? '' : ct.call(this, t);
    };
  } catch (e) {}
  try {
    if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
      var di = navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
      navigator.mediaCapabilities.decodingInfo = function (c) {
        var s = '';
        try { s = JSON.stringify(c || {}); } catch (e) {}
        if (bad.test(s)) {
          return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
        }
        return di(c);
      };
    }
  } catch (e) {}
})();
