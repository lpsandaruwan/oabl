"""OABL-only, real-player A/B/A buffer test. No APK install or saved sound-setting edits."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import threading
import time
import frida

adb, endpoint, output = sys.argv[1:4]
inplace = len(sys.argv) > 4 and sys.argv[4] == 'inplace'
output = pathlib.Path(output)
ready = threading.Event()
events = []

def shell(command):
    completed = subprocess.run([adb, '-s', endpoint, 'shell', command], text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=15)
    if completed.returncode and not (command.startswith('am stopservice ') and
            ('Service stopped' in completed.stdout or 'was not running' in completed.stdout)):
        raise RuntimeError('ADB command failed: ' + completed.stdout.strip())
    return completed.stdout

def fingerprints():
    return shell('sha256sum /data/user/0/com.softiva.oabl/shared_prefs/equalizer_profile.xml '
                 '/data/user/0/com.softiva.oabl/shared_prefs/launcher_preferences.xml').strip()

def capture(label):
    report = shell('dumpsys media.audio_flinger')
    (output / (label + '.audioflinger.txt')).write_text(report)
    threads = []
    for part in re.split(r'(?=^Output thread )', report, flags=re.M):
        if not part.startswith('Output thread '):
            continue
        name = part.splitlines()[0]
        active = re.search(r'(\d+) Tracks of which (\d+) are active', part)
        if active and int(active[2]):
            fifo = re.search(r'Fifo frame underruns:.*?\bn: (\d+)\)', part)
            lines = part.splitlines()
            tracks = []
            for i, line in enumerate(lines):
                if 'Active Client Session Port Id' in line:
                    for row in lines[i+1:]:
                        fields = row.split()
                        if len(fields) < 24 or fields[1] not in ('yes', 'no'):
                            break
                        if fields[1] == 'yes':
                            tracks.append({'id':int(fields[0]), 'pid':int(fields[2]), 'session':int(fields[3]),
                                           'rate':int(fields[9]), 'frames':int(fields[18]), 'ready':int(fields[19]), 'underruns':int(fields[21])})
            threads.append({'thread':name, 'fifo':int(fifo[1]) if fifo else 0, 'tracks':tracks,
                            'dynamics':part.count('- name: Dynamics Processing'),
                            'visualizers':part.count('- name: Visualizer')})
    return threads

def message(item, data):
    events.append(item)
    if item.get('type') == 'send':
        payload = item['payload']
        print(json.dumps(payload), flush=True)
        if payload.get('event') == 'hook-ready': ready.set()
    else:
        print('AGENT ' + json.dumps(item), flush=True)

before = fingerprints()
pid = int(shell('pidof com.softiva.oabl').strip().split()[0])
device = frida.get_device_manager().add_remote_device('127.0.0.1:27942')
session = device.attach(pid)
script = session.create_script(pathlib.Path(__file__).with_name('inplace-buffer-agent.js' if inplace else 'live-buffer-agent.js').read_text())
script.on('message', message)
result = {'pid':pid, 'phases':[], 'fingerprints_before':before}
started = False
try:
    script.load()
    if not ready.wait(15): raise RuntimeError('AudioTrack hook did not become ready')
    original = script.exports_sync.snapshot()
    result['original'] = original
    print('SNAPSHOT ' + json.dumps({k:v for k,v in original.items() if k != 'uri'}), flush=True)
    started = True
    test_session = original['session']
    if inplace:
        shell('am stopservice --user 0 -n com.softiva.oabl/.MusicService')
        time.sleep(1)
        shell('am startservice --user 0 -n com.softiva.oabl/.MusicService -a play')
        time.sleep(2)
        settled = script.exports_sync.settle()
        test_session = settled['session']
        print('SETTLED ' + json.dumps({k:v for k,v in settled.items() if k != 'uri'}), flush=True)
    for label, ms in [('A-default',0), ('B-250ms',250), ('A-return',0)]:
        print('PHASE ' + label + ' ' + json.dumps(script.exports_sync.reload(ms)), flush=True)
        time.sleep(3)
        first = capture(label + '-start')
        for retry in range(5):
            if any(t['pid'] == pid and t['session'] == test_session for thread in first for t in thread['tracks']):
                break
            time.sleep(1)
            first = capture(label + '-start')
        print('START ' + json.dumps(first), flush=True)
        tracks = [t for thread in first for t in thread['tracks'] if t['pid'] == pid and t['session'] == test_session]
        if len(tracks) != 1: raise RuntimeError('Expected exactly one active OABL track in its original audio session')
        if ms and tracks[0]['frames'] < tracks[0]['rate'] * ms / 1000: raise RuntimeError('Larger output buffer was not actually applied')
        time.sleep(6 if inplace else 12)
        last = capture(label + '-end')
        phase = {'label':label, 'buffer_ms':ms, 'seconds':6 if inplace else 12, 'first':first, 'last':last}
        result['phases'].append(phase)
        print('END ' + label + ' ' + json.dumps(last), flush=True)
finally:
    if started:
        try:
            restore_value = script.exports_sync.restore()
            print('RESTORE ' + json.dumps({k:v for k,v in restore_value.items() if k != 'uri'}), flush=True)
            if inplace:
                shell('am stopservice --user 0 -n com.softiva.oabl/.MusicService')
                time.sleep(1)
                shell('am startservice --user 0 -n com.softiva.oabl/.MusicService -a play')
            time.sleep(3)
            restored = script.exports_sync.finishrestore()
            result['restored'] = restored
            print('RESTORED ' + json.dumps({k:v for k,v in restored.items() if k != 'uri'}), flush=True)
        except Exception as error:
            result['restore_error'] = str(error)
            print('RESTORE ERROR ' + str(error), flush=True)
    try: script.unload()
    finally: session.detach()
    result['fingerprints_after'] = fingerprints()
    result['settings_unchanged'] = before == result['fingerprints_after']
    result['events'] = events
    (output / 'buffer-ab-results.json').write_text(json.dumps(result, indent=2))
    print('SETTINGS_UNCHANGED ' + str(result['settings_unchanged']), flush=True)
