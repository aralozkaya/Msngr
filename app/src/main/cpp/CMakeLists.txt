cmake_minimum_required(VERSION 3.22.1)
project(msngr_siren C)
add_library(msngr_siren SHARED
    siren_jni.c libsiren/common.c libsiren/dct4.c libsiren/rmlt.c
    libsiren/huffman.c libsiren/encoder.c libsiren/decoder.c)
target_link_libraries(msngr_siren m)
target_compile_options(msngr_siren PRIVATE -Wall -Wextra -fstack-protector-strong)
