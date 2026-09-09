#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "mquickjs_kmp.h"

#define BRIDGE_CLASS "wang/harlon/mquickjs/NativeBridge"
#define VALUE_CLASS "wang/harlon/mquickjs/NativeValue"

static JavaVM *g_vm;
static jclass g_bridge;
static jmethodID g_on_host_call;
static jmethodID g_on_log;
static jclass g_value;
static jmethodID g_value_ctor;
static jfieldID g_f_tag, g_f_ref, g_f_num, g_f_str, g_f_stack;

typedef struct {
    jobject target;
} jni_user;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env;
    jclass cls;

    g_vm = vm;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    cls = (*env)->FindClass(env, BRIDGE_CLASS);
    if (!cls)
        return JNI_ERR;
    g_bridge = (*env)->NewGlobalRef(env, cls);
    g_on_host_call = (*env)->GetStaticMethodID(env, g_bridge, "onHostCall",
        "(Ljava/lang/Object;I[L" VALUE_CLASS ";)L" VALUE_CLASS ";");
    g_on_log = (*env)->GetStaticMethodID(env, g_bridge, "onLog", "(Ljava/lang/Object;[B)V");

    cls = (*env)->FindClass(env, VALUE_CLASS);
    if (!cls)
        return JNI_ERR;
    g_value = (*env)->NewGlobalRef(env, cls);
    g_value_ctor = (*env)->GetMethodID(env, g_value, "<init>", "(IJD[B[B)V");
    g_f_tag = (*env)->GetFieldID(env, g_value, "tag", "I");
    g_f_ref = (*env)->GetFieldID(env, g_value, "ref", "J");
    g_f_num = (*env)->GetFieldID(env, g_value, "num", "D");
    g_f_str = (*env)->GetFieldID(env, g_value, "str", "[B");
    g_f_stack = (*env)->GetFieldID(env, g_value, "stack", "[B");

    if (!g_on_host_call || !g_on_log || !g_value_ctor || !g_f_tag || !g_f_ref || !g_f_num || !g_f_str || !g_f_stack)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}

/* Host callbacks run on the thread that entered kmpjs_eval, which is a Java thread. */
static JNIEnv *current_env(void)
{
    JNIEnv *env = NULL;
    (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    return env;
}

static jbyteArray new_bytes(JNIEnv *env, const char *p, int32_t len)
{
    jbyteArray arr;
    if (!p)
        return NULL;
    arr = (*env)->NewByteArray(env, len);
    if (arr && len > 0)
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)p);
    return arr;
}

static jobject new_value(JNIEnv *env, const kmpjs_value *v)
{
    return (*env)->NewObject(env, g_value, g_value_ctor, (jint)v->tag, (jlong)v->ref, (jdouble)v->num,
                             new_bytes(env, v->str, v->str_len),
                             new_bytes(env, v->stack, v->stack_len));
}

