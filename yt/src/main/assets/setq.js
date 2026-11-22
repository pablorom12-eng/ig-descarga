(function (q) {
  var p = document.getElementById('movie_player');
  if (!p) return 'noplayer';
  try { p.setPlaybackQualityRange(q, q); } catch (e) {}
  try { p.setPlaybackQuality(q); } catch (e) {}
  try { p.mute(); p.playVideo(); } catch (e) {}
  try { var t = p.getCurrentTime(); p.seekTo(t + 20, true); } catch (e) {}
  return 'ok';
})('__Q__')
