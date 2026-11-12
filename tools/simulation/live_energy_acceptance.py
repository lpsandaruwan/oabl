#!/usr/bin/env python3
"""Emulator-only process-death acceptance; run after taking a complete emulator app-data backup."""
import argparse,json,os,re,shlex,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5554');p.add_argument('--output',required=True);args=p.parse_args()
adb=os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
def shell(*words):return subprocess.check_output([adb,'-s',args.serial,'shell',shlex.join(map(str,words))],text=True).strip()
if not args.serial.startswith('emulator-') or shell('getprop','ro.hardware') not in ('ranchu','goldfish'):raise SystemExit('Refused non-emulator')
root=ET.fromstring(subprocess.check_output([adb,'-s',args.serial,'exec-out','run-as','com.koushikdutta.vysor','cat','shared_prefs/oabllink.xml']))
link=next(x.text for x in root.findall('string') if x.attrib['name']=='install_id')
def sql(query,pkg='com.softiva.oabl',db='energy.db'):return shell('run-as',pkg,'sqlite3','-cmd','.timeout 10000','databases/'+db,query)
def pid(name):
 try:return shell('pidof',name).split()[0]
 except subprocess.CalledProcessError:return None
def rows():return int(sql("select count(*) from car_sample where link='"+link+"'"))
def wait_for(test,seconds=45):
 until=time.monotonic()+seconds
 while time.monotonic()<until:
  if test():return
  time.sleep(.25)
 raise AssertionError('Timed out waiting for process/sample recovery')
report={'checks':[]}
def check(test,label):
 if not test:raise AssertionError(label)
 report['checks'].append(label);print('PASS '+label,flush=True)
try:
 shell('am','start','-n','com.softiva.oabl/.MainActivity');wait_for(lambda:pid('com.softiva.oabl:energy'))
 baseline=rows();wait_for(lambda:rows()>baseline)
 energy=pid('com.softiva.oabl:energy');ui=pid('com.softiva.oabl');check(energy!=ui,'collector uses an independent process')
 shell('am','start','-a','android.settings.SETTINGS');time.sleep(1)
 baseline=rows()
 for attempt in range(10):
  shell('am','kill','com.softiva.oabl');time.sleep(2)
  if pid('com.softiva.oabl')!=ui:break
 check(pid('com.softiva.oabl')!=ui,'am kill removes the background UI process')
 check(pid('com.softiva.oabl:energy')==energy and rows()>baseline,'collection continues through am kill without restarting energy')
 shell('am','start','-n','com.softiva.oabl/.MainActivity');wait_for(lambda:pid('com.softiva.oabl'))
 stacks=shell('am','stack','list');stack=next(block for block in re.split(r'(?=RootTask id=)',stacks) if 'com.softiva.oabl/com.softiva.oabl.MainActivity' in block)
 task=re.search(r'RootTask id=(\d+)',stack).group(1);baseline=rows();shell('am','stack','remove',task);time.sleep(3)
 check(pid('com.softiva.oabl:energy')==energy and rows()>baseline,'removing the launcher task from recents preserves collection')
 shell('am','start','-n','com.softiva.oabl/.MainActivity');wait_for(lambda:pid('com.softiva.oabl'));ui=pid('com.softiva.oabl')
 baseline=rows();shell('am','crash',ui);time.sleep(4)
 check(pid('com.softiva.oabl:energy')==energy and rows()>baseline,'a launcher crash leaves the collector running')
 # The service must restart without opening Home or issuing a service start.
 baseline=rows();before_seq=int(sql("select coalesce(max(seq),0) from car_sample where link='"+link+"'"));shell('am','crash',energy)
 wait_for(lambda:pid('com.softiva.oabl:energy') and pid('com.softiva.oabl:energy')!=energy,60)
 wait_for(lambda:rows()>baseline,60)
 # Give bounded signed catch-up time to drain; seqs after our baseline must be contiguous.
 time.sleep(5)
 values=sql("select count(*),max(seq)-min(seq)+1 from car_sample where link='"+link+"' and seq>"+str(before_seq)).split('|')
 check(values[0]==values[1] and int(values[0])>0,'energy restarts and backfills every numbered sample with no duplicates')
 check(sql('pragma integrity_check')=='ok','SQLite remains consistent after both process deaths')
 report['result']='PASS'
except BaseException as e:report['result']='FAIL';report['error']=repr(e);raise
finally:Path(args.output).write_text(json.dumps(report,indent=2)+'\n')