static char *copy_bytes(JNIEnv *env, jbyteArray arr, int32_t *plen)
{
    jsize len;
    char *buf;
    if (!arr) {
        *plen = 0;
        return NULL;
    }
    len = (*env)->GetArrayLength(env, arr);
    buf = kmpjs_alloc(len);
    if (!buf) {
        *plen = 0;
        return NULL;
    }
    if (len > 0)
        (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    *plen = (int32_t)len;
    return buf;
}

static void set_error(kmpjs_value *result, const char *msg)
{
    int32_t len = (int32_t)strlen(msg);
    result->tag = KMPJS_TAG_EXCEPTION;
    result->str = kmpjs_alloc(len);
    if (result->str)
        memcpy((char *)result->str, msg, len);
    result->str_len = result->str ? len : 0;
}

/* Copies a NativeValue into *v; string payloads are kmpjs_alloc'ed and owned by the caller. */
static void read_value(JNIEnv *env, jobject obj, kmpjs_value *v)
{
    jbyteArray bytes;
    memset(v, 0, sizeof(*v));
    if (!obj) {
        v->tag = KMPJS_TAG_UNDEFINED;
        return;
    }
    v->tag = (*env)->GetIntField(env, obj, g_f_tag);
    v->ref = (*env)->GetLongField(env, obj, g_f_ref);
    v->num = (*env)->GetDoubleField(env, obj, g_f_num);
    bytes = (*env)->GetObjectField(env, obj, g_f_str);
    v->str = copy_bytes(env, bytes, &v->str_len);
    if (bytes)
        (*env)->DeleteLocalRef(env, bytes);
    bytes = (*env)->GetObjectField(env, obj, g_f_stack);
    v->stack = copy_bytes(env, bytes, &v->stack_len);
    if (bytes)
        (*env)->DeleteLocalRef(env, bytes);
}

static void free_value(kmpjs_value *v)
{
    kmpjs_free((void *)v->str);
    kmpjs_free((void *)v->stack);
}

static int jni_host(void *user, int32_t fn_id, const kmpjs_value *args, int32_t argc, kmpjs_value *result)
{
    JNIEnv *env = current_env();
    jni_user *u = user;
    jobjectArray arr;
    jobject res;
    int i;

    if (!env || (*env)->PushLocalFrame(env, argc + 8) != 0) {
        set_error(result, "JNI local frame unavailable");
        return 1;
    }
    arr = (*env)->NewObjectArray(env, argc, g_value, NULL);
    for (i = 0; i < argc; i++)
        (*env)->SetObjectArrayElement(env, arr, i, new_value(env, &args[i]));

    res = (*env)->CallStaticObjectMethod(env, g_bridge, g_on_host_call, u->target, (jint)fn_id, arr);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->PopLocalFrame(env, NULL);
        set_error(result, "uncaught exception in host function");
        return 1;
    }
    read_value(env, res, result);
    (*env)->PopLocalFrame(env, NULL);
    return result->tag == KMPJS_TAG_EXCEPTION;
}

static void jni_log(void *user, const char *msg, int32_t len)
{
    JNIEnv *env = current_env();
    jni_user *u = user;
    if (!env || (*env)->PushLocalFrame(env, 4) != 0)
        return;
    (*env)->CallStaticVoidMethod(env, g_bridge, g_on_log, u->target, new_bytes(env, msg, len));
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);
}

JNIEXPORT jint JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeAbiVersion(JNIEnv *env, jclass cls)
{
    return kmpjs_abi_version();
}

