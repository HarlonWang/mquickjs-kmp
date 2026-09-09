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

kmpjs_engine *kmpjs_create(int32_t mem_bytes, void *user, kmpjs_host_fn host, kmpjs_log_fn log)
{
    kmpjs_engine *e;

    if (mem_bytes < KMP_MIN_MEM)
        return NULL;
    e = calloc(1, sizeof(*e));
    if (!e)
        return NULL;
    atomic_init(&e->state, KMP_IDLE);
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
    if (!e)
        return;
    JS_FreeContext(e->ctx);
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

static void publish(kmp_buf *b, const char **pstr, int32_t *plen)
{
    *pstr = b->data ? b->data : "";
    *plen = b->len;
}

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

/* Converts a JS value. Returns -1 with the exception left pending in ctx. */
static int value_to_out(JSContext *ctx, JSValue v, kmp_buf *sbuf, kmpjs_value *out)
{
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

/* The parser reads one byte past the input, so every source buffer handed to JS_Eval
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

void kmpjs_eval(kmpjs_engine *e, const char *code, int32_t code_len, const char *filename, kmpjs_value *out)
{
    JSValue r;
    int expected = KMP_IDLE;
    /* nested evaluations (from a host function) keep the outer state so an interrupt is never lost */
    int outermost = atomic_compare_exchange_strong(&e->state, &expected, KMP_RUNNING);

    r = parse_terminated(e->ctx, code, code_len, filename, JS_EVAL_RETVAL, 1);
    if (JS_IsException(r) || value_to_out(e->ctx, r, &e->out_str, out))
        exception_to_out(e, out);
    if (outermost)
        atomic_store(&e->state, KMP_IDLE);
}

int32_t kmpjs_define_function(kmpjs_engine *e, const char *name, int32_t fn_id, kmpjs_value *out)
{
    JSContext *ctx = e->ctx;
    JSGCRef f_ref;
    JSValue *pf, r;

    pf = JS_PushGCRef(ctx, &f_ref);
    *pf = JS_NewCFunctionParams(ctx, KMP_CFUNCTION_HOST, JS_NewInt32(ctx, fn_id));
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

static JSValue value_from_host(JSContext *ctx, const kmpjs_value *v)
{
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
    default:
        return throw_message(ctx, v->str, v->str_len);
    }
}

static JSValue js_kmp_host(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv, JSValue params)
{
    kmpjs_engine *e = JS_GetContextOpaque(ctx);
    int32_t fn_id = JS_VALUE_GET_INT(params);
    kmpjs_value *args = NULL;
    kmp_buf *bufs = NULL;
    kmpjs_value result;
    JSValue ret = JS_EXCEPTION;
    int i, rc;

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
        if (value_to_out(ctx, argv[i], &bufs[i], &args[i]))
            goto done;
    }
    memset(&result, 0, sizeof(result));
    rc = e->host(e->user, fn_id, args, argc, &result);
    if (rc != 0)
        ret = throw_message(ctx, result.str, result.str_len);
    else
        ret = value_from_host(ctx, &result);
    free((void *)result.str);
    free((void *)result.stack);
done:
    for (i = 0; i < argc && bufs; i++)
        buf_free(&bufs[i]);
    free(bufs);
    free(args);
    return ret;
}
