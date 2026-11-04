// Captures what every other program plays, before the system volume, and writes it to stdout as mono float samples.
// The usual loopback of the playback device hears the system volume: on many Windows 11 computers, the user's among them,
// a muted system gives it only zeros, so muting the speakers stopped the subtitles. This asks Windows for the sound of
// all processes except one tree (OpenSub's own), the per-process loopback added in Windows 10 2004.
//
// Usage: audio-capture <process id to leave out> [rate]  streams mono float samples at rate (48000) until stopped
//        audio-capture --probe <seconds>                prints, each second, the level heard this way and by the usual loopback
#include <windows.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <audiopolicy.h>
#include <endpointvolume.h>
#include <mmdeviceapi.h>

#include <fcntl.h>
#include <io.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

static const int PROBE_RATE = 16000;

class Activation : public IActivateAudioInterfaceCompletionHandler, public IAgileObject {
public:
    HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    HRESULT result = E_FAIL;
    IAudioClient * client = nullptr;

    STDMETHODIMP QueryInterface(REFIID riid, void ** out) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IActivateAudioInterfaceCompletionHandler)) *out = static_cast<IActivateAudioInterfaceCompletionHandler *>(this);
        else if (riid == __uuidof(IAgileObject)) *out = static_cast<IAgileObject *>(this);
        else { *out = nullptr; return E_NOINTERFACE; }
        AddRef();
        return S_OK;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&references); }
    STDMETHODIMP_(ULONG) Release() override {
        const ULONG left = InterlockedDecrement(&references);
        if (left == 0) delete this;
        return left;
    }
    STDMETHODIMP ActivateCompleted(IActivateAudioInterfaceAsyncOperation * operation) override {
        HRESULT activated = E_FAIL;
        IUnknown * unknown = nullptr;
        const HRESULT got = operation->GetActivateResult(&activated, &unknown);
        result = FAILED(got) ? got : activated;
        if (SUCCEEDED(result) && unknown != nullptr) result = unknown->QueryInterface(IID_PPV_ARGS(&client));
        if (unknown != nullptr) unknown->Release();
        SetEvent(done);
        return S_OK;
    }

private:
    LONG references = 1;
};

static int fail(const char * what, HRESULT hr) {
    std::fprintf(stderr, "%s failed: 0x%08lx\n", what, (unsigned long) hr);
    return 1;
}

static long long now100ns() {
    static LARGE_INTEGER frequency = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    return (long long) ((double) counter.QuadPart * 10000000.0 / (double) frequency.QuadPart);
}

/** The sound of every process but [leftOut]'s tree, converted by Windows to mono float at [rate]. */
static HRESULT openProcessLoopback(DWORD leftOut, int rate, IAudioClient ** client) {
    AUDIOCLIENT_ACTIVATION_PARAMS params = {};
    params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    params.ProcessLoopbackParams.TargetProcessId = leftOut;
    params.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE;
    PROPVARIANT variant = {};
    variant.vt = VT_BLOB;
    variant.blob.cbSize = sizeof(params);
    variant.blob.pBlobData = reinterpret_cast<BYTE *>(&params);
    Activation * activation = new Activation();
    IActivateAudioInterfaceAsyncOperation * operation = nullptr;
    HRESULT hr = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient), &variant, activation, &operation);
    if (FAILED(hr)) { activation->Release(); return hr; }
    WaitForSingleObject(activation->done, INFINITE);
    hr = activation->result;
    *client = activation->client;
    operation->Release();
    activation->Release();
    if (FAILED(hr)) return hr;
    WAVEFORMATEX format = {};
    format.wFormatTag = WAVE_FORMAT_IEEE_FLOAT;
    format.nChannels = 1;
    format.nSamplesPerSec = rate;
    format.wBitsPerSample = 32;
    format.nBlockAlign = 4;
    format.nAvgBytesPerSec = rate * 4;
    return (*client)->Initialize(AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
        200 * 10000, 0, &format, nullptr);
}

/** Reads packets as they come and keeps time with zeros, since nothing arrives while nothing plays. */
class Reader {
public:
    Reader(IAudioCaptureClient * capture, int rate) : capture(capture), rate(rate) {}

