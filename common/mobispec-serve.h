#pragma once

// Request loop for the Android demo's agent (examples/mobispec-demo-android). Inert unless MOBISPEC_SERVE
// names a file or FIFO: the driver then loads its contexts once and runs one request per line,
//   <id> TAB <prompt file> TAB <image file, may be empty> TAB <n_predict>
// until end of input or a line "quit". "-" or "/dev/stdin" is the process's own stdin, which under adb shell is a
// socket that cannot be reopened by path. The prompt file is read the way -f reads it.

#include "common.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

struct mobispec_serve {
    FILE * f      = nullptr;
    bool   owned  = false;
    bool   failed = false;   // asked to serve but the request source or a request was unusable
    int    id     = -1;

    mobispec_serve() {
        const char * path = getenv("MOBISPEC_SERVE");
        if (!path || !*path) {
            return;
        }
        owned = strcmp(path, "-") != 0 && strcmp(path, "/dev/stdin") != 0;
        f = owned ? fopen(path, "r") : stdin;
        if (!f) {
            fprintf(stderr, "[SERVE] cannot open %s\n", path);
            failed = true;
        }
    }

    ~mobispec_serve() {
        if (f && owned) {
            fclose(f);
        }
    }

    mobispec_serve(const mobispec_serve &) = delete;
    mobispec_serve & operator=(const mobispec_serve &) = delete;

    bool on() const { return f != nullptr; }

    // Blocks for the next request and puts it into params; false at the end of input or on a bad request.
    bool next(common_params & params) {
        if (!f) {
            return false;
        }
        std::string line;
        for (int c = fgetc(f); c != EOF && c != '\n'; c = fgetc(f)) {
            line += (char) c;
        }
        if (line.empty() || line == "quit") {
            return false;
        }
        std::vector<std::string> fields;
        for (size_t start = 0;;) {
            const size_t tab = line.find('\t', start);
            fields.push_back(line.substr(start, tab == std::string::npos ? std::string::npos : tab - start));
            if (tab == std::string::npos) {
                break;
            }
            start = tab + 1;
        }
        std::ifstream prompt(fields.size() == 4 ? fields[1] : std::string());
        if (fields.size() != 4 || !prompt) {
            fprintf(stderr, "[SERVE] bad request: %s\n", line.c_str());
            failed = true;
            return false;
        }
        id = atoi(fields[0].c_str());
        params.prompt.assign(std::istreambuf_iterator<char>(prompt), std::istreambuf_iterator<char>());
        if (!params.prompt.empty() && params.prompt.back() == '\n') {
            params.prompt.pop_back();
        }
        params.prompt_file = fields[1];
        params.image.clear();
        if (!fields[2].empty()) {
            params.image.push_back(fields[2]);
        }
        params.n_predict = atoi(fields[3].c_str());
        return true;
    }
};
