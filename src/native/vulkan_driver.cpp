// Vulkan driver selection on Android. DXVK needs Vulkan features that Qualcomm's own Adreno drivers often lack
// (robustness2 null descriptors, extended dynamic state, ...), so on Adreno the port loads Mesa's Turnip driver
// through libadrenotools (which gets a driver past Android's linker namespaces), and hands its
// vkGetInstanceProcAddr to DXVK (DXVK_VK_GIPA, android/patches/dxvk-android.patch). An imported
// AdrenoTools Turnip ZIP (SetupActivity unpacks it into internal storage) is tried first, then the driver
// shipped in the APK. A candidate is used only if it finds the GPU as Turnip; otherwise, and on other GPUs
// (Mali, PowerVR, Xclipse), the system driver stays.
// Setting vulkan_driver: auto (default) | system | turnip.
#include "guest.h"

#if defined(__ANDROID__) && defined(PROTORE_ADRENOTOOLS)
#include <dlfcn.h>
#include <poll.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <unistd.h>

#include <adrenotools/driver.h>
#include <vulkan/vulkan.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

// libadrenotools is built for API 29, where libc exports android_get_device_api_level; libmain targets API 26,
// whose headers only have an inline version, so the Makefile points the symbol here (--defsym).
extern "C" int protore_device_api_level() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("ro.build.version.sdk", value);
    return atoi(value);
}

namespace native {

namespace {

// Creates a throwaway instance to see which GPU the driver drives; returns the device name ("" if none) and puts a
// line for the log in `report` (the probe may run in a forked child, which must not touch the log).
std::string ProbeDevice(PFN_vkGetInstanceProcAddr gipa, const char* driver, std::string& report) {
    char line[256];
    auto create = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
    if (!create) {
        snprintf(line, sizeof(line), "vulkan: %s has no vkCreateInstance", driver);
        report = line;
        return "";
    }
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (VkResult r = create(&ci, nullptr, &instance); r != VK_SUCCESS) {
        snprintf(line, sizeof(line), "vulkan: %s vkCreateInstance -> %d", driver, r);
        report = line;
        return "";
    }
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(instance, "vkEnumeratePhysicalDevices"));
    auto props = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gipa(instance, "vkGetPhysicalDeviceProperties"));
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(gipa(instance, "vkDestroyInstance"));
    std::string name;
    uint32_t count = 1;
    VkPhysicalDevice device = VK_NULL_HANDLE;
    VkResult r = enumerate ? enumerate(instance, &count, &device) : VK_ERROR_INITIALIZATION_FAILED;
    if (props && r >= 0 && count && device) {
        VkPhysicalDeviceProperties p;
        props(device, &p);
        name = p.deviceName;
        snprintf(line, sizeof(line), "vulkan: %s sees %s (Vulkan %u.%u.%u, driver %08x, vendor %04x device %08x)",
                 driver, p.deviceName, VK_API_VERSION_MAJOR(p.apiVersion), VK_API_VERSION_MINOR(p.apiVersion),
                 VK_API_VERSION_PATCH(p.apiVersion), p.driverVersion, p.vendorID, p.deviceID);
    } else {
        snprintf(line, sizeof(line), "vulkan: %s vkEnumeratePhysicalDevices -> %d, %u device(s)", driver, r, count);
    }
    report = line;
    if (destroy) destroy(instance, nullptr);
    return name;
}

// Logs the system driver's GPU, for reports from phones where Turnip is not used.
void LogSystemDevice() {
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    auto gipa = lib ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr")) : nullptr;
    if (!gipa) return;
    std::string report;
    ProbeDevice(gipa, "system driver", report);
    Log("%s", report.c_str());
}

// Loads Turnip through adrenotools (in the libvulkan.so loader it returns, Turnip replaces the system driver) and
// checks that the device really is Turnip: if Turnip itself fails to load, the adrenotools hook quietly falls back
// to the system driver, and a second copy of that driver in the process must not be used. `hookDir` is always
// the APK's native library directory; `driverDir` is that same directory for the bundled driver, or the app's
// internal files directory for an imported ZIP (custom drivers cannot be dlopened from /sdcard).
PFN_vkGetInstanceProcAddr LoadTurnip(const std::string& hookDir, const std::string& driverDir,
                                     const std::string& libraryName, const char* label,
                                     std::string& device, std::string& report) {
    // adrenotools joins the directories and the file name as is, so the directories need trailing slashes.
    void* lib = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hookDir.c_str(),
                                           driverDir.c_str(), libraryName.c_str(), nullptr, nullptr);
    if (!lib) {
        const char* err = dlerror();
        report = std::string("vulkan: adrenotools could not load ") + label + " ("
                 + (err ? err : "no dlerror") + ")";
        return nullptr;
    }
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!gipa) {
        report = "vulkan: the adrenotools loader has no vkGetInstanceProcAddr";
        return nullptr;
    }
    device = ProbeDevice(gipa, label, report);
    if (device.find("Turnip") == std::string::npos) {
        report += device.empty() ? "" : " (not Turnip: the driver did not load)";
        device.clear();
        return nullptr;
    }
    return gipa;
}

