#include "nexonic_torrent.h"
#include <jni.h>
#include <string>
#include <cstdint>
#include <cstring>
static std::string bytes(JNIEnv* env, jbyteArray value) {
    if (!value) return {};
    auto size=env->GetArrayLength(value); std::string data(static_cast<std::size_t>(size),'\0');
    env->GetByteArrayRegion(value,0,size,reinterpret_cast<jbyte*>(data.data())); return data;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_htorrent_mobile_torrent_NativeTorrent_create(JNIEnv* env,jobject,jbyteArray config) {
    auto value=bytes(env,config); return reinterpret_cast<std::intptr_t>(ndm_torrent_create(value.c_str()));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_htorrent_mobile_torrent_NativeTorrent_call(JNIEnv* env,jobject,jlong handle,jbyteArray request) {
    auto value=bytes(env,request); char* result=ndm_torrent_call(reinterpret_cast<void*>(static_cast<std::intptr_t>(handle)),value.c_str());
    if (!result) return nullptr;
    auto size=static_cast<jsize>(std::strlen(result)); auto array=env->NewByteArray(size);
    if (array) env->SetByteArrayRegion(array,0,size,reinterpret_cast<jbyte*>(result));
    ndm_torrent_free(result); return array;
}
extern "C" JNIEXPORT void JNICALL Java_com_htorrent_mobile_torrent_NativeTorrent_destroy(JNIEnv*,jobject,jlong handle) {
    ndm_torrent_destroy(reinterpret_cast<void*>(static_cast<std::intptr_t>(handle)));
}
