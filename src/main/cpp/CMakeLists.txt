cmake_minimum_required(VERSION 3.22.1)
project(fastpngnative)

add_library(
    fastpngnative
    SHARED
    native_image_pipeline.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_compile_features(fastpngnative PRIVATE cxx_std_20)

target_link_libraries(
    fastpngnative
    PRIVATE
    ${log-lib}
    ${jnigraphics-lib}
)
