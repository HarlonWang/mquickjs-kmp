#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdatomic.h>
#include <math.h>
#include <time.h>
#include <sys/time.h>

#include "cutils.h"
#include "mquickjs.h"
#include "mquickjs_kmp.h"

#define KMP_CFUNCTION_HOST (JS_CFUNCTION_USER + 0)
#define KMP_MIN_MEM 4096

enum { KMP_IDLE = 0, KMP_RUNNING = 1, KMP_INTERRUPTED = 2 };

typedef struct {
    char *data;
    int32_t len;
    int32_t cap;
} kmp_buf;

/* One slot per live ref. Slots are malloc'ed individually because the engine keeps
   their JSGCRef linked in an intrusive list and would break if they moved.
   A ref handle packs the slot index (low 32 bits) with a 32-bit generation (high bits)
   so a stale handle whose slot was reused is rejected instead of touching another object. */
typedef struct {
    JSGCRef gc;
    int32_t refcount;
    int32_t next_free;
    uint32_t gen;
} kmp_slot;

struct kmpjs_engine {
    JSContext *ctx;
    uint8_t *mem;
    void *user;
    kmpjs_host_fn host;
    kmpjs_log_fn log;
    atomic_int state; /* KMP_IDLE / KMP_RUNNING / KMP_INTERRUPTED */
    kmp_buf out_str;
    kmp_buf out_stack;
    kmp_buf log_line;
    kmp_slot **slots;
    int32_t slot_count;
    int32_t slot_cap;
    int32_t free_head; /* index of first free slot or -1 */
};

static void buf_reset(kmp_buf *b)
{
    b->len = 0;
}

static int buf_append(kmp_buf *b, const void *p, size_t n)
{
    if ((size_t)b->cap - b->len < n) {
        size_t cap = b->cap ? b->cap : 64;
        char *data;
        while (cap - b->len < n)
            cap *= 2;
        data = realloc(b->data, cap);
        if (!data)
            return -1;
        b->data = data;
        b->cap = (int32_t)cap;
    }
    memcpy(b->data + b->len, p, n);
    b->len += (int32_t)n;
    return 0;
}

static void buf_free(kmp_buf *b)
{
    free(b->data);
    b->data = NULL;
    b->len = b->cap = 0;
}

static void publish(kmp_buf *b, const char **pstr, int32_t *plen)
{
    *pstr = b->data ? b->data : "";
    *plen = b->len;
}

char *kmpjs_alloc(int32_t len)
{
    return malloc(len > 0 ? (size_t)len : 1);
}

void kmpjs_free(void *p)
{
    free(p);
}

int32_t kmpjs_abi_version(void)
{
    return KMPJS_ABI_VERSION;
}

static int64_t get_time_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + (ts.tv_nsec / 1000000);
}

static int64_t get_date_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + (tv.tv_usec / 1000);
}

/* Referenced by name from the generated stdlib tables, hence not static. */
JSValue js_date_constructor(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv)
{
    double val;
    argc &= ~FRAME_CF_CTOR;
    if (argc == 0) {
        val = (double)get_date_ms();
    } else if (argc == 1 && JS_IsNumber(ctx, argv[0])) {
        if (JS_ToNumber(ctx, &val, argv[0]))
            return JS_EXCEPTION;
    } else {
        return JS_ThrowTypeError(ctx, "unsupported Date() parameter");
    }
    return JS_NewDate(ctx, val);
}

JSValue js_date_now(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv)
{
    return JS_NewInt64(ctx, get_date_ms());
}

JSValue js_performance_now(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv)
{
    return JS_NewInt64(ctx, get_time_ms());
}

static void kmp_write_func(void *opaque, const void *buf, size_t buf_len)
{
    kmpjs_engine *e = opaque;
    buf_append(&e->log_line, buf, buf_len);
}

