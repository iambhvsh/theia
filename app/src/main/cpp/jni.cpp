#include <jni.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include "needle.h"
}

static void fail(JNIEnv* e, const std::string& m) {
    jclass c = e->FindClass("java/lang/RuntimeException");
    e->ThrowNew(c, m.c_str());
}

static std::string lastErr() {
    const char* m = needle_last_error();
    return m ? m : "unknown";
}

static std::string toStr(JNIEnv* e, jbyteArray a) {
    if (a == nullptr) {
        return {};
    }
    const auto n = e->GetArrayLength(a);
    if (n <= 0) {
        return {};
    }
    std::vector<jbyte> buf(static_cast<size_t>(n));
    e->GetByteArrayRegion(a, 0, n, buf.data());
    return std::string(buf.begin(), buf.end());
}

static jbyteArray toJava(JNIEnv* e, const char* s) {
    if (s == nullptr) {
        return e->NewByteArray(0);
    }
    constexpr size_t kMaxLen = 16384;
    const auto len = static_cast<jsize>(strnlen(s, kMaxLen));
    jbyteArray a = e->NewByteArray(len);
    if (len > 0) {
        std::vector<jbyte> buf(s, s + len);
        e->SetByteArrayRegion(a, 0, len, buf.data());
    }
    return a;
}

extern "C" {

// mmap the .cact and hand it to the engine. Mapping is never released:
// the engine owns one process-global model per kind and has no unload.
JNIEXPORT jint JNICALL
Java_in_iambhvsh_theia_Engine_nativeLoad(JNIEnv* e, jobject, jstring path) {
    const char* p = e->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDONLY);
    e->ReleaseStringUTFChars(path, p);
    if (fd < 0) { fail(e, "open failed"); return -1; }

    struct stat st;
    if (fstat(fd, &st) != 0) { close(fd); fail(e, "fstat failed"); return -1; }

    const void* m = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) { fail(e, "mmap failed"); return -1; }

    const int rc = needle_load(static_cast<const unsigned char*>(m),
                               static_cast<unsigned long long>(st.st_size));
    if (rc < 0) { fail(e, "needle_load: " + lastErr()); return rc; }
    return rc;
}

JNIEXPORT jint JNICALL
Java_in_iambhvsh_theia_Engine_nativeModels(JNIEnv*, jobject) {
    return needle_models();
}

JNIEXPORT jint JNICALL
Java_in_iambhvsh_theia_Engine_nativeInit(JNIEnv* e, jobject, jbyteArray tools) {
    std::string t = toStr(e, tools);
    const int rc = needle_init(nullptr, t.c_str(), nullptr);
    if (rc < 0) {
        fail(e, "needle_init: " + lastErr());
    }
    return rc;
}

JNIEXPORT jbyteArray JNICALL
Java_in_iambhvsh_theia_Engine_nativeTranscribe(JNIEnv* e, jobject, jfloatArray pcm) {
    const auto n = e->GetArrayLength(pcm);
    jfloat* d = e->GetFloatArrayElements(pcm, nullptr);
    std::vector<char> out(16384, 0);
    static const char* kKeywords = "Theia\nopen\napp\ntimer\nalarm\nsearch\nfind\nreminder\nminutes\nminute\nhours\nhour\nfiles\ncall\nplay";
    const int rc = needle_transcribe(d, static_cast<int>(n), "en", kKeywords, 0, out.data(), static_cast<int>(out.size()));
    e->ReleaseFloatArrayElements(pcm, d, JNI_ABORT);
    if (rc < 0) { fail(e, "needle_transcribe: " + lastErr()); return nullptr; }
    return toJava(e, out.data());
}

JNIEXPORT jbyteArray JNICALL
Java_in_iambhvsh_theia_Engine_nativeComplete(JNIEnv* e, jobject, jbyteArray text) {
    std::string in = toStr(e, text);
    std::vector<char> out(16384, 0);
    if (const int rc = needle_complete(in.c_str(), nullptr, 0, 256, out.data(), static_cast<int>(out.size())); rc < 0) {
        fail(e, "needle_complete: " + lastErr());
        return nullptr;
    }
    return toJava(e, out.data());
}

JNIEXPORT void JNICALL
Java_in_iambhvsh_theia_Engine_nativeReset(JNIEnv*, jobject) {
    needle_reset();
}

// Subpackage aliases for in.iambhvsh.theia.ai.Engine
JNIEXPORT jint JNICALL
Java_in_iambhvsh_theia_ai_Engine_nativeLoad(JNIEnv* e, jobject thiz, jstring path) {
    return Java_in_iambhvsh_theia_Engine_nativeLoad(e, thiz, path);
}

JNIEXPORT jint JNICALL
Java_in_iambhvsh_theia_ai_Engine_nativeModels(JNIEnv* e, jobject thiz) {
    return Java_in_iambhvsh_theia_Engine_nativeModels(e, thiz);
}

JNIEXPORT jint JNICALL
Java_in_iambhvsh_theia_ai_Engine_nativeInit(JNIEnv* e, jobject thiz, jbyteArray tools) {
    return Java_in_iambhvsh_theia_Engine_nativeInit(e, thiz, tools);
}

JNIEXPORT jbyteArray JNICALL
Java_in_iambhvsh_theia_ai_Engine_nativeTranscribe(JNIEnv* e, jobject thiz, jfloatArray pcm) {
    return Java_in_iambhvsh_theia_Engine_nativeTranscribe(e, thiz, pcm);
}

JNIEXPORT jbyteArray JNICALL
Java_in_iambhvsh_theia_ai_Engine_nativeComplete(JNIEnv* e, jobject thiz, jbyteArray text) {
    return Java_in_iambhvsh_theia_Engine_nativeComplete(e, thiz, text);
}

JNIEXPORT void JNICALL
Java_in_iambhvsh_theia_ai_Engine_nativeReset(JNIEnv* e, jobject thiz) {
    Java_in_iambhvsh_theia_Engine_nativeReset(e, thiz);
}

}  // extern "C"
