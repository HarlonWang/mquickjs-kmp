/* Host-side checks of the shim, run with DEBUG_GC + ASan so every allocation moves objects.
   Exit status is the number of failed checks. */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "mquickjs_kmp.h"

static int failures;
static kmpjs_engine *g;
static int64_t retained_ref;
static char last_log[512];

#define CHECK(cond) do { if (!(cond)) { failures++; printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } } while (0)
#define S(x) x, (int32_t)strlen(x)

static int str_is(const kmpjs_value *v, const char *expected)
{
    return v->str && v->str_len == (int32_t)strlen(expected) && memcmp(v->str, expected, (size_t)v->str_len) == 0;
}

static int str_has(const kmpjs_value *v, const char *needle)
{
    char buf[1024];
    int32_t n = v->str_len < (int32_t)sizeof(buf) - 1 ? v->str_len : (int32_t)sizeof(buf) - 1;
    if (!v->str)
        return 0;
    memcpy(buf, v->str, (size_t)n);
    buf[n] = '\0';
    return strstr(buf, needle) != NULL;
}

static void set_str(kmpjs_value *v, int32_t tag, const char *text)
{
    memset(v, 0, sizeof(*v));
    v->tag = tag;
    v->str = kmpjs_alloc((int32_t)strlen(text));
    memcpy((char *)v->str, text, strlen(text));
    v->str_len = (int32_t)strlen(text);
}

static kmpjs_value eval(const char *code, int32_t flags)
{
    kmpjs_value out;
    kmpjs_eval(g, code, (int32_t)strlen(code), "<test>", flags, &out);
    return out;
}

static int host(void *user, int32_t id, const kmpjs_value *args, int32_t argc, kmpjs_value *result)
{
    memset(result, 0, sizeof(*result));
    switch (id) {
    case 0: /* echo argument count as "ok:N" */
        {
            char buf[32];
            snprintf(buf, sizeof buf, "ok:%d", argc);
            set_str(result, KMPJS_TAG_STRING, buf);
            CHECK(argc == 5);
            CHECK(args[0].tag == KMPJS_TAG_NUMBER && args[0].num == 1);
            CHECK(args[1].tag == KMPJS_TAG_STRING && str_is(&args[1], "two"));
            CHECK(args[2].tag == KMPJS_TAG_BOOL && args[2].num == 1);
            CHECK(args[3].tag == KMPJS_TAG_NULL);
            CHECK(args[4].tag == KMPJS_TAG_OBJECT && str_is(&args[4], "{\"k\":[3]}"));
            return 0;
        }
    case 1: /* throw */
        set_str(result, KMPJS_TAG_EXCEPTION, "host failed");
        return 1;
    case 2: /* object result */
        set_str(result, KMPJS_TAG_OBJECT, "{\"n\":7,\"s\":\"x\"}");
        return 0;
    case 3: /* interrupt from inside script */
        kmpjs_interrupt(g);
        result->tag = KMPJS_TAG_UNDEFINED;
        return 0;
    case 4: /* keep a ref argument */
        CHECK(args[0].tag == KMPJS_TAG_REF && (int)args[0].num == 0);
        CHECK(args[1].tag == KMPJS_TAG_REF && ((int)args[1].num & KMPJS_REF_ARRAY));
        CHECK(args[2].tag == KMPJS_TAG_STRING);
        kmpjs_ref_retain(g, args[0].ref);
        retained_ref = args[0].ref;
        result->tag = KMPJS_TAG_UNDEFINED;
        return 0;
    case 5: /* give the retained ref back */
        result->tag = KMPJS_TAG_REF;
        result->ref = retained_ref;
        return 0;
    case 6: /* nested evaluation */
        {
            kmpjs_value o = eval("nestedCounter = nestedCounter + 1", 0);
            result->tag = KMPJS_TAG_NUMBER;
            result->num = o.num;
            return 0;
        }
    default:
        result->tag = KMPJS_TAG_UNDEFINED;
        return 0;
    }
}