JSValue js_print(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv)
{
    kmpjs_engine *e = JS_GetContextOpaque(ctx);
    int i;

    buf_reset(&e->log_line);
    for (i = 0; i < argc; i++) {
        if (i != 0)
            buf_append(&e->log_line, " ", 1);
        if (JS_IsString(ctx, argv[i])) {
            JSCStringBuf sb;
            size_t len;
            const char *p = JS_ToCStringLen(ctx, &len, argv[i], &sb);
            if (!p)
                return JS_EXCEPTION;
            buf_append(&e->log_line, p, len);
        } else {
            JS_PrintValueF(ctx, argv[i], JS_DUMP_LONG);
        }
    }
    if (e->log)
        e->log(e->user, e->log_line.data ? e->log_line.data : "", e->log_line.len);
    return JS_UNDEFINED;
}

static JSValue js_kmp_host(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv, JSValue params);

#include "kmp_stdlib.h"

static int kmp_interrupt_handler(JSContext *ctx, void *opaque)
{
    kmpjs_engine *e = opaque;
    return atomic_load_explicit(&e->state, memory_order_relaxed) == KMP_INTERRUPTED;
}

/* ---- ref table ---- */

static int64_t slot_handle(int32_t idx, uint32_t gen)
{
    return (int64_t)(((uint64_t)gen << 32) | (uint32_t)(idx + 1));
}

static int32_t slot_index(int64_t ref)
{
    return (int32_t)((uint64_t)ref & 0xFFFFFFFFu) - 1;
}

static kmp_slot *slot_get(kmpjs_engine *e, int64_t ref)
{
    kmp_slot *s;
    int32_t idx = slot_index(ref);
    uint32_t gen = (uint32_t)((uint64_t)ref >> 32);
    if (ref <= 0 || idx < 0 || idx >= e->slot_count)
        return NULL;
    s = e->slots[idx];
    return (s->refcount > 0 && s->gen == gen) ? s : NULL;
}

static int64_t slot_new(kmpjs_engine *e, JSValue v)
{
    kmp_slot *s;
    int32_t idx;
    JSValue *pv;

    if (e->free_head >= 0) {
        idx = e->free_head;
        s = e->slots[idx];
        e->free_head = s->next_free;
    } else {
        if (e->slot_count == INT32_MAX)
            return 0;
        if (e->slot_count == e->slot_cap) {
            int32_t cap = e->slot_cap ? e->slot_cap * 2 : 16;
            kmp_slot **slots = realloc(e->slots, (size_t)cap * sizeof(*slots));
            if (!slots)
                return 0;
            e->slots = slots;
            e->slot_cap = cap;
        }
        s = calloc(1, sizeof(*s));
        if (!s)
            return 0;
        idx = e->slot_count++;
        e->slots[idx] = s;
    }
    s->refcount = 1;
    s->next_free = -1;
    pv = JS_AddGCRef(e->ctx, &s->gc);
    *pv = v;
    return slot_handle(idx, s->gen);
}

static void slot_free(kmpjs_engine *e, int64_t ref)
{
    int32_t idx = slot_index(ref);
    kmp_slot *s = e->slots[idx];
    JS_DeleteGCRef(e->ctx, &s->gc);
    s->gc.val = JS_UNDEFINED;
    s->refcount = 0;
    s->gen++;
    s->next_free = e->free_head;
    e->free_head = idx;
}

void kmpjs_ref_retain(kmpjs_engine *e, int64_t ref)
{
    kmp_slot *s = slot_get(e, ref);
    if (s)
        s->refcount++;
}

void kmpjs_ref_release(kmpjs_engine *e, int64_t ref)
{
    kmp_slot *s = slot_get(e, ref);
    if (s && --s->refcount == 0)
        slot_free(e, ref);
}

void kmpjs_get_stats(kmpjs_engine *e, kmpjs_stats *stats)
{
    int32_t i, live = 0;
    for (i = 0; i < e->slot_count; i++) {
        if (e->slots[i]->refcount > 0)
            live++;
    }
    stats->live_refs = live;
    stats->ref_slots = e->slot_count;
}

