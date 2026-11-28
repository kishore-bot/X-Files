#ifndef XFILES_ENGINE_H
#define XFILES_ENGINE_H

typedef struct XfilesIosJob XfilesIosJob;
XfilesIosJob * _Nonnull xfiles_ios_job_new(void);
void xfiles_ios_job_cancel(XfilesIosJob * _Nonnull job);
void xfiles_ios_job_free(XfilesIosJob * _Nonnull job);

// The input is borrowed. The returned JSON must be released by xfiles_ios_string_free.
char * _Nullable xfiles_ios_call(const char * _Nonnull input, XfilesIosJob * _Nonnull job);
void xfiles_ios_string_free(char * _Nullable value);

#endif
