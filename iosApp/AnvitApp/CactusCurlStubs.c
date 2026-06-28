#include <stddef.h>

// Cactus includes optional cloud and telemetry objects that reference libcurl.
// Anvit disables both features, and iOS does not expose a public libcurl SDK.
typedef void CURL;
typedef int CURLcode;
typedef int CURLoption;
typedef int CURLINFO;

struct curl_slist {
    char *data;
    struct curl_slist *next;
};

CURLcode curl_global_init(long flags) {
    (void)flags;
    return 0;
}

void curl_global_cleanup(void) {}

CURL *curl_easy_init(void) {
    return NULL;
}

void curl_easy_cleanup(CURL *handle) {
    (void)handle;
}

CURLcode curl_easy_setopt(CURL *handle, CURLoption option, ...) {
    (void)handle;
    (void)option;
    return 2;
}

CURLcode curl_easy_getinfo(CURL *handle, CURLINFO info, ...) {
    (void)handle;
    (void)info;
    return 2;
}

CURLcode curl_easy_perform(CURL *handle) {
    (void)handle;
    return 2;
}

const char *curl_easy_strerror(CURLcode code) {
    (void)code;
    return "Network access is disabled in Anvit";
}

struct curl_slist *curl_slist_append(struct curl_slist *list, const char *value) {
    (void)list;
    (void)value;
    return NULL;
}

void curl_slist_free_all(struct curl_slist *list) {
    (void)list;
}