void kmpjs_dump_memory(kmpjs_engine *e, kmpjs_value *out)
{
    /* JS_DumpMemory writes through the log func, which lands in log_line */
    buf_reset(&e->log_line);
    JS_DumpMemory(e->ctx, 0);
    buf_reset(&e->out_str);
    buf_append(&e->out_str, e->log_line.data ? e->log_line.data : "", (size_t)e->log_line.len);
    memset(out, 0, sizeof(*out));
    out->tag = KMPJS_TAG_STRING;
    publish(&e->out_str, &out->str, &out->str_len);
}

/* ---- engine lifecycle ---- */

kmpjs_engine *kmpjs_create(int32_t mem_bytes, void *user, kmpjs_host_fn host, kmpjs_log_fn log)
{
    kmpjs_engine *e;

    if (mem_bytes < KMP_MIN_MEM)
        return NULL;
    e = calloc(1, sizeof(*e));
    if (!e)
        return NULL;
    atomic_init(&e->state, KMP_IDLE);
    e->free_head = -1;
    e->mem = malloc((size_t)mem_bytes);
    if (!e->mem) {
        free(e);
        return NULL;
    }
    e->user = user;
    e->host = host;
    e->log = log;
    e->ctx = JS_NewContext(e->mem, (size_t)mem_bytes, &js_stdlib);
    if (!e->ctx) {
        free(e->mem);
        free(e);
        return NULL;
    }
    JS_SetContextOpaque(e->ctx, e);
    JS_SetLogFunc(e->ctx, kmp_write_func);
    JS_SetInterruptHandler(e->ctx, kmp_interrupt_handler);
    return e;
}

void kmpjs_destroy(kmpjs_engine *e)
{
    int32_t i;
    if (!e)
        return;
    /* unlink live slots from the engine's GC ref list before the context tears down */
    for (i = 0; i < e->slot_count; i++) {
        if (e->slots[i]->refcount > 0)
            JS_DeleteGCRef(e->ctx, &e->slots[i]->gc);
    }
    JS_FreeContext(e->ctx);
    for (i = 0; i < e->slot_count; i++)
        free(e->slots[i]);
    free(e->slots);
    buf_free(&e->out_str);
    buf_free(&e->out_stack);
    buf_free(&e->log_line);
    free(e->mem);
    free(e);
}

void *kmpjs_get_user(kmpjs_engine *e)
{
    return e->user;
}

void kmpjs_interrupt(kmpjs_engine *e)
{
    int expected = KMP_RUNNING;
    atomic_compare_exchange_strong(&e->state, &expected, KMP_INTERRUPTED);
}

/* Nested runs (a host function calling back into the engine) keep the outer state so an
   interrupt is never lost; only the outermost run returns the engine to idle. */
static int run_begin(kmpjs_engine *e)
{
    int expected = KMP_IDLE;
    return atomic_compare_exchange_strong(&e->state, &expected, KMP_RUNNING);
}

static void run_end(kmpjs_engine *e, int outermost)
{
    if (outermost)
        atomic_store(&e->state, KMP_IDLE);
}

/* ---- value conversion ---- */

static int copy_js_string(JSContext *ctx, JSValue str, kmp_buf *b)
{
    JSCStringBuf sb;
    size_t len;
    const char *p = JS_ToCStringLen(ctx, &len, str, &sb);
    if (!p)
        return -1;
    buf_reset(b);
    return buf_append(b, p, len);
}

/* JSON.stringify(*pval). *pval must be GC-rooted by the caller. The returned string
   must be consumed before the next allocation. */
static JSValue json_stringify(JSContext *ctx, JSValue *pval)
{
    JSGCRef json_ref;
    JSValue *pjson, func, res;

    pjson = JS_PushGCRef(ctx, &json_ref);
    *pjson = JS_GetPropertyStr(ctx, JS_GetGlobalObject(ctx), "JSON");
    if (JS_IsException(*pjson))
        goto fail;
    func = JS_GetPropertyStr(ctx, *pjson, "stringify");
    if (JS_IsException(func))
        goto fail;
    if (JS_StackCheck(ctx, 3))
        goto fail;
    JS_PushArg(ctx, *pval);
    JS_PushArg(ctx, func);
    JS_PushArg(ctx, *pjson);
    res = JS_Call(ctx, 1);
    JS_PopGCRef(ctx, &json_ref);
    return res;
fail:
    JS_PopGCRef(ctx, &json_ref);
    return JS_EXCEPTION;
}

