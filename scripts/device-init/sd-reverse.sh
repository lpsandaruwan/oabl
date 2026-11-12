#!/system/bin/sh

#############################################
CHANNEL=sd_msm4250
EXE_PATH=/system/bin/
#############################################
#sleep 30
##echo 38 > /sys/class/gpio/export
##echo out > /sys/class/gpio/gpio38/direction
##echo 1 > /sys/class/gpio/gpio38/value

##echo 63 > /sys/class/gpio/export
##echo out > /sys/class/gpio/gpio63/direction 
##echo 1 > /sys/class/gpio/gpio63/value

#echo "host" > /sys/devices/platform/soc/4e00000.ssusb/mode
##echo "peripheral" > /sys/devices/platform/soc/7000000.ssusb/mode
disable_sdcarplay=`getprop persist.sdcarplay.reverse.disable`
product_mode=`cat /sys/devices/platform/soc/soc:qpdata_driver/cp_aa`
if [ $disable_sdcarplay = "1" ];then
setprop persist.carplay.auto 0
setprop persist.vendor.usb.config "diag,serial_cdev,rmnet,dpl,qdss,adb"
setprop vendor.qcom.main.start 1
echo 1270  > /sys/class/gpio/export
echo out > /sys/class/gpio/gpio1270/direction
echo 0   > /sys/class/gpio/gpio1270/value
sleep 1
echo "host" > /sys/devices/platform/soc/4e00000.ssusb/mode
sleep 1
echo "peripheral" > /sys/devices/platform/soc/4e00000.ssusb/mode
sleep 1
setprop sys.usb.ffs.ready 1
setprop sys.usb.config "diag,serial_cdev,rmnet,dpl,qdss,adb"
setprop sys.usb.configfs 1
sleep 1
adb.sh
while true
do
	sleep 2
done
fi

gps=`getprop persist.local_car.gps`
if [ "$gps" ];then
    echo "no empty"
else
    echo "empty default use local gps data"
    setprop persist.local_car.gps 1
    setprop persist.origin_car.gps 0
fi

#function sc138_set_cpu_parameters() {
#    echo 1612800 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq
#    echo 1612800 > /sys/devices/system/cpu/cpu1/cpufreq/scaling_min_freq
#    echo 1612800 > /sys/devices/system/cpu/cpu2/cpufreq/scaling_min_freq
#    echo 1612800 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_min_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu5/cpufreq/scaling_min_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu6/cpufreq/scaling_min_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq
#    echo 1612800 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq
#    echo 1612800 > /sys/devices/system/cpu/cpu1/cpufreq/scaling_max_freq
#    echo 1612800 > /sys/devices/system/cpu/cpu2/cpufreq/scaling_max_freq
#    echo 1612800 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_max_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu5/cpufreq/scaling_max_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu6/cpufreq/scaling_max_freq
#    echo 1804800 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq
#}
#sc138_set_cpu_parameters

if [ $product_mode = "2000" ];then
	setprop persist.carplay.auto 2
	setprop persist.vendor.usb.config "autoReverseTemp"
	insmod /system/lib/modules/snd-aloop.ko
	$EXE_PATH/sd_mdnsd&
	# OABL (12 November): run the daemon under /system/lib/liboabltrace.so, which logs its calls to
	# /data/local/tmp/oabl-trace.log (6 MB cap, one .1 file). Off: setprop persist.oabl.shim 0. If the
	# daemon dies within 20 s three starts in a row the switch turns itself off. Stock line is the else.
	if [ "$(getprop persist.oabl.shim)" = "1" ] && [ -f /system/lib/liboabltrace.so ]; then
		shim_t0=$(date +%s)
		LD_PRELOAD=/system/lib/liboabltrace.so $EXE_PATH/sdAutoReverse -c $CHANNEL
		shim_n=$(getprop persist.oabl.shim.quick); shim_n=${shim_n:-0}
		if [ $(( $(date +%s) - shim_t0 )) -lt 20 ]; then
			shim_n=$((shim_n + 1)); setprop persist.oabl.shim.quick $shim_n
			[ $shim_n -ge 3 ] && setprop persist.oabl.shim 0
		else
			setprop persist.oabl.shim.quick 0
		fi
	else
		$EXE_PATH/sdAutoReverse -c $CHANNEL
	fi
else
	setprop persist.carplay.auto 1
	setprop persist.vendor.usb.config "appleSvcTemp"
	setprop sys.suding.reverse.multi.touch 1
	insmod /system/lib/modules/usb_f_sd_ptp.ko
	insmod /system/lib/modules/usb_f_sd_hid.ko
	insmod /system/lib/modules/usb_f_sd_mux.ko
	insmod /system/lib/modules/usb_f_sd_uac1.ko
	insmod /system/lib/modules/usb_f_sd_vsc.ko
	insmod /system/lib/modules/snd-aloop.ko

	mkdir /config/usb_gadget/g1/functions/ptp_sd.usb0
	mkdir /config/usb_gadget/g1/functions/audio_sd.usb0
	mkdir /config/usb_gadget/g1/functions/hid_sd.usb0
	mkdir /config/usb_gadget/g1/functions/mux_sd.usb0
	mkdir /config/usb_gadget/g1/functions/vsc_sd.usb0
	mkdir /config/usb_gadget/g1/configs/b.2 0770
	mkdir /config/usb_gadget/g1/configs/b.2/strings/0x409 0770
	mkdir /config/usb_gadget/g1/configs/b.3 0770
	mkdir /config/usb_gadget/g1/configs/b.3/strings/0x409 0770
	mkdir /config/usb_gadget/g1/configs/b.4 0770
	mkdir /config/usb_gadget/g1/configs/b.4/strings/0x409 0770

	echo "86364000">/proc/sys/net/core/rmem_max
	echo '1' > /config/usb_gadget/g1/configs/b.1/MaxPower

	$EXE_PATH/sd_mdnsd&
	$EXE_PATH/sdCarplaySvc -c $CHANNEL
fi