    template <typename Sink> HRESULT drain(Sink && sink) {
        UINT32 waiting = 0;
        HRESULT hr;
        while ((hr = capture->GetNextPacketSize(&waiting)) == S_OK && waiting > 0) {
            BYTE * data = nullptr;
            UINT32 frames = 0;
            DWORD flags = 0;
            UINT64 position = 0, qpc = 0;
            hr = capture->GetBuffer(&data, &frames, &flags, &position, &qpc);
            if (FAILED(hr)) return hr;
            if (qpc != 0) catchUp((long long) qpc, sink);
            const float * samples = reinterpret_cast<const float *>(data);
            buffer.assign(frames, 0.0f);
            if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) buffer.assign(samples, samples + frames);
            sink(buffer.data(), frames);
            written += frames;
            capture->ReleaseBuffer(frames);
        }
        if (FAILED(hr)) return hr;
        // A 50 ms cushion, so a packet that is merely late is not pushed back by zeros in front of it.
        catchUp(now100ns() - 500000, sink);
        return S_OK;
    }

    long long start = now100ns();

private:
    template <typename Sink> void catchUp(long long at100ns, Sink && sink) {
        const long long due = (at100ns - start) * rate / 10000000;
        while (written < due) {
            const long long count = std::min<long long>(due - written, rate / 50);
            buffer.assign((size_t) count, 0.0f);
            sink(buffer.data(), (UINT32) count);
            written += count;
        }
    }

    IAudioCaptureClient * capture;
    int rate;
    std::vector<float> buffer;
    long long written = 0;
};

static int stream(DWORD leftOut, int rate) {
    _setmode(_fileno(stdout), _O_BINARY);
    IAudioClient * client = nullptr;
    HRESULT hr = openProcessLoopback(leftOut, rate, &client);
    if (FAILED(hr)) return fail("process loopback", hr);
    IAudioCaptureClient * capture = nullptr;
    if (FAILED(hr = client->GetService(IID_PPV_ARGS(&capture)))) return fail("capture client", hr);
    HANDLE ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    client->SetEventHandle(ready);
    Reader reader(capture, rate);
    if (FAILED(hr = client->Start())) return fail("start", hr);
    reader.start = now100ns();
    bool open = true;
    while (open) {
        WaitForSingleObject(ready, 20);
        hr = reader.drain([&](const float * samples, UINT32 count) {
            if (open && std::fwrite(samples, sizeof(float), count, stdout) != count) open = false;
        });
        if (FAILED(hr)) return fail("capture", hr);
        if (std::fflush(stdout) != 0) open = false;
    }
    client->Stop();
    return 0;
}

/** The usual loopback of the default playback device, as OpenSub has used it, for comparison. */
static HRESULT openDeviceLoopback(IAudioClient ** client, WAVEFORMATEX ** format, bool * muted) {
    IMMDeviceEnumerator * devices = nullptr;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&devices));
    if (FAILED(hr)) return hr;
    IMMDevice * device = nullptr;
    if (FAILED(hr = devices->GetDefaultAudioEndpoint(eRender, eConsole, &device))) return hr;
    IAudioEndpointVolume * volume = nullptr;
    BOOL mute = FALSE;
    if (SUCCEEDED(device->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr, reinterpret_cast<void **>(&volume)))) {
        volume->GetMute(&mute);
        volume->Release();
    }
    *muted = mute != FALSE;
    if (FAILED(hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void **>(client)))) return hr;
    if (FAILED(hr = (*client)->GetMixFormat(format))) return hr;
    return (*client)->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 200 * 10000, 0, *format, nullptr);
}

/** Which programs have sound open on the default playback device, whether they are playing, and their peak level. */
static void listSessions() {
    IMMDeviceEnumerator * devices = nullptr;
    IMMDevice * device = nullptr;
    IAudioSessionManager2 * manager = nullptr;
    IAudioSessionEnumerator * sessions = nullptr;
    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&devices)))) return;
    if (FAILED(devices->GetDefaultAudioEndpoint(eRender, eConsole, &device))) return;
    if (FAILED(device->Activate(__uuidof(IAudioSessionManager2), CLSCTX_ALL, nullptr, reinterpret_cast<void **>(&manager)))) return;
    if (FAILED(manager->GetSessionEnumerator(&sessions))) return;
    int count = 0;
    sessions->GetCount(&count);
    for (int i = 0; i < count; ++i) {
        IAudioSessionControl * control = nullptr;
        if (FAILED(sessions->GetSession(i, &control))) continue;
        IAudioSessionControl2 * control2 = nullptr;
        DWORD pid = 0;
        if (SUCCEEDED(control->QueryInterface(IID_PPV_ARGS(&control2)))) { control2->GetProcessId(&pid); control2->Release(); }
        AudioSessionState state = AudioSessionStateInactive;
        control->GetState(&state);
        float peak = 0;
        IAudioMeterInformation * meter = nullptr;
        if (SUCCEEDED(control->QueryInterface(IID_PPV_ARGS(&meter)))) { meter->GetPeakValue(&peak); meter->Release(); }
        char name[MAX_PATH] = "?";
        if (HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid)) {
            DWORD size = MAX_PATH;
            QueryFullProcessImageNameA(process, 0, name, &size);
            CloseHandle(process);
        }
        std::printf("session pid %lu  %s  %s  peak %.4f\n", (unsigned long) pid, state == AudioSessionStateActive ? "playing" : "idle", name, peak);
        control->Release();
    }
}

