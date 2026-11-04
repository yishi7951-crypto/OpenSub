// Keeps Hy-MT2 loaded and answers one request per line, so OpenSub reaches it through a pipe and never opens a port.
// Request:  english<TAB>chinese already on screen (may be empty)<TAB>english said just before (optional)
//           or \x01 then a whole user message, \n written as backslash n, for the model card's other templates
// Reply:    the chinese that follows it
// A separate process means a native crash cannot take the app down, and llama.cpp shares no libraries with ONNX Runtime.
#include "llama.h"
#include "ggml-cpu.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <fcntl.h>
#include <io.h>
#endif

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

static const int CONTEXT_TOKENS = 1024;
static const int MOST_NEW_TOKENS = 200;
// The model card's wording. The model is trained on its templates: Chinese wording made it chat or refuse instead of translating.
static const std::string INSTRUCTION = "Translate the following text into Chinese. Note that you should only output the translated result without any additional explanation:\n\n";

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    const int size = -llama_tokenize(vocab, text.c_str(), (int) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> tokens(size);
    llama_tokenize(vocab, text.c_str(), (int) text.size(), tokens.data(), size, false, true);
    return tokens;
}

static std::string unescape(const std::string & text) {
    std::string result;
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] == '\\' && i + 1 < text.size() && text[i + 1] == 'n') {
            result += '\n';
            ++i;
        } else result += text[i];
    }
    return result;
}

static std::string piece(const llama_vocab * vocab, llama_token token) {
    char buffer[256];
    const int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
    return size > 0 ? std::string(buffer, size) : std::string();
}

class Session {
public:
    Session(llama_context * context, const llama_vocab * vocab) : context(context), vocab(vocab) {
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    std::string translate(const std::string & english, const std::string & shown, const std::string & before) {
        const std::string request = !english.empty() && english[0] == '\x01' ? unescape(english.substr(1))
            : before.empty() ? INSTRUCTION + english
            : "[Background Information]\n" + before + "\nPlease translate the following text into Chinese, taking the provided background information into consideration.\n[Source Text]\n" + english;
        const std::string prompt = "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + request + "<｜hy_Assistant｜>" + shown;
        std::vector<llama_token> tokens = tokenize(vocab, prompt);
        if ((int) tokens.size() + MOST_NEW_TOKENS >= CONTEXT_TOKENS) return "";
        return answer(tokens, english);
    }

private:
    std::string answer(std::vector<llama_token> & tokens, const std::string & english) {
        const int64_t began = llama_time_us();
        if (!feed(tokens)) return "";
        const int64_t fed = llama_time_us();

        // A cap in proportion to the sentence stops a model stuck repeating itself long before the context runs out.
        const int limit = std::min(MOST_NEW_TOKENS, (int) english.size() / 2 + 24);
        std::string reply;
        for (int count = 0; count < limit; ++count) {
            llama_token next = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, next)) break;
            reply += piece(vocab, next);
            llama_sampler_accept(sampler, next);
            if (llama_decode(context, llama_batch_get_one(&next, 1)) != 0) break;
            remembered.push_back(next);
        }
        if (timing) std::fprintf(stderr, "prompt %d new %d %lld ms, reply %d %lld ms\n", (int) tokens.size(), (int) (tokens.size() - shared_last),
            (long long) (fed - began) / 1000, (int) (remembered.size() - tokens.size()), (long long) (llama_time_us() - fed) / 1000);
        return reply;
    }

    // A growing sentence repeats its prompt, so only the tokens after the shared start need the model's time.
    bool feed(std::vector<llama_token> & tokens) {
        size_t shared = 0;
        while (shared < remembered.size() && shared + 1 < tokens.size() && remembered[shared] == tokens[shared]) ++shared;
        shared_last = shared;
        llama_memory_seq_rm(llama_get_memory(context), 0, (llama_pos) shared, -1);
        remembered.resize(shared);
        if (llama_decode(context, llama_batch_get_one(tokens.data() + shared, (int) (tokens.size() - shared))) != 0) {
            llama_memory_clear(llama_get_memory(context), true);
            remembered.clear();
            return false;
        }
        remembered = tokens;
        return true;
    }

    llama_context * context;
    const llama_vocab * vocab;
    llama_sampler * sampler;
    std::vector<llama_token> remembered;
    size_t shared_last = 0;
    // HY_TIMING set: one line per request on the error stream, the prompt's tokens and the reply's, and how long each took.
    const bool timing = std::getenv("HY_TIMING") != nullptr;
};

// Windows can run a program whose window is not in front as background work, on the CPU's efficiency cores, and the user is
// watching a video. OpenSub asks the same for itself: its speech readings took 0.24 s instead of 0.12 s without it. This
// program translated as fast either way in replays; it asks anyway, since real use has a video in front. Windows only.
static void keep_full_speed() {
#ifdef _WIN32
    PROCESS_POWER_THROTTLING_STATE state = {};
    state.Version = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
    state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
    state.StateMask = 0;
    SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, &state, sizeof(state));
#endif
}

int main(int argc, char ** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: hy-translate <model.gguf> <threads>\n");
        return 2;
    }
    keep_full_speed();
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    const int threads = std::atoi(argv[2]);

    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(argv[1], model_params);
    if (model == nullptr) {
        std::fprintf(stderr, "could not load %s\n", argv[1]);
        return 1;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = CONTEXT_TOKENS;
    context_params.n_batch = CONTEXT_TOKENS;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    llama_context * context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        std::fprintf(stderr, "could not create the model context\n");
        return 1;
    }

    // Without a pool of its own, llama.cpp starts its threads afresh for every step, every word of a reply. On the tablet a
    // new thread starts on a slow core at a low clock and is gone before the system moves it: the threads sat on the slow
    // cores. Kept for the whole run, they are seen as busy and go to the fast ones.
    ggml_threadpool_params pool_params = ggml_threadpool_params_default(threads);
    // Waiting threads sleep rather than spin: as fast on the tablet (40 ms a word either way), and the cores stay free for speech recognition.
    pool_params.poll = 0;
    ggml_threadpool * pool = ggml_threadpool_new(&pool_params);
    llama_attach_threadpool(context, pool, pool);

    Session session(context, llama_model_get_vocab(model));
    std::string line;
    while (std::getline(std::cin, line)) {
        const size_t tab = line.find('\t');
        const size_t second = tab == std::string::npos ? tab : line.find('\t', tab + 1);
        const std::string english = line.substr(0, tab);
        const std::string shown = tab == std::string::npos ? "" : line.substr(tab + 1, second == std::string::npos ? second : second - tab - 1);
        const std::string before = second == std::string::npos ? "" : line.substr(second + 1);
        std::string reply = session.translate(english, shown, before);
        std::replace(reply.begin(), reply.end(), '\n', ' ');
        std::replace(reply.begin(), reply.end(), '\r', ' ');
        std::fwrite(reply.data(), 1, reply.size(), stdout);
        std::fputc('\n', stdout);
        std::fflush(stdout);
    }
    return 0;
}
