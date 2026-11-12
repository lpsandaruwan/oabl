// Temporary, OABL-only diagnostic. Intercepts its AudioTrack minimum-size query;
// LibVLC doubles that value when constructing its real output track.
let bufferMs = 0;
let original;
const main = fn => new Promise((resolve, reject) => {
  Java.perform(() => Java.scheduleOnMainThread(() => {
    try { resolve(fn()); } catch (error) { reject(String(error.stack || error)); }
  }));
});
Java.perform(() => {
  const Track = Java.use('android.media.AudioTrack');
  const minimum = Track.getMinBufferSize.overload('int', 'int', 'int');
  minimum.implementation = function (rate, channels, encoding) {
    const actual = minimum.call(Track, rate, channels, encoding);
    let selected = actual;
    if (bufferMs && channels === 12 && (encoding === 2 || encoding === 4) && actual > 0) {
      const bytesPerFrame = 2 * (encoding === 4 ? 4 : 2);
      selected = Math.max(actual, Math.ceil(rate * bytesPerFrame * bufferMs / 2000));
    }
    send({event:'buffer-query', rate, channels, encoding, actual, selected, bufferMs});
    return selected;
  };
  send({event:'hook-ready'});
});
function service() {
  const value = Java.use('com.softiva.oabl.MusicService').instance.value;
  if (value === null) throw Error('Music service is not running');
  return value;
}
async function rebuild(ms) {
  await main(() => {
    const s = service();
    s.pause.overload('boolean').call(s, false);
    if (s.player.value !== null) {
      s.player.value.setEventListener(null);
      s.player.value.stop();
    }
  });
  // libVLC stop is asynchronous. Releasing immediately can orphan its output
  // track; wait for Stopped before constructing either comparison condition.
  for (let i = 0; i < 30; i++) {
    const stopped = await main(() => {
      const p = service().player.value;
      return p === null || [0,5,6,7].indexOf(p.getPlayerState()) !== -1;
    });
    if (stopped) break;
    if (i === 29) throw Error('Previous decoder did not stop; refusing a confounded comparison');
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  return main(() => {
    const s = service(); bufferMs = ms;
    if (s.player.value !== null) { s.player.value.release(); s.player.value = null; }
    if (s.vlc.value !== null) { s.vlc.value.release(); s.vlc.value = null; }
    s.normalization.value = null;
    s.load.overload('long','long').call(s, original.id, original.position);
    return {bufferMs, id:Number(s.current.value.id.value), session:s.audioSession.value};
  });
}
rpc.exports = {
  snapshot() { return main(() => {
    const s = service(), current = s.current.value;
    if (current === null) throw Error('No current track');
    original = {id:Number(current.id.value), position:Number(s.position()), playing:!!s.playing.value,
      session:s.audioSession.value, uri:String(current.uri.value)};
    return original;
  }); },
  reload(ms) {
    if (!original) throw Error('Snapshot first');
    return rebuild(ms);
  },
  restore() {
    if (!original) return null;
    return rebuild(0);
  },
  finishrestore() { return main(() => {
    const s = service(); s.seek(original.position);
    if (!original.playing) s.pause.overload('boolean').call(s, false);
    return {id:Number(s.current.value.id.value), position:Number(s.position()), playing:!!s.playing.value,
      session:s.audioSession.value, uri:String(s.current.value.uri.value)};
  }); }
};
