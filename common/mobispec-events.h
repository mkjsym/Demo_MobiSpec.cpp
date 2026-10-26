#pragma once

// JSON-lines event stream for the Android demo app (examples/mobispec-demo-android).
// Inert unless MOBISPEC_EVENTS names a file or FIFO; the app opens the FIFO for reading before it
// starts the process, so the blocking open below returns at once.

#include "llama.h"

#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <string>
#include <utility>
#include <vector>

struct mobispec_events {
    FILE *  f     = nullptr;
    int64_t t0_us = now_us();
    bool    tree  = false;   // MOBISPEC_EVENTS_TREE=1: also send the verified tree of every step

    explicit mobispec_events(const char * bin) {
        const char * path = getenv("MOBISPEC_EVENTS");
        if (!path || !*path) {
            return;
        }
        signal(SIGPIPE, SIG_IGN);   // a closed reader turns into a write error, not a dead process
        f = fopen(path, "w");
        if (!f) {
            fprintf(stderr, "[EVENTS] cannot open %s, events disabled\n", path);
            return;
        }
        tree = getenv("MOBISPEC_EVENTS_TREE") && atoi(getenv("MOBISPEC_EVENTS_TREE")) == 1;
        std::string name = bin ? bin : "";
        const size_t slash = name.find_last_of('/');
        if (slash != std::string::npos) {
            name = name.substr(slash + 1);
        }
        fprintf(f, "{\"ev\":\"start\",\"t_us\":0,\"bin\":\"%s\"}\n", name.c_str());
        flush();
    }

    ~mobispec_events() {
        if (f) {
            fclose(f);
        }
    }

    mobispec_events(const mobispec_events &) = delete;
    mobispec_events & operator=(const mobispec_events &) = delete;

    bool on() const { return f != nullptr; }

    static int64_t now_us() {
        return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    void mark(const char * ev) {
        if (!f) {
            return;
        }
        fprintf(f, "{\"ev\":\"%s\",\"t_us\":%lld}\n", ev, (long long) (now_us() - t0_us));
        flush();
    }

    // Start of one served request (mobispec-serve.h); every event up to its "done" belongs to it.
    void request(int id) {
        if (!f) {
            return;
        }
        fprintf(f, "{\"ev\":\"request\",\"t_us\":%lld,\"id\":%d}\n", (long long) (now_us() - t0_us), id);
        flush();
    }

    // Tokens committed by one verify step (or one AR step). Text goes out as raw bytes in base64:
    // a multi-byte character can straddle two tokens, so the app reassembles UTF-8 itself.
    void step(const llama_vocab * vocab, const llama_token * ids, size_t n,
              const std::vector<std::pair<int, llama_token>> * nodes = nullptr,
              const std::vector<int> * accepted = nullptr) {
        if (!f || n == 0) {
            return;
        }
        std::string bytes;
        std::vector<int> lens(n, 0);
        char buf[256];
        for (size_t i = 0; i < n; ++i) {
            const int len = llama_token_to_piece(vocab, ids[i], buf, sizeof(buf), 0, false);
            if (len > 0) {
                bytes.append(buf, len);
                lens[i] = len;
            }
        }
        fprintf(f, "{\"ev\":\"step\",\"t_us\":%lld,\"n\":%zu,\"ids\":[", (long long) (now_us() - t0_us), n);
        for (size_t i = 0; i < n; ++i) {
            fprintf(f, i ? ",%d" : "%d", ids[i]);
        }
        fputs("],\"lens\":[", f);
        for (size_t i = 0; i < n; ++i) {
            fprintf(f, i ? ",%d" : "%d", lens[i]);
        }
        fprintf(f, "],\"b64\":\"%s\"", base64(bytes).c_str());
        if (tree && nodes) {
            fputs(",\"tree\":[", f);
            for (size_t i = 0; i < nodes->size(); ++i) {
                fprintf(f, i ? ",[%d,%d]" : "[%d,%d]", (*nodes)[i].first, (*nodes)[i].second);
            }
            fputs("],\"path\":[", f);
            for (size_t i = 0; accepted && i < accepted->size(); ++i) {
                fprintf(f, i ? ",%d" : "%d", (*accepted)[i]);
            }
            fputc(']', f);
        }
        fputs("}\n", f);
        flush();
    }

    // Final numbers exactly as the binary computes them, so the app never re-derives a metric.
    void done(std::initializer_list<std::pair<const char *, double>> fields) {
        if (!f) {
            return;
        }
        fprintf(f, "{\"ev\":\"done\",\"t_us\":%lld", (long long) (now_us() - t0_us));
        for (const auto & kv : fields) {
            fprintf(f, ",\"%s\":%.10g", kv.first, kv.second);
        }
        fputs("}\n", f);
        flush();
    }

private:
    void flush() {
        if (fflush(f) != 0 || ferror(f)) {
            fclose(f);
            f = nullptr;
        }
    }

    static std::string base64(const std::string & in) {
        static const char * tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        std::string out;
        out.reserve((in.size() + 2) / 3 * 4);
        size_t i = 0;
        for (; i + 2 < in.size(); i += 3) {
            const uint32_t v = (uint8_t) in[i] << 16 | (uint8_t) in[i + 1] << 8 | (uint8_t) in[i + 2];
            out += tbl[v >> 18 & 63]; out += tbl[v >> 12 & 63]; out += tbl[v >> 6 & 63]; out += tbl[v & 63];
        }
        if (i + 1 == in.size()) {
            const uint32_t v = (uint8_t) in[i] << 16;
            out += tbl[v >> 18 & 63]; out += tbl[v >> 12 & 63]; out += "==";
        } else if (i + 2 == in.size()) {
            const uint32_t v = (uint8_t) in[i] << 16 | (uint8_t) in[i + 1] << 8;
            out += tbl[v >> 18 & 63]; out += tbl[v >> 12 & 63]; out += tbl[v >> 6 & 63]; out += '=';
        }
        return out;
    }
};