// Runs LoadTurnip in a forked child first, so that a driver that crashes or hangs on this phone costs a second of
// startup instead of the game. Returns true if the child found Turnip; its log line comes back through a pipe.
bool TurnipWorksInChild(const std::string& hookDir, const std::string& driverDir,
                        const std::string& libraryName, const char* label) {
    int fds[2];
    if (pipe(fds) != 0) return true;  // cannot test: try in-process
    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        return true;
    }
    if (pid == 0) {
        close(fds[0]);
        alarm(10);
        std::string device, report;
        bool ok = LoadTurnip(hookDir, driverDir, libraryName, label, device, report) != nullptr;
        (void)!write(fds[1], report.data(), report.size());
        _exit(ok ? 0 : 3);
    }
    close(fds[1]);
    std::string report;
    char buf[256];
    for (int waited = 0; waited < 8000;) {
        pollfd p = {fds[0], POLLIN, 0};
        int n = poll(&p, 1, 100);
        waited += 100;
        if (n > 0) {
            ssize_t got = read(fds[0], buf, sizeof(buf));
            if (got <= 0) break;  // child closed the pipe: done
            report.append(buf, size_t(got));
        }
    }
    close(fds[0]);
    int status = 0;
    pid_t done = 0;
    for (int i = 0; i < 20 && (done = waitpid(pid, &status, WNOHANG)) == 0; ++i) usleep(50000);
    if (done == 0) {
        kill(pid, SIGKILL);
        waitpid(pid, &status, 0);
        Log("vulkan: %s test hung", label);
        return false;
    }
    if (!report.empty()) Log("%s (test process)", report.c_str());
    if (WIFSIGNALED(status)) {
        Log("vulkan: %s test crashed (signal %d)", label, WTERMSIG(status));
        return false;
    }
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

// Crash guard (working directory = the app's files folder): turnip_pending exists while a Turnip run has not yet
// reached VulkanDriverConfirm, and contains a stamp for the exact driver file in use (app build, path, size and
// mtime). If it is still there at the next start, that candidate crashed or hung during startup (its log is
// proto-native.prev.log): turnip_failed records the same stamp and auto mode skips only that candidate, so a
// bad imported ZIP does not blacklist the bundled driver and a newly imported ZIP is retried. vulkan_driver =
// turnip still forces a retry. A failed test in the child process is not recorded: it costs a second and is
// retried (and logged) at every start.
constexpr const char* kPending = "turnip_pending";
constexpr const char* kFailed = "turnip_failed";
constexpr const char* kBuild = __DATE__ " " __TIME__;  // a failure recorded by another build is retried
bool g_turnip_pending = false;
const char* g_turnip_label = "Turnip";

struct TurnipCandidate {
    const char* label;
    std::string driverDir;  // trailing slash included: libadrenotools joins paths literally
    std::string libraryName;
    std::string stamp;
};

std::string WithTrailingSlash(const char* path) {
    std::string dir = path ? path : "";
    if (!dir.empty() && dir.back() != '/') dir.push_back('/');
    return dir;
}

std::string DriverStamp(const std::string& libraryPath) {
    struct stat st {};
    long long size = 0;
    long long mtime = 0;
    if (stat(libraryPath.c_str(), &st) == 0) {
        size = (long long)st.st_size;
        mtime = (long long)st.st_mtime;
    }
    char stamp[768];
    snprintf(stamp, sizeof(stamp), "%s|%s|%lld|%lld", kBuild, libraryPath.c_str(), size, mtime);
    return stamp;
}

void MarkFailed(const std::string& stamp, const char* reason) {
    if (FILE* f = fopen(kFailed, "w")) {
        fprintf(f, "%s\n%s\n", stamp.empty() ? kBuild : stamp.c_str(), reason);
        fclose(f);
    }
}

std::string ReadFirstLine(const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return "";
    char line[768] = {};
    bool ok = fgets(line, sizeof(line), f) != nullptr;
    fclose(f);
    if (!ok) return "";
    line[strcspn(line, "\r\n")] = 0;
    return line;
}

struct TurnipFailure {
    std::string stamp;
    std::string reason;
};

// The recorded failure for this app build, if any.
TurnipFailure FailedBefore() {
    FILE* f = fopen(kFailed, "r");
    if (!f) return {};
    char stamp[768] = {}, reason[256] = {};
    bool haveStamp = fgets(stamp, sizeof(stamp), f) != nullptr;
    if (haveStamp) fgets(reason, sizeof(reason), f);
    fclose(f);
    if (!haveStamp) return {};
    stamp[strcspn(stamp, "\r\n")] = 0;
    reason[strcspn(reason, "\r\n")] = 0;
    if (strncmp(stamp, kBuild, strlen(kBuild)) != 0) return {};
    return {stamp, reason[0] ? reason : "unknown"};
}

}  // namespace

