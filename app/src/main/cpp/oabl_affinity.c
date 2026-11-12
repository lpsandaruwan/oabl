// Pins the calling thread to the CPU cluster with the highest capacity (the performance cores),
// the same detection scripts/oabl-affinity.c uses: /sys/devices/system/cpu/cpuN/cpu_capacity.
// An app may change the affinity of its own threads within the cores its cpuset allows; it
// cannot move other processes, which stays a job for the root scripts.
#define _GNU_SOURCE
#include <errno.h>
#include <jni.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static long capacity(int cpu) {
    char path[96], value[32];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
    FILE *file = fopen(path, "re");
    if (!file) return -1;
    long result = fgets(value, sizeof(value), file) ? strtol(value, NULL, 10) : -1;
    fclose(file);
    return result;
}

// Returns the mask of cores the thread now runs on, 0 when the cores are all alike (nothing to
// do), or -errno.
JNIEXPORT jlong JNICALL
Java_com_softiva_oabl_CoreAffinity_pinCurrentThreadToFastCores(JNIEnv *env, jclass type) {
    (void) env; (void) type;
    long cpus = sysconf(_SC_NPROCESSORS_CONF);
    if (cpus <= 0 || cpus > 64) return -EINVAL;
    long max = 0, min = -1;
    for (int i = 0; i < cpus; i++) {
        long c = capacity(i);
        if (c <= 0) continue;
        if (c > max) max = c;
        if (min < 0 || c < min) min = c;
    }
    if (max <= 0 || max == min) return 0;
    cpu_set_t set;
    CPU_ZERO(&set);
    jlong mask = 0;
    for (int i = 0; i < cpus; i++) {
        if (capacity(i) == max) { CPU_SET(i, &set); mask |= 1LL << i; }
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) return -errno;
    return mask;
}
