#pragma once
#ifdef __cplusplus
extern "C" {
#endif
typedef struct native_handle {
    int version;
    int numFds;
    int numInts;
    int data[0];
} native_handle_t;
static inline native_handle_t* native_handle_create(int numFds, int numInts) { return 0; }
static inline int native_handle_delete(native_handle_t* h) { return 0; }
static inline int native_handle_close(const native_handle_t* h) { return 0; }
#ifdef __cplusplus
}
#endif