static int probe(int seconds) {
    IAudioClient * perProcess = nullptr;
    HRESULT hr = openProcessLoopback(GetCurrentProcessId(), PROBE_RATE, &perProcess);
    if (FAILED(hr)) return fail("process loopback", hr);
    IAudioCaptureClient * perProcessCapture = nullptr;
    if (FAILED(hr = perProcess->GetService(IID_PPV_ARGS(&perProcessCapture)))) return fail("capture client", hr);
    HANDLE ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    perProcess->SetEventHandle(ready);

    IAudioClient * device = nullptr;
    WAVEFORMATEX * deviceFormat = nullptr;
    bool muted = false;
    if (FAILED(hr = openDeviceLoopback(&device, &deviceFormat, &muted))) return fail("device loopback", hr);
    IAudioCaptureClient * deviceCapture = nullptr;
    if (FAILED(hr = device->GetService(IID_PPV_ARGS(&deviceCapture)))) return fail("device capture client", hr);
    const bool deviceFloat = deviceFormat->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
        (deviceFormat->wFormatTag == WAVE_FORMAT_EXTENSIBLE && deviceFormat->wBitsPerSample == 32);

    Reader reader(perProcessCapture, PROBE_RATE);
    perProcess->Start();
    device->Start();
    reader.start = now100ns();
    std::printf("system muted: %s\n", muted ? "yes" : "no");
    listSessions();
    for (int second = 1; second <= seconds; ++second) {
        double processSum = 0, deviceSum = 0;
        long long processCount = 0, deviceCount = 0;
        const long long end = now100ns() + 10000000;
        while (now100ns() < end) {
            WaitForSingleObject(ready, 20);
            if (FAILED(hr = reader.drain([&](const float * samples, UINT32 count) {
                for (UINT32 i = 0; i < count; ++i) processSum += samples[i] * samples[i];
                processCount += count;
            }))) return fail("capture", hr);
            UINT32 waiting = 0;
            while (deviceCapture->GetNextPacketSize(&waiting) == S_OK && waiting > 0) {
                BYTE * data = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;
                if (FAILED(deviceCapture->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) break;
                const UINT32 values = frames * deviceFormat->nChannels;
                if (deviceFloat && !(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
                    const float * samples = reinterpret_cast<const float *>(data);
                    for (UINT32 i = 0; i < values; ++i) deviceSum += samples[i] * samples[i];
                }
                deviceCount += values;
                deviceCapture->ReleaseBuffer(frames);
            }
        }
        std::printf("second %d  per-process %.5f  device %.5f\n", second,
            processCount ? std::sqrt(processSum / processCount) : 0.0, deviceCount ? std::sqrt(deviceSum / deviceCount) : 0.0);
        std::fflush(stdout);
    }
    perProcess->Stop();
    device->Stop();
    return 0;
}

int main(int argc, char ** argv) {
    if (FAILED(CoInitializeEx(nullptr, COINIT_MULTITHREADED))) return 1;
    if (argc >= 2 && std::string(argv[1]) == "--probe") return probe(argc >= 3 ? std::atoi(argv[2]) : 10);
    if (argc < 2) {
        std::fprintf(stderr, "usage: audio-capture <process id to leave out> [rate] | --probe <seconds>\n");
        return 2;
    }
    return stream((DWORD) std::strtoul(argv[1], nullptr, 10), argc >= 3 ? std::atoi(argv[2]) : 48000);
}
