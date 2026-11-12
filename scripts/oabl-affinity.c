#define _GNU_SOURCE
/* Per-thread CPU placement of audio, OABL and Suding CarPlay threads on all performance cores.
 * No clocks, RT priorities, IRQ or thermal writes. Build with the Android NDK; run by hand with
 * box-affinity.sh, or at boot from oabl-affinity.rc (box-affinity-boot.sh install). Root only.
 * Threads a vendor pinned to a single fast core (Suding pins its iAP2/AirPlay threads to cpu4,
 * which core_ctl often isolates) are widened to all fast cores as well. A thread whose only
 * allowed core is isolated at that moment reports an empty mask and is handled on a later pass.
 */
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define STATE "/data/local/tmp/oabl-affinity-state"
#define GROUP "/oabl-performance"
#define MAX_RECORDS 16384
typedef struct { int pid, tid; uint64_t start, mask; char group[256]; } Record;
static Record records[MAX_RECORDS];
static size_t record_count;
static uint64_t big, little;
static volatile sig_atomic_t stopping;
static FILE *journal;

static bool read_text(const char *path, char *out, size_t size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    ssize_t n = read(fd, out, size - 1); close(fd);
    if (n < 0 || (size_t)n == size - 1) return false;
    out[n] = 0;
    while (n > 0 && (out[n-1] == '\n' || out[n-1] == '\r')) out[--n] = 0;
    return true;
}
static bool write_text(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    size_t size = strlen(value);
    bool ok = write(fd, value, size) == (ssize_t)size;
    if (close(fd)) ok = false;
    return ok;
}
static bool cpu_list(const char *text, uint64_t *mask) {
    *mask = 0;
    const char *p = text;
    while (*p) {
        if (!isdigit((unsigned char)*p)) return false;
        char *end;
        unsigned long first = strtoul(p, &end, 10), last = first;
        p = end;
        if (*p == '-') { p++; if (!isdigit((unsigned char)*p)) return false; last = strtoul(p, &end, 10); p = end; }
        if (last < first || last >= 64) return false;
        for (unsigned long i = first; i <= last; i++) *mask |= UINT64_C(1) << i;
        if (*p == ',') { p++; if (!*p) return false; }
        else if (*p) return false;
    }
    return *mask != 0;
}
static void format_list(uint64_t mask, char *out, size_t size) {
    size_t used = 0;
    out[0] = 0;
    for (int i = 0; i < 64; i++) if (mask & (UINT64_C(1) << i))
        used += (size_t)snprintf(out + used, size - used, "%s%d", used ? "," : "", i);
}
/* The thread's own allowed set (Cpus_allowed_list). sched_getaffinity() would AND it with the
 * cores core_ctl keeps awake: a correct 4-7 pin then reads as "5,7" while 4 and 6 are isolated,
 * looks wrong, and gets rewritten every pass (and recorded wrongly for rollback). */
