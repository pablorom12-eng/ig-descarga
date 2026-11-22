(function () {
  var p = document.getElementById('movie_player');
  if (!p || !p.getAvailableQualityLevels) return JSON.stringify({ ready: false });
  var ad = !!document.querySelector('.ad-showing');
  var lv = [];
  try { lv = p.getAvailableQualityLevels(); } catch (e) {}
  var t = '', a = '';
  try { var d = p.getVideoData(); t = d.title || ''; a = d.author || ''; } catch (e) {}
  try { p.mute(); if (p.getPlayerState() !== 1) p.playVideo(); } catch (e) {}
  return JSON.stringify({ ready: true, ad: ad, levels: lv, title: t, author: a });
})()
