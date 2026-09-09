/* Handle-based C API over MicroQuickJS shared by the JNI and cinterop bindings.
   Design notes: docs/architecture.md */
#ifndef MQUICKJS_KMP_H
#define MQUICKJS_KMP_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KMPJS_ABI_VERSION 1

typedef struct kmpjs_engine kmpjs_engine;

enum {
    KMPJS_TAG_UNDEFINED = 0,
    KMPJS_TAG_NULL = 1,
    KMPJS_TAG_BOOL = 2,
    KMPJS_TAG_NUMBER = 3,
    KMPJS_TAG_STRING = 4,
    KMPJS_TAG_OBJECT = 5,    /* str = JSON text, or NULL when not serializable */
    KMPJS_TAG_EXCEPTION = 6, /* str = message, stack = JS stack trace or NULL */
};

typedef struct {
    int32_t tag;
    double num;          /* KMPJS_TAG_BOOL: 0 or 1, KMPJS_TAG_NUMBER: the value */
    const char *str;     /* UTF-8 (WTF-8), not NUL terminated */
    int32_t str_len;
    const char *stack;
    int32_t stack_len;
} kmpjs_value;

/* Host function callback. `args` is only valid during the call.
   Return 0 with *result filled, or non-zero with result->str holding an error message
   that is thrown as an Error. String payloads placed in *result must come from
   kmpjs_alloc(); the engine frees them. */
typedef int (*kmpjs_host_fn)(void *user, int32_t fn_id, const kmpjs_value *args,
                             int32_t argc, kmpjs_value *result);
typedef void (*kmpjs_log_fn)(void *user, const char *msg, int32_t len);

int32_t kmpjs_abi_version(void);

/* Returns NULL if the memory buffer cannot be allocated or is too small. */
kmpjs_engine *kmpjs_create(int32_t mem_bytes, void *user, kmpjs_host_fn host, kmpjs_log_fn log);
void kmpjs_destroy(kmpjs_engine *e);
void *kmpjs_get_user(kmpjs_engine *e);

/* String payloads in *out stay valid until the next kmpjs_* call on the same engine. */
void kmpjs_eval(kmpjs_engine *e, const char *code, int32_t code_len,
                const char *filename, kmpjs_value *out);

/* Defines global `name` as a host function dispatching to fn_id.
   Returns 0 on success, otherwise *out holds the exception. */
int32_t kmpjs_define_function(kmpjs_engine *e, const char *name, int32_t fn_id, kmpjs_value *out);

/* Safe to call from any thread while the engine is alive. */
void kmpjs_interrupt(kmpjs_engine *e);

char *kmpjs_alloc(int32_t len);
void kmpjs_free(void *p);

#ifdef __cplusplus
}
#endif

#endif /* MQUICKJS_KMP_H */
