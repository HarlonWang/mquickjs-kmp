/* Handle-based C API over MicroQuickJS shared by the JNI and cinterop bindings.
   Design notes: docs/architecture.md */
#ifndef MQUICKJS_KMP_H
#define MQUICKJS_KMP_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KMPJS_ABI_VERSION 4

typedef struct kmpjs_engine kmpjs_engine;

enum {
    KMPJS_TAG_UNDEFINED = 0,
    KMPJS_TAG_NULL = 1,
    KMPJS_TAG_BOOL = 2,
    KMPJS_TAG_NUMBER = 3,
    KMPJS_TAG_STRING = 4,
    KMPJS_TAG_OBJECT = 5,    /* str = JSON text, or NULL when not serializable */
    KMPJS_TAG_EXCEPTION = 6, /* str = message, stack = JS stack trace or NULL */
    KMPJS_TAG_REF = 7,       /* ref = 64-bit handle (slot index | generation), num = KMPJS_REF_* kind bits */
};

/* KMPJS_TAG_REF kind bits carried in kmpjs_value.num */
enum {
    KMPJS_REF_FUNCTION = 1,
    KMPJS_REF_ARRAY = 2,
};

/* flags for kmpjs_eval / kmpjs_define_function / kmpjs_ref_* */
enum {
    KMPJS_FLAG_REF_OBJECTS = 1, /* hand objects out as KMPJS_TAG_REF instead of JSON */
};

typedef struct {
    int32_t tag;
    int32_t reserved;
    int64_t ref;
    double num;          /* KMPJS_TAG_BOOL: 0 or 1, KMPJS_TAG_NUMBER: the value, KMPJS_TAG_REF: kind bits */
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
                const char *filename, int32_t flags, kmpjs_value *out);

/* Defines global `name` as a host function dispatching to fn_id. With KMPJS_FLAG_REF_OBJECTS the
   host receives object arguments as refs that are released after the call unless retained.
   Returns 0 on success, otherwise *out holds the exception. */
int32_t kmpjs_define_function(kmpjs_engine *e, const char *name, int32_t fn_id, int32_t flags, kmpjs_value *out);

/* Refs are reference counted handles to JS objects, valid until released or the engine is destroyed.
   All kmpjs_ref_* calls return 0 on success or -1 with *out holding the exception. */
void kmpjs_ref_retain(kmpjs_engine *e, int64_t ref);
void kmpjs_ref_release(kmpjs_engine *e, int64_t ref);
int32_t kmpjs_ref_get(kmpjs_engine *e, int64_t ref, const char *name, int32_t flags, kmpjs_value *out);
int32_t kmpjs_ref_get_index(kmpjs_engine *e, int64_t ref, int32_t index, int32_t flags, kmpjs_value *out);
int32_t kmpjs_ref_set(kmpjs_engine *e, int64_t ref, const char *name, const kmpjs_value *value, kmpjs_value *out);
/* Calls the function behind `ref` with `this_ref` (0 for undefined) and `args`. */
int32_t kmpjs_ref_call(kmpjs_engine *e, int64_t ref, int64_t this_ref, const kmpjs_value *args,
                       int32_t argc, int32_t flags, kmpjs_value *out);
int32_t kmpjs_ref_to_json(kmpjs_engine *e, int64_t ref, kmpjs_value *out);

/* Safe to call from any thread while the engine is alive. Stops the evaluation in
   progress; a call while no evaluation runs is a no-op. */
void kmpjs_interrupt(kmpjs_engine *e);

/* ---- precompiled bytecode ----
   Bytecode is bound to the exact engine (native/UPSTREAM commit) and word size that produced it;
   kmpjs_load_bytecode rejects anything else with a clear message. It is not validated beyond that. */

#define KMPJS_BYTECODE_HEADER_SIZE 52

enum {
    KMPJS_COMPILE_STRIP_COLUMNS = 1, /* drop column numbers from debug info to save space */
};

int32_t kmpjs_word_size(void); /* 32 or 64: the bytecode flavour this engine build runs */

/* Compiles `code` for `word_size` (a 64-bit engine can emit both, a 32-bit one only 32).
   On success returns 0 with out->str/str_len holding the bytecode, allocated with kmpjs_alloc
   and owned by the caller; on failure returns -1 with out->str (also caller-owned) holding the message. */
int32_t kmpjs_compile(const char *code, int32_t code_len, const char *filename,
                      int32_t word_size, int32_t flags, kmpjs_value *out);

/* Loads bytecode into an engine that has not defined any script or host function yet, returning
   a ref to the program (0 on success, -1 with *out holding the error). The engine keeps its own
   copy of the bytes for its whole lifetime. The engine accepts 1 program(s) (upstream limit). */
int32_t kmpjs_load_bytecode(kmpjs_engine *e, const uint8_t *buf, int32_t len, kmpjs_value *out);
/* Runs a program loaded by kmpjs_load_bytecode; result semantics match kmpjs_eval. */
int32_t kmpjs_run_program(kmpjs_engine *e, int64_t ref, int32_t flags, kmpjs_value *out);

typedef struct {
    int32_t live_refs;  /* outstanding releases: every retain adds one */
    int32_t ref_slots;  /* slots allocated so far (live + reusable) */
} kmpjs_stats;

void kmpjs_get_stats(kmpjs_engine *e, kmpjs_stats *stats);
/* Text summary of the engine heap (JS_DumpMemory) in out->str; diagnostics only. */
void kmpjs_dump_memory(kmpjs_engine *e, kmpjs_value *out);

char *kmpjs_alloc(int32_t len);
void kmpjs_free(void *p);

#ifdef __cplusplus
}
#endif

#endif /* MQUICKJS_KMP_H */