static int object_kind(JSContext *ctx, JSValue v)
{
    int kind = 0;
    if (JS_IsFunction(ctx, v))
        kind |= KMPJS_REF_FUNCTION;
    if (JS_IsArray(ctx, v))
        kind |= KMPJS_REF_ARRAY;
    return kind;
}

/* Converts a JS value. Returns -1 with the exception left pending in ctx. */
static int value_to_out(kmpjs_engine *e, JSValue v, kmp_buf *sbuf, int32_t flags, kmpjs_value *out)
{
    JSContext *ctx = e->ctx;

    memset(out, 0, sizeof(*out));
    if (JS_IsUndefined(v)) {
        out->tag = KMPJS_TAG_UNDEFINED;
    } else if (JS_IsNull(v)) {
        out->tag = KMPJS_TAG_NULL;
    } else if (JS_IsBool(v)) {
        out->tag = KMPJS_TAG_BOOL;
        out->num = (v == JS_TRUE);
    } else if (JS_IsNumber(ctx, v)) {
        out->tag = KMPJS_TAG_NUMBER;
        if (JS_ToNumber(ctx, &out->num, v))
            return -1;
    } else if (JS_IsString(ctx, v)) {
        out->tag = KMPJS_TAG_STRING;
        if (copy_js_string(ctx, v, sbuf))
            return -1;
        publish(sbuf, &out->str, &out->str_len);
    } else if (flags & KMPJS_FLAG_REF_OBJECTS) {
        out->tag = KMPJS_TAG_REF;
        out->num = object_kind(ctx, v);
        out->ref = slot_new(e, v);
        if (!out->ref) {
            JS_ThrowOutOfMemory(ctx);
            return -1;
        }
    } else if (JS_IsFunction(ctx, v)) {
        out->tag = KMPJS_TAG_OBJECT;
    } else {
        JSGCRef v_ref;
        JSValue *pv, json;
        pv = JS_PushGCRef(ctx, &v_ref);
        *pv = v;
        json = json_stringify(ctx, pv);
        JS_PopGCRef(ctx, &v_ref);
        if (JS_IsException(json))
            return -1;
        out->tag = KMPJS_TAG_OBJECT;
        if (!JS_IsUndefined(json)) {
            if (copy_js_string(ctx, json, sbuf))
                return -1;
            publish(sbuf, &out->str, &out->str_len);
        }
    }
    return 0;
}

static void exception_to_out(kmpjs_engine *e, kmpjs_value *out)
{
    JSContext *ctx = e->ctx;
    JSGCRef exc_ref;
    JSValue *pexc, s;
    static const char unknown[] = "unknown exception";

    memset(out, 0, sizeof(*out));
    out->tag = KMPJS_TAG_EXCEPTION;
    pexc = JS_PushGCRef(ctx, &exc_ref);
    *pexc = JS_GetException(ctx);

    s = JS_ToString(ctx, *pexc);
    if (JS_IsException(s) || copy_js_string(ctx, s, &e->out_str)) {
        JS_GetException(ctx);
        buf_reset(&e->out_str);
        buf_append(&e->out_str, unknown, sizeof(unknown) - 1);
    }
    publish(&e->out_str, &out->str, &out->str_len);

    if (JS_IsError(ctx, *pexc)) {
        s = JS_GetPropertyStr(ctx, *pexc, "stack");
        if (JS_IsException(s)) {
            JS_GetException(ctx);
        } else if (JS_IsString(ctx, s) && !copy_js_string(ctx, s, &e->out_stack)) {
            publish(&e->out_stack, &out->stack, &out->stack_len);
        }
    }
    JS_PopGCRef(ctx, &exc_ref);
}

