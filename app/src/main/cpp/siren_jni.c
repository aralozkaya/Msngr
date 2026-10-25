#include <jni.h>
#include <pthread.h>
#include "libsiren/siren7.h"

// Upstream uses shared scratch tables/bitstream state. Serialize whole clips.
static pthread_mutex_t codec_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jbyteArray JNICALL
Java_org_aralozkaya_msngr_protocol_SirenCodec_encode(JNIEnv *env, jobject self, jbyteArray input) {
    (void)self;
    jsize size = (*env)->GetArrayLength(env, input);
    if (size <= 0 || size > 480000 || size % 640 != 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, size / 16);
    if (!result) return NULL;
    jbyte *pcm = (*env)->GetByteArrayElements(env, input, NULL);
    jbyte *encoded = (*env)->GetByteArrayElements(env, result, NULL);
    if (!pcm || !encoded) {
        if (pcm) (*env)->ReleaseByteArrayElements(env, input, pcm, JNI_ABORT);
        if (encoded) (*env)->ReleaseByteArrayElements(env, result, encoded, JNI_ABORT);
        return NULL;
    }
    pthread_mutex_lock(&codec_lock);
    SirenEncoder encoder = Siren7_NewEncoder(16000);
    int error = !encoder;
    for (int offset = 0; !error && offset < size; offset += 640)
        error = Siren7_EncodeFrame(encoder, (unsigned char *)pcm + offset, (unsigned char *)encoded + offset / 16);
    if (encoder) Siren7_CloseEncoder(encoder);
    pthread_mutex_unlock(&codec_lock);
    (*env)->ReleaseByteArrayElements(env, input, pcm, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, result, encoded, 0);
    return error ? NULL : result;
}

JNIEXPORT jbyteArray JNICALL
Java_org_aralozkaya_msngr_protocol_SirenCodec_decode(JNIEnv *env, jobject self, jbyteArray input) {
    (void)self;
    jsize size = (*env)->GetArrayLength(env, input);
    if (size <= 0 || size > 30000 || size % 40 != 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, size * 16);
    if (!result) return NULL;
    jbyte *encoded = (*env)->GetByteArrayElements(env, input, NULL);
    jbyte *pcm = (*env)->GetByteArrayElements(env, result, NULL);
    if (!pcm || !encoded) {
        if (encoded) (*env)->ReleaseByteArrayElements(env, input, encoded, JNI_ABORT);
        if (pcm) (*env)->ReleaseByteArrayElements(env, result, pcm, JNI_ABORT);
        return NULL;
    }
    pthread_mutex_lock(&codec_lock);
    SirenDecoder decoder = Siren7_NewDecoder(16000);
    int error = !decoder;
    for (int offset = 0; !error && offset < size; offset += 40)
        error = Siren7_DecodeFrame(decoder, (unsigned char *)encoded + offset, (unsigned char *)pcm + offset * 16);
    if (decoder) Siren7_CloseDecoder(decoder);
    pthread_mutex_unlock(&codec_lock);
    (*env)->ReleaseByteArrayElements(env, input, encoded, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, result, pcm, 0);
    return error ? NULL : result;
}
