// Allocate headroom once, then compare effective AudioTrack sizes in-place.
// Decoder, file, session, EQ and spectrum remain identical across A/B/A.
let allocateHeadroom = true, retained = null, original = null, smallFrames = 0;
const main = fn => new Promise((resolve,reject) => Java.perform(() => Java.scheduleOnMainThread(() => {
  try { resolve(fn()); } catch (e) { reject(String(e.stack || e)); }
})));
const music = () => Java.use('com.softiva.oabl.MusicService').instance.value;
Java.perform(() => {
  const Track = Java.use('android.media.AudioTrack');
  const ctor = Track.$init.overload('int','int','int','int','int','int','int');
  ctor.implementation = function(stream, rate, channels, encoding, bytes, mode, session) {
    const eligible = stream === 3 && channels === 12 && encoding === 2 && mode === 1;
    const capacity = eligible && allocateHeadroom ? Math.max(bytes, rate) : bytes; // 250 ms, stereo PCM16
    ctor.call(this, stream, rate, channels, encoding, capacity, mode, session);
    if (eligible) {
      smallFrames = Math.floor(bytes / 4);
      this.setBufferSizeInFrames(smallFrames);
      this.setStartThresholdInFrames(smallFrames);
      retained = Java.retain(this);
      send({event:'track-created', session, capacity:this.getBufferCapacityInFrames(), size:this.getBufferSizeInFrames(), originalFrames:smallFrames});
    }
  };
  send({event:'hook-ready'});
});
function state() {
  const s = music();
  return {id:Number(s.current.value.id.value),position:Number(s.position()),playing:!!s.playing.value,
    session:s.audioSession.value,uri:String(s.current.value.uri.value),
    buffer:retained === null ? null : retained.getBufferSizeInFrames(),
    capacity:retained === null ? null : retained.getBufferCapacityInFrames()};
}
rpc.exports = {
  snapshot() { return main(() => { original = state(); return original; }); },
  settle() { return main(() => {
    if (retained === null || retained.getAudioSessionId() !== music().audioSession.value) throw Error('Waiting for the new output track');
    return state();
  }); },
  reload(ms) { return main(() => {
    if (!retained || retained.getAudioSessionId() !== music().audioSession.value) throw Error('Output track changed unexpectedly');
    const wanted = ms ? Math.ceil(retained.getSampleRate() * ms / 1000) : smallFrames;
    const actual = retained.setBufferSizeInFrames(wanted);
    if (actual !== wanted) throw Error('Kernel rejected size: wanted ' + wanted + ', actual ' + actual);
    return {bufferMs:ms,wanted,...state()};
  }); },
  restore() { return main(() => { allocateHeadroom = false; return original; }); },
  finishrestore() { return main(() => {
    music().seek(original.position);
    if (!original.playing) music().pause.overload('boolean').call(music(),false);
    return state();
  }); }
};
