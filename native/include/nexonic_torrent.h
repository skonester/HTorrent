#ifndef NEXONIC_TORRENT_H
#define NEXONIC_TORRENT_H
#include <stddef.h>
#if defined(_WIN32) && !defined(NDM_TORRENT_STATIC)
# ifdef NDM_TORRENT_EXPORTS
#  define NDM_API __declspec(dllexport)
# else
#  define NDM_API __declspec(dllimport)
# endif
#else
# define NDM_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
/* UTF-8 JSON requests/results. Every result is owned by caller: free with ndm_torrent_free.
   A context owns its native libtorrent session. Calls are serialized internally.
   No exception crosses the C ABI. No process, shell or Python runtime is used. */
NDM_API void* ndm_torrent_create(const char* config_json);
NDM_API char* ndm_torrent_call(void* context, const char* request_json);
NDM_API void ndm_torrent_free(char* result);
NDM_API void ndm_torrent_destroy(void* context);
NDM_API const char* ndm_torrent_version(void);
#ifdef __cplusplus
}
#endif
#endif