/* An error raised by the shim itself, with no JS exception pending. */
static int32_t fail_message(kmpjs_engine *e, kmpjs_value *out, const char *msg)
{
    memset(out, 0, sizeof(*out));
    out->tag = KMPJS_TAG_EXCEPTION;
    buf_reset(&e->out_str);
    buf_append(&e->out_str, msg, strlen(msg));
    publish(&e->out_str, &out->str, &out->str_len);
    return -1;
}

/* The parser reads one byte past the input, so every source buffer handed to the engine
   must be NUL terminated (mqjs.c does the same after load_file). */
static JSValue parse_terminated(JSContext *ctx, const char *code, int32_t len, const char *filename, int flags, int run)
{
    char *buf = malloc((size_t)len + 1);
    JSValue r;
    if (!buf)
        return JS_ThrowOutOfMemory(ctx);
    memcpy(buf, code ? code : "", (size_t)len);
    buf[len] = '\0';
    r = run ? JS_Eval(ctx, buf, (size_t)len, filename, flags)
            : JS_Parse(ctx, buf, (size_t)len, filename, flags);
    free(buf);
    return r;
}

static JSValue throw_message(JSContext *ctx, const char *p, int32_t len)
{
    char *msg = malloc((size_t)len + 1);
    JSValue r;
    if (!msg)
        return JS_ThrowOutOfMemory(ctx);
    memcpy(msg, p ? p : "", (size_t)len);
    msg[len] = '\0';
    r = JS_ThrowError(ctx, JS_CLASS_ERROR, "%s", msg);
    free(msg);
    return r;
}

static JSValue value_from_host(kmpjs_engine *e, const kmpjs_value *v)
{
    JSContext *ctx = e->ctx;
    switch (v->tag) {
    case KMPJS_TAG_UNDEFINED:
        return JS_UNDEFINED;
    case KMPJS_TAG_NULL:
        return JS_NULL;
    case KMPJS_TAG_BOOL:
        return JS_NewBool(v->num != 0);
    case KMPJS_TAG_NUMBER: {
        double d = v->num;
        int32_t i = (int32_t)d;
        if ((double)i == d && !(d == 0 && signbit(d)))
            return JS_NewInt32(ctx, i);
        return JS_NewFloat64(ctx, d);
    }
    case KMPJS_TAG_STRING:
        return JS_NewStringLen(ctx, v->str ? v->str : "", (size_t)v->str_len);
    case KMPJS_TAG_OBJECT:
        if (!v->str)
            return JS_UNDEFINED;
        return parse_terminated(ctx, v->str, v->str_len, "<host>", JS_EVAL_JSON, 0);
    case KMPJS_TAG_REF: {
        kmp_slot *s = slot_get(e, v->ref);
        if (!s)
            return JS_ThrowTypeError(ctx, "invalid or released ref");
        return s->gc.val;
    }
    default:
        return throw_message(ctx, v->str, v->str_len);
    }
}

/* ---- evaluation ---- */

void kmpjs_eval(kmpjs_engine *e, const char *code, int32_t code_len, const char *filename, int32_t flags, kmpjs_value *out)
{
    JSValue r;
    int outermost = run_begin(e);

    r = parse_terminated(e->ctx, code, code_len, filename, JS_EVAL_RETVAL, 1);
    if (JS_IsException(r) || value_to_out(e, r, &e->out_str, flags, out))
        exception_to_out(e, out);
    run_end(e, outermost);
}

int32_t kmpjs_define_function(kmpjs_engine *e, const char *name, int32_t fn_id, int32_t flags, kmpjs_value *out)
{
    JSContext *ctx = e->ctx;
    JSGCRef f_ref;
    JSValue *pf, r;
    int32_t params = (fn_id << 1) | (flags & KMPJS_FLAG_REF_OBJECTS);

    pf = JS_PushGCRef(ctx, &f_ref);
    *pf = JS_NewCFunctionParams(ctx, KMP_CFUNCTION_HOST, JS_NewInt32(ctx, params));
    if (JS_IsException(*pf)) {
        JS_PopGCRef(ctx, &f_ref);
        exception_to_out(e, out);
        return -1;
    }
    r = JS_SetPropertyStr(ctx, JS_GetGlobalObject(ctx), name, *pf);
    JS_PopGCRef(ctx, &f_ref);
    if (JS_IsException(r)) {
        exception_to_out(e, out);
        return -1;
    }
    memset(out, 0, sizeof(*out));
    return 0;
}

