/* Ahead-of-time compiler: kmpjsc [-m32] [--no-column] -o out.bin script.js
   Output is bound to the engine commit in native/UPSTREAM and to the chosen word size. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "mquickjs_kmp.h"

static void usage(void)
{
    fprintf(stderr, "usage: kmpjsc [-m32] [--no-column] -o OUT script.js\n");
    exit(2);
}

int main(int argc, char **argv)
{
    const char *out_path = NULL, *in_path = NULL;
    int32_t word_size = kmpjs_word_size(), flags = 0, i;
    FILE *f;
    long len;
    char *code;
    kmpjs_value out;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-m32") == 0) {
            word_size = 32;
        } else if (strcmp(argv[i], "--no-column") == 0) {
            flags |= KMPJS_COMPILE_STRIP_COLUMNS;
        } else if (strcmp(argv[i], "-o") == 0 && i + 1 < argc) {
            out_path = argv[++i];
        } else if (argv[i][0] == '-') {
            usage();
        } else {
            in_path = argv[i];
        }
    }
    if (!out_path || !in_path)
        usage();

    f = fopen(in_path, "rb");
    if (!f) {
        perror(in_path);
        return 1;
    }
    fseek(f, 0, SEEK_END);
    len = ftell(f);
    fseek(f, 0, SEEK_SET);
    code = malloc((size_t)len + 1);
    if (!code || fread(code, 1, (size_t)len, f) != (size_t)len) {
        fprintf(stderr, "could not read %s\n", in_path);
        return 1;
    }
    fclose(f);

    if (kmpjs_compile(code, (int32_t)len, in_path, word_size, flags, &out) != 0) {
        fprintf(stderr, "%s: %.*s\n", in_path, out.str_len, out.str ? out.str : "");
        kmpjs_free((void *)out.str);
        return 1;
    }
    f = fopen(out_path, "wb");
    if (!f) {
        perror(out_path);
        return 1;
    }
    fwrite(out.str, 1, (size_t)out.str_len, f);
    fclose(f);
    kmpjs_free((void *)out.str);
    free(code);
    return 0;
}
