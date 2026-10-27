cmake_minimum_required(VERSION 3.22.1)
project(msngr_siren C)
find_package(Git REQUIRED)
set(SIREN_UPSTREAM "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libsiren")
set(SIREN_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/patches/libsiren-hardening.patch")
set(SIREN_PATCHED "${CMAKE_CURRENT_BINARY_DIR}/libsiren")
if(NOT EXISTS "${SIREN_UPSTREAM}/siren7.h")
    message(FATAL_ERROR "Initialize dependencies first: git submodule update --init")
endif()
# Keep upstream pristine. Reapply the reviewed safety fixes to build-local copies.
# A conflicting update must fail configuration instead of dropping the fixes.
file(GLOB SIREN_FILES CONFIGURE_DEPENDS "${SIREN_UPSTREAM}/*.c" "${SIREN_UPSTREAM}/*.h")
file(MAKE_DIRECTORY "${SIREN_PATCHED}")
foreach(SIREN_FILE IN LISTS SIREN_FILES)
    get_filename_component(SIREN_NAME "${SIREN_FILE}" NAME)
    configure_file("${SIREN_FILE}" "${SIREN_PATCHED}/${SIREN_NAME}" COPYONLY)
endforeach()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${SIREN_PATCH}")
# Stop Git discovering the outer repository so patch paths are relative to this copy.
execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env "GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}"
        "${GIT_EXECUTABLE}" apply --whitespace=nowarn "${SIREN_PATCH}"
    WORKING_DIRECTORY "${SIREN_PATCHED}"
    RESULT_VARIABLE SIREN_PATCH_RESULT ERROR_VARIABLE SIREN_PATCH_ERROR)
if(NOT SIREN_PATCH_RESULT EQUAL 0)
    message(FATAL_ERROR "libsiren safety patch failed; review the upstream update: ${SIREN_PATCH_ERROR}")
endif()
add_library(msngr_siren SHARED
    siren_jni.c "${SIREN_PATCHED}/common.c" "${SIREN_PATCHED}/dct4.c" "${SIREN_PATCHED}/rmlt.c"
    "${SIREN_PATCHED}/huffman.c" "${SIREN_PATCHED}/encoder.c" "${SIREN_PATCHED}/decoder.c")
target_include_directories(msngr_siren PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(msngr_siren m)
target_compile_options(msngr_siren PRIVATE -Wall -Wextra -fstack-protector-strong)