static uint64_t affinity(int tid) {
    char path[64], line[256]; uint64_t mask = 0;
    if (tid == 0) snprintf(path, sizeof(path), "/proc/thread-self/status");
    else snprintf(path, sizeof(path), "/proc/%d/status", tid);
    FILE *file = fopen(path, "re");
    if (!file) return 0;
    while (fgets(line, sizeof(line), file)) {
        if (strncmp(line, "Cpus_allowed_list:", 18)) continue;
        char *value = line + 18;
        while (*value == ' ' || *value == '\t') value++;
        value[strcspn(value, "\r\n")] = 0;
        if (!cpu_list(value, &mask)) mask = 0;
        break;
    }
    fclose(file);
    return mask;
}
static bool set_affinity(int tid, uint64_t mask) {
    cpu_set_t set; CPU_ZERO(&set);
    for (int i = 0; i < 64; i++) if (mask & (UINT64_C(1) << i)) CPU_SET(i, &set);
    return !sched_setaffinity(tid, sizeof(set), &set);
}
static bool numeric(const char *s) {
    if (!*s) return false;
    for (; *s; s++) if (!isdigit((unsigned char)*s)) return false;
    return true;
}
static bool package(const char *name, const char *base) {
    size_t n = strlen(base);
    return !strncmp(name, base, n) && (!name[n] || name[n] == ':');
}
static bool target(const char *name) {
    if (package(name, "com.softiva.oabl") || package(name, "com.suding.speedplay")
            || package(name, "com.android.bluetooth") || package(name, "com.carsyso.bluetooth")) return true;
    static const char *const native[] = { "audioserver", "android.hardware.audio.service",
        "android.hardware.bluetooth@1.0-service-qti", "sdCarplaySvc", "sd_carplay",
        "carnetos.usbmedia", "media.codec", "media.swcodec", NULL };
    for (int i = 0; native[i]; i++) if (!strcmp(name, native[i])) return true;
    return false;
}
static bool process_name(int pid, char *name, size_t size) {
    char path[128], buf[1024];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    if (!read_text(path, buf, sizeof(buf)) || !buf[0]) return false;
    const char *base = strrchr(buf, '/'); base = base ? base + 1 : buf;
    if (strlen(base) >= size) return false;
    strcpy(name, base); return true;
}
static uint64_t start_time(int pid, int tid) {
    char path[128], buf[2048];
    snprintf(path, sizeof(path), "/proc/%d/task/%d/stat", pid, tid);
    if (!read_text(path, buf, sizeof(buf))) return 0;
    char *p = strrchr(buf, ')'), *save;
    if (!p) return 0;
    char *part = strtok_r(p + 2, " ", &save);
    for (int field = 3; part; field++, part = strtok_r(NULL, " ", &save))
        if (field == 22) return strtoull(part, NULL, 10);
    return 0;
}
static bool valid_group(const char *group) {
    if (*group != '/' || strstr(group, "..")) return false;
    for (const char *p = group; *p; p++) if (!(isalnum((unsigned char)*p) || strchr("/_-.", *p))) return false;
    return true;
}
static bool thread_group(int pid, int tid, char *group, size_t size) {
    char path[128]; snprintf(path, sizeof(path), "/proc/%d/task/%d/cpuset", pid, tid);
    return read_text(path, group, size) && valid_group(group);
}
static uint64_t group_cpus(const char *group) {
    char path[512], value[512]; uint64_t mask = 0;
    snprintf(path, sizeof(path), "/dev/cpuset%s/cpus", !strcmp(group, "/") ? "" : group);
    if (read_text(path, value, sizeof(value))) cpu_list(value, &mask);
    return mask;
}
static bool move_thread(int tid, const char *group) {
    char path[512], value[32];
    if (!valid_group(group)) return false;
    snprintf(path, sizeof(path), "/dev/cpuset%s/tasks", !strcmp(group, "/") ? "" : group);
    snprintf(value, sizeof(value), "%d\n", tid);
    return write_text(path, value);
}
static bool topology(void) {
    char value[512], path[256]; uint64_t online;
    if (!read_text("/sys/devices/system/cpu/online", value, sizeof(value)) || !cpu_list(value, &online)) return false;
    unsigned long capacities[64] = {0}, max = 0, min = ~0UL;
    for (int i = 0; i < 64; i++) if (online & (UINT64_C(1) << i)) {
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", i);
        if (!read_text(path, value, sizeof(value))) return false;
        char *end; unsigned long capacity = strtoul(value, &end, 10);
        if (*end || !capacity) return false;
        capacities[i] = capacity;
        if (capacity > max) max = capacity;
        if (capacity < min) min = capacity;
    }
    if (!max || min == max) return false; /* Never guess core numbers on homogeneous/unknown CPUs. */
    big = little = 0;
    for (int i = 0; i < 64; i++) if (capacities[i]) {
        if (capacities[i] == max) big |= UINT64_C(1) << i;
        else little |= UINT64_C(1) << i;
    }
    return big && little;
}
static bool prepare_group(void) {
    char cpus[256], mems[128];
    format_list(big, cpus, sizeof(cpus));
    if (mkdir("/dev/cpuset" GROUP, 0755)) {
        if (errno != EEXIST || group_cpus(GROUP) != big) return false;
        return true;
    }
    if (!read_text("/dev/cpuset/mems", mems, sizeof(mems))) return false;
    return write_text("/dev/cpuset" GROUP "/mems", mems) && write_text("/dev/cpuset" GROUP "/cpus", cpus);
}
static bool open_journal(void) {
    char boot[128], saved[128];
    if (!read_text("/proc/sys/kernel/random/boot_id", boot, sizeof(boot))) return false;
    int fd = open(STATE "/originals.tsv", O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0 || !(journal = fdopen(fd, "r+"))) { if (fd >= 0) close(fd); return false; }
    bool same = fgets(saved, sizeof(saved), journal) && !strncmp(saved, boot, strlen(boot)) && saved[strlen(boot)] == '\n';
    if (!same) {
        if (ftruncate(fd, 0) || fseek(journal, 0, SEEK_SET)) return false;
        fprintf(journal, "%s\n", boot);
        return !fflush(journal) && !fsync(fd);
    }
    char line[512];
    while (fgets(line, sizeof(line), journal)) {
        if (record_count == MAX_RECORDS) return false;
        Record *r = &records[record_count];
        if (sscanf(line, "%d\t%d\t%" SCNu64 "\t%" SCNx64 "\t%255s", &r->pid, &r->tid, &r->start, &r->mask, r->group) != 5
                || r->pid <= 0 || r->tid <= 0 || !r->start || !r->mask || !valid_group(r->group)) return false;
        record_count++;
    }
    clearerr(journal); return !fseek(journal, 0, SEEK_END);
}
static bool remembered(int pid, int tid, uint64_t start) {
    for (size_t i = 0; i < record_count; i++) if (records[i].pid == pid && records[i].tid == tid && records[i].start == start) return true;
    return false;
}
static bool remember(int pid, int tid, uint64_t start, uint64_t mask, const char *group) {
    if (remembered(pid, tid, start)) return true;
    if (!start || !mask || record_count == MAX_RECORDS) return false;
    Record r = { .pid = pid, .tid = tid, .start = start, .mask = mask };
    strcpy(r.group, group);
    /* A thread created after we pinned its parent inherits our placement (our cpuset, or 4-7 in
     * its own cpuset). Its "original" is its process leader's pre-change state, otherwise the off
     * switch would leave it on the performance cores. */
    bool inherited = !strcmp(group, GROUP) || (mask == big && tid != pid);
    if (inherited) {
        uint64_t leader_start = start_time(pid, pid);
        bool found = false;
        for (size_t i = 0; i < record_count; i++) if (records[i].pid == pid && records[i].tid == pid && records[i].start == leader_start) {
            r.mask = records[i].mask; strcpy(r.group, records[i].group); found = true; break;
        }
        if (!found && !strcmp(group, GROUP)) return false;
    }
    if (fprintf(journal, "%d\t%d\t%" PRIu64 "\t%" PRIx64 "\t%s\n", r.pid, r.tid, r.start, r.mask, r.group) < 0
            || fflush(journal) || fsync(fileno(journal))) return false;
    records[record_count++] = r; return true;
}
/* A non-target process's name is read once in its lifetime: reading other daemons' /proc entries
 * from the su domain logs a (permitted) SELinux notice, which would otherwise fill the kernel log.
 * A pid stays "known" while it is listed on every pass, so a reused pid (the process must vanish
 * first) is read again. Processes still in zygote start-up are re-read until they take their
 * final name (an app such as SpeedPlay renames itself right after forking). */