static JSValue js_kmp_host(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv, JSValue params)
{
    kmpjs_engine *e = JS_GetContextOpaque(ctx);
    int32_t packed = JS_VALUE_GET_INT(params);
    int32_t fn_id = packed >> 1;
    int32_t flags = packed & KMPJS_FLAG_REF_OBJECTS;
    kmpjs_value *args = NULL;
    kmp_buf *bufs = NULL;
    kmpjs_value result;
    JSValue ret = JS_EXCEPTION;
    int i, rc, converted = 0;

    if (!e->host)
        return JS_ThrowInternalError(ctx, "no host function handler");
    if (argc > 0) {
        args = calloc((size_t)argc, sizeof(*args));
        bufs = calloc((size_t)argc, sizeof(*bufs));
        if (!args || !bufs) {
            ret = JS_ThrowOutOfMemory(ctx);
            goto done;
        }
    }
    /* argv lives on the VM stack, which the GC updates in place, so re-read it per argument */
    for (i = 0; i < argc; i++) {
        if (value_to_out(e, argv[i], &bufs[i], flags, &args[i]))
            goto done;
        converted++;
    }
    memset(&result, 0, sizeof(result));
    rc = e->host(e->user, fn_id, args, argc, &result);
    if (rc != 0)
        ret = throw_message(ctx, result.str, result.str_len);
    else
        ret = value_from_host(e, &result);
    free((void *)result.str);
    free((void *)result.stack);
done:
    /* refs handed to the host for this call are transient unless the host retained them */
    for (i = 0; i < converted; i++) {
        if (args[i].tag == KMPJS_TAG_REF)
            kmpjs_ref_release(e, args[i].ref);
    }
    for (i = 0; i < argc && bufs; i++)
        buf_free(&bufs[i]);
    free(bufs);
    free(args);
    return ret;
}

/* ---- ref operations ---- */

/* Property access and JSON.stringify can run script (accessors, toJSON), so every ref
   operation enters the running state like kmpjs_eval does to stay interruptible. */
static int32_t finish(kmpjs_engine *e, int outermost, JSValue v, int32_t flags, kmpjs_value *out)
{
    int32_t rc = 0;
    if (JS_IsException(v) || value_to_out(e, v, &e->out_str, flags, out)) {
        exception_to_out(e, out);
        rc = -1;
    }
    run_end(e, outermost);
    return rc;
}

int32_t kmpjs_ref_get(kmpjs_engine *e, int64_t ref, const char *name, int32_t flags, kmpjs_value *out)
{
    kmp_slot *s = slot_get(e, ref);
    int outermost;
    if (!s)
        return fail_message(e, out, "invalid or released ref");
    outermost = run_begin(e);
    return finish(e, outermost, JS_GetPropertyStr(e->ctx, s->gc.val, name), flags, out);
}

int32_t kmpjs_ref_get_index(kmpjs_engine *e, int64_t ref, int32_t index, int32_t flags, kmpjs_value *out)
{
    kmp_slot *s = slot_get(e, ref);
    int outermost;
    if (!s)
        return fail_message(e, out, "invalid or released ref");
    if (index < 0)
        return fail_message(e, out, "negative index");
    outermost = run_begin(e);
    return finish(e, outermost, JS_GetPropertyUint32(e->ctx, s->gc.val, (uint32_t)index), flags, out);
}