void VulkanDriverConfirm() {
    if (!g_turnip_pending) return;
    g_turnip_pending = false;
    unlink(kPending);
    Log("vulkan: %s startup ok", g_turnip_label);
}

void SelectVulkanDriver() {
    const std::string mode = Setting("vulkan_driver", "auto");
    const bool adreno = access("/dev/kgsl-3d0", F_OK) == 0;  // Qualcomm's GPU kernel driver
    if (mode == "system" || (mode == "auto" && !adreno)) {
        LogSystemDevice();
        return;
    }

    if (access(kPending, F_OK) == 0) {
        std::string pendingStamp = ReadFirstLine(kPending);
        unlink(kPending);
        MarkFailed(pendingStamp,
                   "the game crashed or hung during startup on Turnip (see proto-native.prev.log)");
        Log("vulkan: the last Turnip run ended during startup");
    }
    TurnipFailure failed = FailedBefore();
    const bool forceTurnip = mode == "turnip";
    if (forceTurnip) {
        unlink(kFailed);
        failed = {};
    }

    const char* libdir = getenv("PROTORE_NATIVE_LIB_DIR");  // ProtoActivity: APK native libraries/hooks
    if (!libdir || !*libdir) {
        Log("vulkan: no APK native library directory for the AdrenoTools hooks, using the system driver");
        LogSystemDevice();
        return;
    }
    const std::string hookDir = WithTrailingSlash(libdir);

    std::vector<TurnipCandidate> candidates;
    const char* customDirEnv = getenv("PROTORE_TURNIP_DRIVER_DIR");    // imported ZIP, internal storage
    const char* customNameEnv = getenv("PROTORE_TURNIP_DRIVER_NAME");  // its meta.json libraryName
    if (customDirEnv && *customDirEnv) {
        std::string customName = customNameEnv && *customNameEnv ? customNameEnv : "libvulkan_freedreno.so";
        std::string customDir = WithTrailingSlash(customDirEnv);
        std::string customPath = customDir + customName;
        if (customName.find_first_of("/\\") != std::string::npos) {
            Log("vulkan: ignoring imported Turnip with unsafe library name %s", customName.c_str());
        } else if (access(customPath.c_str(), F_OK) == 0) {
            candidates.push_back({"imported Turnip", customDir, customName, DriverStamp(customPath)});
        } else {
            Log("vulkan: imported Turnip %s is missing, trying the bundled driver", customPath.c_str());
        }
    }
    const std::string bundledName = "libvulkan_freedreno.so";
    const std::string bundledPath = hookDir + bundledName;
    if (access(bundledPath.c_str(), F_OK) == 0) {
        candidates.push_back({"bundled Turnip", hookDir, bundledName, DriverStamp(bundledPath)});
    }
    if (candidates.empty()) {
        Log("vulkan: no Turnip driver available, using the system driver");
        LogSystemDevice();
        return;
    }

    for (const TurnipCandidate& candidate : candidates) {
        if (!forceTurnip && !failed.stamp.empty()
            && (failed.stamp == candidate.stamp || failed.stamp == kBuild)) {
            Log("vulkan: %s failed here before: %s; skipping it (vulkan_driver = turnip forces a retry)",
                candidate.label, failed.reason.c_str());
            continue;
        }
        if (!TurnipWorksInChild(hookDir, candidate.driverDir, candidate.libraryName, candidate.label)) {
            Log("vulkan: %s not usable", candidate.label);
            continue;
        }
        std::string device, report;
        PFN_vkGetInstanceProcAddr gipa =
            LoadTurnip(hookDir, candidate.driverDir, candidate.libraryName, candidate.label, device, report);
        Log("%s", report.c_str());
        if (!gipa) {
            Log("vulkan: %s not usable", candidate.label);
            continue;
        }
        char hex[32];
        snprintf(hex, sizeof(hex), "%llx", (unsigned long long)reinterpret_cast<uintptr_t>(gipa));
        setenv("DXVK_VK_GIPA", hex, 1);
        Log("vulkan: using %s (Mesa) on %s", candidate.label, device.c_str());
        if (FILE* f = fopen(kPending, "w")) {
            fprintf(f, "%s\n", candidate.stamp.c_str());
            fclose(f);
        }
        g_turnip_pending = true;
        g_turnip_label = candidate.label;
        return;
    }

    Log("vulkan: no usable Turnip driver, using the system driver");
    LogSystemDevice();
}

}  // namespace native

#else

namespace native {

void SelectVulkanDriver() {}
void VulkanDriverConfirm() {}

}  // namespace native

#endif
