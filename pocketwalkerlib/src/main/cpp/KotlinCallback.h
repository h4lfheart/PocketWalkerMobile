#include <jni.h>
#include <functional>

class KotlinCallback {
private:
    static JavaVM* g_jvm;

    static bool GetJNIEnv(JNIEnv** env, bool* shouldDetach) {
        *shouldDetach = false;

        if (!g_jvm) return false;

        int getEnvStat = g_jvm->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_6);
        if (getEnvStat == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(env, nullptr) != 0) {
                return false; // Failed to attach
            }
            *shouldDetach = true;
        }
        return getEnvStat == JNI_OK || getEnvStat == JNI_EDETACHED;
    }

    static void CleanupJNIEnv(bool shouldDetach) {
        if (shouldDetach && g_jvm) {
            g_jvm->DetachCurrentThread();
        }
    }

public:
    static void Initialize(JavaVM* vm) {
        g_jvm = vm;
    }

    template<typename T>
    static void InvokeCallback(jobject callback, T value,
                               std::function<jobject(JNIEnv*, T)> valueConverter) {
        if (!g_jvm || !callback) return;

        JNIEnv* env;
        bool shouldDetach = false;

        if (!GetJNIEnv(&env, &shouldDetach)) return;

        try {
            jobject javaValue = valueConverter(env, value);

            jclass callbackClass = env->GetObjectClass(callback);
            jmethodID invokeMethod = env->GetMethodID(callbackClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");

            env->CallObjectMethod(callback, invokeMethod, javaValue);

            env->DeleteLocalRef(javaValue);
            env->DeleteLocalRef(callbackClass);

        } catch (...) {
        }

        CleanupJNIEnv(shouldDetach);
    }

    static void InvokeByteArrayCallback(jobject callback, uint8_t* data, jsize size) {
        InvokeCallback<uint8_t*>(callback, data, [size](JNIEnv* env, uint8_t* data) -> jobject {
            jbyteArray javaByteArray = env->NewByteArray(size);
            env->SetByteArrayRegion(javaByteArray, 0, size, reinterpret_cast<const jbyte*>(data));
            return javaByteArray;
        });
    }

    static void InvokeFloatCallback(jobject callback, float value) {
        InvokeCallback<float>(callback, value, [](JNIEnv* env, float value) -> jobject {
            jclass floatClass = env->FindClass("java/lang/Float");
            jmethodID floatConstructor = env->GetMethodID(floatClass, "<init>", "(F)V");
            jobject floatObject = env->NewObject(floatClass, floatConstructor, value);
            env->DeleteLocalRef(floatClass);
            return floatObject;
        });
    }

    static void InvokeIntCallback(jobject callback, int value) {
        InvokeCallback<int>(callback, value, [](JNIEnv* env, int value) -> jobject {
            jclass intClass = env->FindClass("java/lang/Integer");
            jmethodID intConstructor = env->GetMethodID(intClass, "<init>", "(I)V");
            jobject intObject = env->NewObject(intClass, intConstructor, value);
            env->DeleteLocalRef(intClass);
            return intObject;
        });
    }

    static void InvokeStringCallback(jobject callback, const char* value) {
        InvokeCallback<const char*>(callback, value, [](JNIEnv* env, const char* value) -> jobject {
            return env->NewStringUTF(value);
        });
    }

    static void InvokeVoidCallback(jobject callback) {
        if (!g_jvm || !callback) return;

        JNIEnv* env;
        bool shouldDetach = false;

        if (!GetJNIEnv(&env, &shouldDetach)) return;

        try {
            jclass callbackClass = env->GetObjectClass(callback);
            jmethodID invokeMethod = env->GetMethodID(callbackClass, "invoke", "()Ljava/lang/Object;");

            env->CallObjectMethod(callback, invokeMethod);

            env->DeleteLocalRef(callbackClass);

        } catch (...) {
        }

        CleanupJNIEnv(shouldDetach);
    }
};

JavaVM* KotlinCallback::g_jvm = nullptr;