#define KNOWN_SLOTS 16384
typedef struct { int pid; unsigned generation; } Known;
static Known known[KNOWN_SLOTS];
static unsigned generation;
/* Zygote children rename themselves into apps; init's children are named "init" until they exec
 * the service binary (sdCarplaySvc, audioserver...). Both are read again until they settle. */
static bool transient_name(const char *name) {
    return name[0] == '<' || !strncmp(name, "zygote", 6) || !strncmp(name, "usap", 4)
            || !strcmp(name, "webview_zygote") || !strncmp(name, "app_process", 11) || !strcmp(name, "init");
}
static int scan(bool apply, bool verbose) {
    DIR *procs = opendir("/proc");
    if (!procs) return 1;
    struct dirent *entry; int total = 0, failures = 0, matches = 0;
    generation++;
    while ((entry = readdir(procs))) {
        if (!numeric(entry->d_name)) continue;
        int pid = atoi(entry->d_name); char name[256], path[128];
        Known *seen = &known[pid % KNOWN_SLOTS];
        if (seen->pid == pid && seen->generation + 1 == generation) { seen->generation = generation; continue; }
        bool named = process_name(pid, name, sizeof(name));
        if (!named || !target(name)) {
            /* Unnamed = kernel thread (permanent). Transient names are read again next pass. */
            if (!named || !transient_name(name)) { seen->pid = pid; seen->generation = generation; }
            continue;
        }
        snprintf(path, sizeof(path), "/proc/%d/task", pid);
        DIR *tasks = opendir(path); if (!tasks) continue;
        int count = 0, wrong = 0, changed = 0; struct dirent *thread;
        while ((thread = readdir(tasks))) {
            if (!numeric(thread->d_name)) continue;
            int tid = atoi(thread->d_name); uint64_t mask = affinity(tid);
            if (!mask) continue;
            if (!apply) { count++; if (mask != big) wrong++; continue; }
            uint64_t start = start_time(pid, tid);
            if (!start) continue;
            /* The steady-state hot path needs no cpuset-file reads or writes. */
            if (mask == big && remembered(pid, tid, start)) { count++; continue; }
            char group[256];
            if (!thread_group(pid, tid, group, sizeof(group))) continue;
            count++;
            /* Record even already-pinned leaders, so a child inheriting our custom set has a rollback anchor. */
            if (apply && !remember(pid, tid, start, mask, group)) { wrong++; continue; }
            if (mask != big && apply) {
                /* Never widen shared background/audio-app sets. Move only the blocked target thread. */
                bool moved = false;
                if ((group_cpus(group) & big) != big) {
                    if (!move_thread(tid, GROUP)) { wrong++; continue; }
                    moved = true;
                }
                if (start_time(pid, tid) != start || !set_affinity(tid, big)) {
                    if (moved && start_time(pid, tid) == start) move_thread(tid, group);
                    if (start_time(pid, tid)) wrong++;
                    continue;
                }
                changed++;
            }
            uint64_t actual = affinity(tid);
            if (actual && actual != big) wrong++;
        }
        closedir(tasks);
        if (count) {
            matches++; total += count; failures += wrong;
            if (verbose || changed || wrong) printf("%s pid=%d threads=%d verified=%d changed=%d%s\n", name, pid, count, count-wrong, changed, wrong ? " INCOMPLETE" : "");
        }
    }
    closedir(procs);
    if (verbose || failures) printf("TOTAL processes=%d threads=%d mismatched=%d target-mask=%" PRIx64 "\n", matches, total, failures, big);
    fflush(stdout);
    return failures || !matches ? 2 : 0;
}
/* Threads born in the last moments before stop (or during restore) were never recorded but may
 * have inherited 4-7 from a pinned parent: give them their process leader's original placement. */