JNIEXPORT jlong JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeCreate(JNIEnv *env, jclass cls, jint mem_bytes, jobject target)
{
    jni_user *u = calloc(1, sizeof(*u));
    kmpjs_engine *e;
    if (!u)
        return 0;
    u->target = (*env)->NewGlobalRef(env, target);
    e = kmpjs_create(mem_bytes, u, jni_host, jni_log);
    if (!e) {
        (*env)->DeleteGlobalRef(env, u->target);
        free(u);
        return 0;
    }
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeDestroy(JNIEnv *env, jclass cls, jlong ptr)
{
    kmpjs_engine *e = (kmpjs_engine *)(intptr_t)ptr;
    jni_user *u = kmpjs_get_user(e);
    kmpjs_destroy(e);
    (*env)->DeleteGlobalRef(env, u->target);
    free(u);
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeEval(JNIEnv *env, jclass cls, jlong ptr,
                                                  jbyteArray code, jbyteArray filename, jint flags)
{
    kmpjs_engine *e = (kmpjs_engine *)(intptr_t)ptr;
    kmpjs_value out;
    jsize code_len = (*env)->GetArrayLength(env, code);
    jsize name_len = (*env)->GetArrayLength(env, filename);
    char *code_buf = malloc(code_len + 1);
    char *name_buf = malloc(name_len + 1);
    jobject res;

    if (!code_buf || !name_buf) {
        free(code_buf);
        free(name_buf);
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, code, 0, code_len, (jbyte *)code_buf);
    (*env)->GetByteArrayRegion(env, filename, 0, name_len, (jbyte *)name_buf);
    code_buf[code_len] = '\0';
    name_buf[name_len] = '\0';

    kmpjs_eval(e, code_buf, code_len, name_buf, flags, &out);
    res = new_value(env, &out);
    free(code_buf);
    free(name_buf);
    return res;
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeDefineFunction(JNIEnv *env, jclass cls, jlong ptr,
                                                            jbyteArray name, jint fn_id, jint flags)
{
    kmpjs_engine *e = (kmpjs_engine *)(intptr_t)ptr;
    kmpjs_value out;
    jsize len = (*env)->GetArrayLength(env, name);
    char *buf = malloc(len + 1);
    jobject res;

    if (!buf)
        return NULL;
    (*env)->GetByteArrayRegion(env, name, 0, len, (jbyte *)buf);
    buf[len] = '\0';
    kmpjs_define_function(e, buf, fn_id, flags, &out);
    res = new_value(env, &out);
    free(buf);
    return res;
}

JNIEXPORT void JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeInterrupt(JNIEnv *env, jclass cls, jlong ptr)
{
    kmpjs_interrupt((kmpjs_engine *)(intptr_t)ptr);
}

/* ---- refs ---- */

static char *dup_cstring(JNIEnv *env, jbyteArray arr)
{
    jsize len = (*env)->GetArrayLength(env, arr);
    char *buf = malloc((size_t)len + 1);
    if (!buf)
        return NULL;
    (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    buf[len] = '\0';
    return buf;
}

JNIEXPORT void JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeRefRetain(JNIEnv *env, jclass cls, jlong ptr, jlong ref)
{
    kmpjs_ref_retain((kmpjs_engine *)(intptr_t)ptr, ref);
}

JNIEXPORT void JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeRefRelease(JNIEnv *env, jclass cls, jlong ptr, jlong ref)
{
    kmpjs_ref_release((kmpjs_engine *)(intptr_t)ptr, ref);
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeRefGet(JNIEnv *env, jclass cls, jlong ptr, jlong ref,
                                                    jbyteArray name, jint flags)
{
    kmpjs_value out;
    char *buf = dup_cstring(env, name);
    jobject res;
    if (!buf)
        return NULL;
    kmpjs_ref_get((kmpjs_engine *)(intptr_t)ptr, ref, buf, flags, &out);
    res = new_value(env, &out);
    free(buf);
    return res;
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeRefGetIndex(JNIEnv *env, jclass cls, jlong ptr, jlong ref,
                                                         jint index, jint flags)
{
    kmpjs_value out;
    kmpjs_ref_get_index((kmpjs_engine *)(intptr_t)ptr, ref, index, flags, &out);
    return new_value(env, &out);
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeRefSet(JNIEnv *env, jclass cls, jlong ptr, jlong ref,
                                                    jbyteArray name, jobject value)
{
    kmpjs_value out, v;
    char *buf = dup_cstring(env, name);
    jobject res;
    if (!buf)
        return NULL;
    read_value(env, value, &v);
    kmpjs_ref_set((kmpjs_engine *)(intptr_t)ptr, ref, buf, &v, &out);
    res = new_value(env, &out);
    free_value(&v);
    free(buf);
    return res;
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeRefCall(JNIEnv *env, jclass cls, jlong ptr, jlong ref,
                                                     jlong this_ref, jobjectArray args, jint flags)
{
    kmpjs_value out;
    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    kmpjs_value *values = argc > 0 ? calloc((size_t)argc, sizeof(*values)) : NULL;
    jobject res;
    jsize i;
    if (argc > 0 && !values)
        return NULL;
    for (i = 0; i < argc; i++) {
        jobject item = (*env)->GetObjectArrayElement(env, args, i);
        read_value(env, item, &values[i]);
        if (item)
            (*env)->DeleteLocalRef(env, item);
    }
    kmpjs_ref_call((kmpjs_engine *)(intptr_t)ptr, ref, this_ref, values, argc, flags, &out);
    res = new_value(env, &out);
    for (i = 0; i < argc; i++)
        free_value(&values[i]);
    free(values);
    return res;
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeRefToJson(JNIEnv *env, jclass cls, jlong ptr, jlong ref)
{
    kmpjs_value out;
    kmpjs_ref_to_json((kmpjs_engine *)(intptr_t)ptr, ref, &out);
    return new_value(env, &out);
}

JNIEXPORT jintArray JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeStats(JNIEnv *env, jclass cls, jlong ptr)
{
    kmpjs_stats st;
    jint values[2];
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr)
        return NULL;
    kmpjs_get_stats((kmpjs_engine *)(intptr_t)ptr, &st);
    values[0] = st.live_refs;
    values[1] = st.ref_slots;
    (*env)->SetIntArrayRegion(env, arr, 0, 2, values);
    return arr;
}

JNIEXPORT jobject JNICALL
Java_wang_harlon_mquickjs_NativeBridge_nativeDumpMemory(JNIEnv *env, jclass cls, jlong ptr)
{
    kmpjs_value out;
    kmpjs_dump_memory((kmpjs_engine *)(intptr_t)ptr, &out);
    return new_value(env, &out);
}