static void logger(void *user, const char *msg, int32_t len)
{
    int32_t n = len < (int32_t)sizeof(last_log) - 1 ? len : (int32_t)sizeof(last_log) - 1;
    memcpy(last_log, msg, (size_t)n);
    last_log[n] = '\0';
}

static void test_values(void)
{
    kmpjs_value v;
    v = eval("1 + 2", 0); CHECK(v.tag == KMPJS_TAG_NUMBER && v.num == 3);
    v = eval("'h\xc3\xa9llo'", 0); CHECK(v.tag == KMPJS_TAG_STRING && str_is(&v, "h\xc3\xa9llo"));
    v = eval("1 < 2", 0); CHECK(v.tag == KMPJS_TAG_BOOL && v.num == 1);
    v = eval("null", 0); CHECK(v.tag == KMPJS_TAG_NULL);
    v = eval("undefined", 0); CHECK(v.tag == KMPJS_TAG_UNDEFINED);
    v = eval("({a: 1, b: [1, 2, 'x']})", 0); CHECK(v.tag == KMPJS_TAG_OBJECT && str_is(&v, "{\"a\":1,\"b\":[1,2,\"x\"]}"));
    v = eval("(function () {})", 0); CHECK(v.tag == KMPJS_TAG_OBJECT && v.str == NULL);
    v = eval("var counter = 41;", 0); CHECK(v.tag == KMPJS_TAG_UNDEFINED);
    v = eval("counter + 1", 0); CHECK(v.tag == KMPJS_TAG_NUMBER && v.num == 42);
    v = eval("'\\uD800'", 0); CHECK(v.tag == KMPJS_TAG_STRING && v.str_len == 3);
}

static void test_exceptions(void)
{
    kmpjs_value v;
    v = eval("null.x", 0); CHECK(v.tag == KMPJS_TAG_EXCEPTION && str_is(&v, "TypeError: cannot read property 'x' of null") && v.stack && str_has(&(kmpjs_value){.str = v.stack, .str_len = v.stack_len}, "<test>"));
    v = eval("var x = ;", 0); CHECK(v.tag == KMPJS_TAG_EXCEPTION && str_has(&v, "SyntaxError"));
    v = eval("throw 42", 0); CHECK(v.tag == KMPJS_TAG_EXCEPTION && str_is(&v, "42"));
    v = eval("var a = []; for (var i = 0; i < 1000000; i++) a.push({i: i});", 0); CHECK(v.tag == KMPJS_TAG_EXCEPTION && str_has(&v, "out of memory"));
    v = eval("1", 0); CHECK(v.tag == KMPJS_TAG_NUMBER && v.num == 1);
}

static void test_host_functions(void)
{
    kmpjs_value v, out;
    CHECK(kmpjs_define_function(g, "record", 0, 0, &out) == 0);
    CHECK(kmpjs_define_function(g, "boom", 1, 0, &out) == 0);
    CHECK(kmpjs_define_function(g, "obj", 2, 0, &out) == 0);
    CHECK(kmpjs_define_function(g, "stop", 3, 0, &out) == 0);
    CHECK(kmpjs_define_function(g, "keep", 4, KMPJS_FLAG_REF_OBJECTS, &out) == 0);
    CHECK(kmpjs_define_function(g, "give", 5, 0, &out) == 0);
    CHECK(kmpjs_define_function(g, "nested", 6, 0, &out) == 0);
    v = eval("record(1, 'two', true, null, {k: [3]})", 0); CHECK(v.tag == KMPJS_TAG_STRING && str_is(&v, "ok:5"));
    v = eval("try { boom() } catch (e) { 'caught: ' + e.message }", 0); CHECK(str_is(&v, "caught: host failed"));
    v = eval("boom()", 0); CHECK(v.tag == KMPJS_TAG_EXCEPTION && str_is(&v, "Error: host failed"));
    v = eval("obj().n + 1", 0); CHECK(v.tag == KMPJS_TAG_NUMBER && v.num == 8);
    v = eval("console.log('a', 1, {x: true}); print('b'); 5", 0); CHECK(v.num == 5 && strcmp(last_log, "b") == 0);
    v = eval("stop(); for (;;) {}", 0); CHECK(v.tag == KMPJS_TAG_EXCEPTION && str_has(&v, "interrupted"));
    v = eval("var nestedCounter = 0; nested() + nested()", 0); CHECK(v.tag == KMPJS_TAG_NUMBER && v.num == 3);
    kmpjs_interrupt(g); /* idle: must be a no-op */
    v = eval("2", 0); CHECK(v.tag == KMPJS_TAG_NUMBER && v.num == 2);
}