int32_t kmpjs_ref_set(kmpjs_engine *e, int64_t ref, const char *name, const kmpjs_value *value, kmpjs_value *out)
{
    JSContext *ctx = e->ctx;
    kmp_slot *s = slot_get(e, ref);
    JSGCRef v_ref;
    JSValue *pv, r;
    int outermost;
    if (!s)
        return fail_message(e, out, "invalid or released ref");
    outermost = run_begin(e);
    /* convert first: JSON parsing allocates and may move the target object */
    pv = JS_PushGCRef(ctx, &v_ref);
    *pv = value_from_host(e, value);
    if (JS_IsException(*pv)) {
        JS_PopGCRef(ctx, &v_ref);
        exception_to_out(e, out);
        run_end(e, outermost);
        return -1;
    }
    r = JS_SetPropertyStr(ctx, s->gc.val, name, *pv);
    JS_PopGCRef(ctx, &v_ref);
    if (JS_IsException(r)) {
        exception_to_out(e, out);
        run_end(e, outermost);
        return -1;
    }
    memset(out, 0, sizeof(*out));
    run_end(e, outermost);
    return 0;
}

int32_t kmpjs_ref_call(kmpjs_engine *e, int64_t ref, int64_t this_ref, const kmpjs_value *args,
                       int32_t argc, int32_t flags, kmpjs_value *out)
{
    JSContext *ctx = e->ctx;
    kmp_slot *fs = slot_get(e, ref);
    kmp_slot *ts = NULL;
    JSGCRef *refs = NULL;
    JSValue r;
    int i, pushed = 0, outermost, rc = -1;

    if (!fs)
        return fail_message(e, out, "invalid or released ref");
    if (!JS_IsFunction(ctx, fs->gc.val))
        return fail_message(e, out, "ref is not a function");
    if (this_ref) {
        ts = slot_get(e, this_ref);
        if (!ts)
            return fail_message(e, out, "invalid or released this ref");
    }
    outermost = run_begin(e);
    if (argc > 0) {
        refs = calloc((size_t)argc, sizeof(*refs));
        if (!refs) {
            fail_message(e, out, "out of memory");
            goto done;
        }
    }
    /* root every converted argument before touching the VM stack: a later conversion may allocate */
    for (i = 0; i < argc; i++) {
        JSValue *pv = JS_PushGCRef(ctx, &refs[i]);
        pushed++;
        *pv = value_from_host(e, &args[i]);
        if (JS_IsException(*pv)) {
            exception_to_out(e, out);
            goto done;
        }
    }
    if (JS_StackCheck(ctx, (uint32_t)argc + 2)) {
        exception_to_out(e, out);
        goto done;
    }
    for (i = argc - 1; i >= 0; i--)
        JS_PushArg(ctx, refs[i].val);
    JS_PushArg(ctx, fs->gc.val);
    JS_PushArg(ctx, ts ? ts->gc.val : JS_UNDEFINED);
    r = JS_Call(ctx, argc);
    if (JS_IsException(r) || value_to_out(e, r, &e->out_str, flags, out)) {
        exception_to_out(e, out);
        goto done;
    }
    rc = 0;
done:
    for (i = pushed - 1; i >= 0; i--)
        JS_PopGCRef(ctx, &refs[i]);
    free(refs);
    run_end(e, outermost);
    return rc;
}

int32_t kmpjs_ref_to_json(kmpjs_engine *e, int64_t ref, kmpjs_value *out)
{
    kmp_slot *s = slot_get(e, ref);
    JSValue json;
    int outermost, rc = 0;
    if (!s)
        return fail_message(e, out, "invalid or released ref");
    memset(out, 0, sizeof(*out));
    out->tag = KMPJS_TAG_OBJECT;
    if (JS_IsFunction(e->ctx, s->gc.val))
        return 0;
    outermost = run_begin(e);
    json = json_stringify(e->ctx, &s->gc.val);
    if (JS_IsException(json) || (!JS_IsUndefined(json) && copy_js_string(e->ctx, json, &e->out_str))) {
        exception_to_out(e, out);
        rc = -1;
    } else if (!JS_IsUndefined(json)) {
        publish(&e->out_str, &out->str, &out->str_len);
    }
    run_end(e, outermost);
    return rc;
}
