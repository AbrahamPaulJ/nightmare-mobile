# The plugin runtime: QuickJS plus our JNI bridge, as libnmjs.so.
#
# ⚠ This deliberately does NOT add_subdirectory() quickjs-ng's own CMakeLists.
# That file carries ~15 options, install targets, a CLI, a bytecode compiler and
# sanitizer wiring, none of which we want inside an APK build -- and every one of
# them is a thing that can change under us on a version bump. The library is four
# C files; listing them is both smaller and more stable than inheriting a build
# system.
#
# ⚠ The sources are fetched, not committed: tools/fetch_quickjs.ps1 (pinned tag +
# sha256). A clean clone must run it before Gradle, and the check below says so
# rather than failing 200 lines later with "no such file".

cmake_minimum_required(VERSION 3.22.1)
project(nmjs C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(QJS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs)

if(NOT EXISTS ${QJS}/quickjs.c)
    message(FATAL_ERROR
        "QuickJS sources are missing from ${QJS}.\n"
        "Run tools/fetch_quickjs.ps1 first (they are fetched, not committed).")
endif()

add_library(nmjs SHARED
    nmjs.c
    ${QJS}/quickjs.c
    ${QJS}/libregexp.c
    ${QJS}/libunicode.c
    ${QJS}/dtoa.c
)

target_include_directories(nmjs PRIVATE ${QJS})

# _GNU_SOURCE and -funsigned-char are quickjs-ng's own build requirements, not
# preferences; char signedness in particular changes how its bytecode reader
# behaves on arm64, where char is unsigned by default anyway -- being explicit
# keeps it that way if the toolchain ever disagrees.
target_compile_definitions(nmjs PRIVATE _GNU_SOURCE)
target_compile_options(nmjs PRIVATE
    -funsigned-char
    # ⚠ -O2 even in a debug build. This is an interpreter: the difference
    # between -O0 and -O2 is the difference between a node that costs
    # microseconds and one that shows up in a render budget, and nobody is
    # going to step through quickjs.c in a debugger.
    -O2
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-missing-field-initializers
    -Wno-implicit-fallthrough
    -Wno-array-bounds
)

find_library(log-lib log)
target_link_libraries(nmjs ${log-lib})