static void test_refs(void)
{
    kmpjs_value v, out, args[2], sv;
    kmpjs_stats st;
    int64_t obj, arr, f, g2, held[50];
    int i;

    v = eval("var target = {n: 7, s: 'x', arr: [1, 2, 3], f: function (a, b) { return a + b + this.n; }}; target", KMPJS_FLAG_REF_OBJECTS);
    CHECK(v.tag == KMPJS_TAG_REF && (int)v.num == 0); obj = v.ref;
    CHECK(kmpjs_ref_get(g, obj, "n", 0, &v) == 0 && v.num == 7);
    CHECK(kmpjs_ref_get(g, obj, "arr", 0, &v) == 0 && str_is(&v, "[1,2,3]"));
    CHECK(kmpjs_ref_get(g, obj, "arr", KMPJS_FLAG_REF_OBJECTS, &v) == 0 && v.tag == KMPJS_TAG_REF && ((int)v.num & KMPJS_REF_ARRAY)); arr = v.ref;
    CHECK(kmpjs_ref_get_index(g, arr, 1, 0, &v) == 0 && v.num == 2);
    CHECK(kmpjs_ref_get_index(g, arr, 10, 0, &v) == 0 && v.tag == KMPJS_TAG_UNDEFINED);
    CHECK(kmpjs_ref_get_index(g, arr, -1, 0, &v) != 0);
    CHECK(kmpjs_ref_get(g, obj, "f", KMPJS_FLAG_REF_OBJECTS, &v) == 0 && ((int)v.num & KMPJS_REF_FUNCTION)); f = v.ref;
    memset(args, 0, sizeof args);
    args[0].tag = KMPJS_TAG_NUMBER; args[0].num = 10; args[1].tag = KMPJS_TAG_NUMBER; args[1].num = 5;
    CHECK(kmpjs_ref_call(g, f, obj, args, 2, 0, &v) == 0 && v.num == 22);
    CHECK(kmpjs_ref_call(g, f, 0, args, 2, 0, &v) != 0 && str_has(&v, "TypeError"));
    CHECK(kmpjs_ref_call(g, obj, 0, NULL, 0, 0, &v) != 0 && str_has(&v, "not a function"));
    set_str(&sv, KMPJS_TAG_STRING, "hello");
    CHECK(kmpjs_ref_set(g, obj, "s", &sv, &out) == 0);
    kmpjs_free((void *)sv.str);
    set_str(&sv, KMPJS_TAG_OBJECT, "{\"z\":[true]}");
    CHECK(kmpjs_ref_set(g, obj, "j", &sv, &out) == 0);
    kmpjs_free((void *)sv.str);
    memset(&sv, 0, sizeof sv); sv.tag = KMPJS_TAG_REF; sv.ref = arr;
    CHECK(kmpjs_ref_set(g, obj, "arr2", &sv, &out) == 0);
    CHECK(kmpjs_ref_to_json(g, obj, &v) == 0 && str_is(&v, "{\"n\":7,\"s\":\"hello\",\"arr\":[1,2,3],\"f\":null,\"j\":{\"z\":[true]},\"arr2\":[1,2,3]}"));
    CHECK(kmpjs_ref_to_json(g, f, &v) == 0 && v.str == NULL);
    v = eval("target.arr2 === target.arr", 0); CHECK(v.tag == KMPJS_TAG_BOOL && v.num == 1);
    v = eval("(function (x) { return x.length * 10; })", KMPJS_FLAG_REF_OBJECTS); g2 = v.ref;
    CHECK(kmpjs_ref_call(g, g2, 0, &sv, 1, 0, &v) == 0 && v.num == 30);

    /* transient refs into a host function, retained and given back */
    v = eval("keep({k: 'kept'}, [1], 'str')", 0); CHECK(v.tag == KMPJS_TAG_UNDEFINED);
    CHECK(kmpjs_ref_get(g, retained_ref, "k", 0, &v) == 0 && str_is(&v, "kept"));
    v = eval("give().k + '!'", 0); CHECK(str_is(&v, "kept!"));
    kmpjs_ref_release(g, retained_ref);
    CHECK(kmpjs_ref_get(g, retained_ref, "k", 0, &v) != 0 && str_has(&v, "released"));

    /* generation: a stale handle never aliases a reused slot */
    v = eval("({a: 1})", KMPJS_FLAG_REF_OBJECTS); { int64_t r1 = v.ref; kmpjs_ref_release(g, r1);
        v = eval("({b: 2})", KMPJS_FLAG_REF_OBJECTS); { int64_t r2 = v.ref;
            CHECK((r1 & 0xFFFFFFFF) == (r2 & 0xFFFFFFFF) && (r1 >> 32) != (r2 >> 32));
            CHECK(kmpjs_ref_get(g, r1, "b", 0, &v) != 0);
            kmpjs_ref_release(g, r1);
            CHECK(kmpjs_ref_get(g, r2, "b", 0, &v) == 0 && v.num == 2);
            kmpjs_ref_release(g, r2); } }

    /* refs survive GC, churn returns slots */
    for (i = 0; i < 50; i++) { char code[32]; snprintf(code, sizeof code, "({v: %d})", i); v = eval(code, KMPJS_FLAG_REF_OBJECTS); held[i] = v.ref; }
    v = eval("var junk = []; for (var i = 0; i < 2000; i++) junk.push({i: i}); junk = null; 1", 0);
    for (i = 0; i < 50; i++) { CHECK(kmpjs_ref_get(g, held[i], "v", 0, &v) == 0 && v.num == i); kmpjs_ref_release(g, held[i]); }
    kmpjs_get_stats(g, &st);
    CHECK(st.live_refs == 4); /* obj, arr, f, g2 */
    for (i = 0; i < 20000; i++) { v = eval("({i: 1})", KMPJS_FLAG_REF_OBJECTS); CHECK(v.tag == KMPJS_TAG_REF); kmpjs_ref_release(g, v.ref); }
    kmpjs_get_stats(g, &st);
    CHECK(st.live_refs == 4 && st.ref_slots < 100);

    /* interrupt reaches accessors */
    v = eval("({get y() { stop(); for (;;) {} }})", KMPJS_FLAG_REF_OBJECTS);
    CHECK(kmpjs_ref_get(g, v.ref, "y", 0, &out) != 0 && str_has(&out, "interrupted"));
    kmpjs_ref_release(g, v.ref);

    kmpjs_ref_release(g, obj); kmpjs_ref_release(g, arr); kmpjs_ref_release(g, f); kmpjs_ref_release(g, g2);
    kmpjs_ref_release(g, 9999); kmpjs_ref_release(g, obj);
    kmpjs_get_stats(g, &st);
    CHECK(st.live_refs == 0);
    kmpjs_dump_memory(g, &v); CHECK(v.tag == KMPJS_TAG_STRING && v.str_len > 0);
}

int main(void)
{
    kmpjs_value v;
    g = kmpjs_create(256 * 1024, NULL, host, logger);
    CHECK(g != NULL);
    CHECK(kmpjs_create(100, NULL, host, logger) == NULL);
    test_values();
    test_exceptions();
    test_host_functions();
    test_refs();
    /* destroy with refs still open must be clean */
    v = eval("({leak: 1})", KMPJS_FLAG_REF_OBJECTS); CHECK(v.tag == KMPJS_TAG_REF);
    kmpjs_destroy(g);
    printf("shim_test: %d failure(s)\n", failures);
    return failures;
}