static int sweep_unrecorded(void) {
    int fixed = 0;
    DIR *procs = opendir("/proc");
    if (!procs) return 0;
    struct dirent *entry;
    while ((entry = readdir(procs))) {
        if (!numeric(entry->d_name)) continue;
        int pid = atoi(entry->d_name); char name[256], path[128];
        if (!process_name(pid, name, sizeof(name)) || !target(name)) continue;
        uint64_t leader_start = start_time(pid, pid); const Record *leader = NULL;
        for (size_t i = 0; i < record_count; i++) if (records[i].pid == pid && records[i].tid == pid && records[i].start == leader_start) { leader = &records[i]; break; }
        if (!leader) continue;
        snprintf(path, sizeof(path), "/proc/%d/task", pid);
        DIR *tasks = opendir(path); if (!tasks) continue;
        struct dirent *thread;
        while ((thread = readdir(tasks))) {
            if (!numeric(thread->d_name)) continue;
            int tid = atoi(thread->d_name);
            if (affinity(tid) != big || remembered(pid, tid, start_time(pid, tid))) continue;
            char group[256];
            if (!thread_group(pid, tid, group, sizeof(group))) continue;
            bool ours = !strcmp(group, GROUP);
            if (ours && !move_thread(tid, leader->group)) continue;
            uint64_t allowed = group_cpus(ours ? leader->group : group);
            uint64_t wanted = leader->mask & allowed;
            if (!wanted) wanted = allowed;
            if (wanted && set_affinity(tid, wanted)) fixed++;
        }
        closedir(tasks);
    }
    closedir(procs);
    return fixed;
}
static bool restored_record[MAX_RECORDS];
static int restore(void) {
    int restored = 0, failed = 0, gone = 0, adapted = 0;
    memset(restored_record, 0, sizeof(restored_record));
    /* The kernel refuses an affinity whose cores are all isolated by core_ctl at that moment
     * (Suding's cpu4-only threads), so those are retried for up to 60 seconds: on this box core 4
     * was parked for at most ~18 s at a time while idle. */
    for (int pass = 0; pass < 120; pass++) {
        bool retry = false;
        for (size_t i = 0; i < record_count; i++) {
            if (restored_record[i]) continue;
            Record *r = &records[i]; char name[256], group[256];
            if (start_time(r->pid, r->tid) != r->start || !process_name(r->pid, name, sizeof(name)) || !target(name)) { gone++; restored_record[i] = true; continue; }
            if (!thread_group(r->pid, r->tid, group, sizeof(group))) { failed++; restored_record[i] = true; continue; }
            if (!strcmp(group, GROUP) && !move_thread(r->tid, r->group)) { failed++; restored_record[i] = true; continue; }
            /* Respect any subsequent Android foreground/background cpuset transition. */
            uint64_t allowed = group_cpus(!strcmp(group, GROUP) ? r->group : group);
            uint64_t wanted = r->mask & allowed;
            if (!wanted) wanted = allowed;
            if (wanted && !set_affinity(r->tid, wanted) && errno == EINVAL && pass < 119) { retry = true; continue; }
            if (wanted != r->mask) adapted++;
            if (!wanted || affinity(r->tid) != wanted) failed++;
            else restored++;
            restored_record[i] = true;
        }
        if (!retry) break;
        usleep(500000);
    }
    int swept = sweep_unrecorded();
    usleep(200000);
    swept += sweep_unrecorded();
    printf("RESTORE verified=%d exited-or-reused=%d adapted-to-current-cpuset=%d failed=%d unrecorded-swept=%d\n", restored, gone, adapted, failed, swept); fflush(stdout);
    return failed ? 2 : 0;
}
static void stop_signal(int sig) { (void)sig; stopping = 1; }
static int stop_watch(void) {
    FILE *pidfile = fopen(STATE "/watch.pid", "r"); int pid = 0; uint64_t start = 0;
    if (pidfile) { int n = fscanf(pidfile, "%d %" SCNu64, &pid, &start); fclose(pidfile); if (n != 2) pid = 0; }
    if (pid <= 1 || !start || start_time(pid, pid) != start) {
        unlink(STATE "/watch.pid"); puts("No live affinity watcher; stale watcher marker cleared."); return 0;
    }
    char path[128]; struct stat other, self;
    snprintf(path, sizeof(path), "/proc/%d/exe", pid);
    if (stat(path, &other) || stat("/proc/self/exe", &self) || other.st_ino != self.st_ino || other.st_dev != self.st_dev) {
        fputs("Watcher executable mismatch; refusing to signal another process.\n", stderr); return 1;
    }
    if (kill(pid, SIGTERM)) return 1;
    /* Restore may retry for up to 60 s while core_ctl has a core isolated. */
    for (int i = 0; i < 650 && start_time(pid, pid) == start; i++) usleep(100000);
    if (start_time(pid, pid) == start) { fputs("Watcher has not stopped yet.\n", stderr); return 1; }
    puts("Watcher stopped; inspect watch.log for verified rollback."); return 0;
}
static void *probe_thread(void *unused) {
    (void)unused;
    /* Only this synthetic test task is moved. No real application is backgrounded or restarted. */
    int tid = gettid();
    if (!move_thread(tid, "/background") || !set_affinity(tid, little)) return (void *)1;
    sleep(4);
    int cpu = sched_getcpu();
    bool ok = affinity(tid) == big && cpu >= 0 && cpu < 64 && (big & (UINT64_C(1) << cpu));
    printf("PROBE new-thread/background reset: %s actual-cpu=%d\n", ok ? "PASS" : "FAIL", cpu);
    return ok ? NULL : (void *)1;
}
static int probe(const char *invoked_as) {
    if (strcmp(invoked_as, "com.softiva.oabl:affinity-probe") || geteuid() != 0 || !topology()) {
        fputs("Run probe via exec -a com.softiva.oabl:affinity-probe with root ADB and a live watcher.\n", stderr); return 1;
    }
    if (!set_affinity(0, little)) return 1;
    sleep(4);
    if (affinity(0) != big) { puts("PROBE new-process: FAIL"); return 1; }
    puts("PROBE new-process: PASS");
    if (!move_thread(gettid(), "/background") || !set_affinity(0, little)) return 1;
    sleep(4);
    if (affinity(0) != big) { puts("PROBE process/background reset: FAIL"); return 1; }
    puts("PROBE process/background reset: PASS");
    pthread_t worker; void *result;
    if (pthread_create(&worker, NULL, probe_thread, NULL) || pthread_join(worker, &result)) return 1;
    return result ? 1 : 0;
}
static int self_test(void) {
    uint64_t mask; int checks = 0;
#define CHECK(condition) do { checks++; if (!(condition)) { fprintf(stderr, "Self-test %d failed\n", checks); return 1; } } while (0)
    CHECK(cpu_list("4-7", &mask) && mask == 0xf0);
    CHECK(cpu_list("0,2-3,7", &mask) && mask == 0x8d);
    CHECK(!cpu_list("7-4", &mask)); CHECK(!cpu_list("64", &mask));
    CHECK(!cpu_list("4-7,", &mask)); CHECK(!cpu_list("", &mask));
    CHECK(target("com.softiva.oabl")); CHECK(target("com.softiva.oabl:audio")); CHECK(target("com.softiva.oabl:services"));
    CHECK(!target("com.softiva.oabl.fake")); CHECK(!target("sh")); CHECK(!target("system_server"));
    CHECK(target("com.suding.speedplay:daemon")); CHECK(target("sdCarplaySvc")); CHECK(target("sd_carplay"));
    CHECK(target("com.android.bluetooth")); CHECK(target("com.carsyso.bluetooth"));
    CHECK(target("android.hardware.bluetooth@1.0-service-qti")); CHECK(target("media.codec")); CHECK(target("media.swcodec"));
    CHECK(!target("media.extractor")); CHECK(!target("com.android.providers.media.module"));
    CHECK(valid_group("/background")); CHECK(valid_group("/")); CHECK(!valid_group("/../x")); CHECK(!valid_group("/a;b"));
    char out[256]; format_list(0xf0, out, sizeof(out)); CHECK(!strcmp(out, "4,5,6,7"));
    CHECK(start_time(getpid(), getpid()) > 0); CHECK(affinity(0) != 0);
    printf("PASS %d affinity-controller checks\n", checks); return 0;
}
int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "Usage: %s status|apply|watch|stop|restore|self-test|probe\n", argv[0]); return 1; }
    const char *mode = argv[1];
    if (!strcmp(mode, "self-test")) return self_test();
    if (!strcmp(mode, "probe")) return probe(argv[0]);
    if (geteuid() != 0) { fputs("Root ADB required; no changes made.\n", stderr); return 1; }
    if (!strcmp(mode, "stop")) return stop_watch();
    bool watch = !strcmp(mode, "watch"), apply = watch || !strcmp(mode, "apply");
    if (!apply && strcmp(mode, "status") && strcmp(mode, "restore")) return 1;
    if (!topology()) { fputs("Cannot verify distinct online performance/efficiency clusters; no changes made.\n", stderr); return 1; }
    printf("Detected performance-mask=%" PRIx64 " efficiency-mask=%" PRIx64 "\n", big, little);
    if (!strcmp(mode, "status")) return scan(false, true);
    if (mkdir(STATE, 0700) && errno != EEXIST) return 1;
    struct stat info;
    if (lstat(STATE, &info) || !S_ISDIR(info.st_mode) || info.st_uid != 0 || (info.st_mode & 0077)) return 1;
    int lock = open(STATE "/lock", O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB)) { fputs("Another affinity controller is active. Stop it before apply/restore.\n", stderr); return 1; }
    if (!open_journal()) { fputs("Cannot securely save/validate rollback state; no changes made.\n", stderr); return 1; }
    if (!apply) return restore();
    if (!prepare_group()) { fputs("Cannot prepare targeted cpuset; no threads changed.\n", stderr); return 1; }
    if (!watch) return scan(true, true);
    /* The small watcher itself belongs on efficiency cores, below normal scheduling priority. */
    if (!set_affinity(0, little)) return 1;
    (void)nice(10);
    signal(SIGTERM, stop_signal); signal(SIGINT, stop_signal);
    FILE *pidfile = fopen(STATE "/watch.pid", "w");
    if (!pidfile) return 1;
    fprintf(pidfile, "%d %" PRIu64 "\n", getpid(), start_time(getpid(), getpid()));
    if (fclose(pidfile)) return 1;
    puts("Watcher started; 2-second reconciliation, saved per-thread rollback.");
    uint64_t expected = big;
    while (!stopping) {
        if (!topology() || big != expected) {
            fputs("CPU topology/online cluster changed; stopping and restoring rather than overriding hotplug.\n", stderr);
            break;
        }
        scan(true, false);
        struct timespec delay = { .tv_sec = 2 };
        nanosleep(&delay, NULL);
    }
    int result = restore();
    unlink(STATE "/watch.pid");
    fclose(journal); close(lock); return result;
}
