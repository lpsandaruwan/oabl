#!/usr/bin/env python3
"""Measure the real emulator Link APK's simulated 2.6 kW charge through the production client."""
import argparse,json,os,shlex,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5554');p.add_argument('--output',required=True);a=p.parse_args();adb=os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
def shell(*words):return subprocess.check_output([adb,'-s',a.serial,'shell',shlex.join(map(str,words))],text=True).strip()
if not a.serial.startswith('emulator-') or shell('getprop','ro.hardware') not in ('ranchu','goldfish'):raise SystemExit('Refused non-emulator')
def phase(value):subprocess.run([adb,'-s',a.serial,'shell',shlex.join(['run-as','com.koushikdutta.vysor','sh','-c','cat > files/simulation-phase-seconds.next && mv files/simulation-phase-seconds.next files/simulation-phase-seconds'])],input=value.encode(),check=True)
def sql(q):return shell('run-as','com.softiva.oabl','sqlite3','-cmd','.timeout 10000','databases/energy.db',q)
root=ET.fromstring(subprocess.check_output([adb,'-s',a.serial,'exec-out','run-as','com.koushikdutta.vysor','cat','shared_prefs/oabllink.xml']))
link=next(x.text for x in root.findall('string') if x.attrib['name']=='install_id')
phase('300 hold');shell('am','start','-n','com.softiva.oabl/.MainActivity');time.sleep(4)
start=int(time.time()*1000)-10000;time.sleep(18);phase('660 hold');time.sleep(4)
query="select data from car_event where kind='charge' and state!='open' and link='"+link+"' order by start desc limit 1"
event=json.loads(sql(query));assert event['covered_seconds']>=10,event
assert abs(event['charger_kwh']-2.6*event['covered_seconds']/3600)<1e-10,event
latest=json.loads(sql("select data from car_frame where link='"+link+"' order by t desc limit 1"))
for signal in ('hu_boot_id','trip_b_km','trip_b_kwh100','trip_c_km','trip_c_kwh100'):assert signal in latest['values'],signal
report={'result':'PASS','source':'real Link APK; simulated probe on Android emulator','charger_kwh':event['charger_kwh'],'coverage_seconds':event['covered_seconds'],'elapsed_seconds':(event['end']-event['start'])/1000,'signals':['hu_boot_id','trip_b_km','trip_b_kwh100','trip_c_km','trip_c_kwh100'],'event':event}
Path(a.output).write_text(json.dumps(report,indent=2)+'\n');print('PASS real Link APK → collector: simulated 2.6 kW integration, coverage and extended signals',flush=True)
