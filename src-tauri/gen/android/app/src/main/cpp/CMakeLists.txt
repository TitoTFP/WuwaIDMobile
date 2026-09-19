cmake_minimum_required(VERSION 3.22.1)
project(wuwa_root_helper LANGUAGES CXX)

if(NOT DEFINED WUWA_APP_PACKAGE)
    set(WUWA_APP_PACKAGE "com.titotfp.wuwaid")
endif()

add_executable(wuwa_root_helper root_file_helper.cpp)
target_compile_definitions(wuwa_root_helper PRIVATE WUWA_APP_PACKAGE="${WUWA_APP_PACKAGE}")
set_target_properties(wuwa_root_helper PROPERTIES
    OUTPUT_NAME "libwuwa_root_helper"
    SUFFIX ".so"
)
target_compile_features(wuwa_root_helper PRIVATE cxx_std_20)
target_compile_options(wuwa_root_helper PRIVATE -Wall -Wextra -Werror -fPIE)
target_link_options(wuwa_root_helper PRIVATE -fPIE -pie)
