# CMakeLists.txt - MNN-LLM JNI 原生库构建 (v11.1)
#
# 构建 mnn_llm_jni.so，依赖 MNN 原生库。
#
# 两种构建模式：
#   1. MNN 预编译模式（默认）：使用从 MNN GitHub Release 下载的预编译 .so
#   2. MNN 源码编译模式：从 MNN 源码编译（CI 中使用）
#
# 构建命令（本地开发）：
#   cd app/src/main/cpp
#   cmake -B build -DMNN_ROOT=/path/to/mnn -DANDROID_ABI=arm64-v8a
#   cmake --build build
#
# 构建命令（CI）：
#   由 .github/workflows/build-mnn-native.yml 自动执行

cmake_minimum_required(VERSION 3.22)
project(mnn_llm_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MNN 路径（预编译库或源码目录）
set(MNN_ROOT "" CACHE PATH "Path to MNN prebuilt libraries or source")

# JNI 源文件
add_library(mnn_llm_jni SHARED
    mnn_llm_jni.cpp
)

# 链接系统库
target_link_libraries(mnn_llm_jni
    android
    log
)

# 检查 MNN 是否可用
if(EXISTS "${MNN_ROOT}/include/MNN/MNNDefine.h")
    message(STATUS "MNN found at: ${MNN_ROOT}")
    target_compile_definitions(mnn_llm_jni PRIVATE MNN_LLM_AVAILABLE=1)

    # MNN 头文件
    target_include_directories(mnn_llm_jni PRIVATE
        ${MNN_ROOT}/include
        ${MNN_ROOT}/include/MNN
        ${MNN_ROOT}/tools/llm
    )

    # MNN 库
    find_library(MNN_LIB MNN PATHS ${MNN_ROOT}/lib NO_DEFAULT_PATH)
    find_library(MNN_LLM_LIB MNNLLM PATHS ${MNN_ROOT}/lib NO_DEFAULT_PATH)
    find_library(MNN_EXPR_LIB MNN_Express PATHS ${MNN_ROOT}/lib NO_DEFAULT_PATH)

    if(MNN_LIB AND MNN_LLM_LIB)
        target_link_libraries(mnn_llm_jni
            ${MNN_LIB}
            ${MNN_LLM_LIB}
            ${MNN_EXPR_LIB}
        )
    else()
        message(WARNING "MNN libraries not found, building stub")
        target_compile_definitions(mnn_llm_jni PRIVATE MNN_LLM_AVAILABLE=0)
    endif()
else()
    message(STATUS "MNN not found, building stub (no-op)")
    target_compile_definitions(mnn_llm_jni PRIVATE MNN_LLM_AVAILABLE=0)
endif()

# Android 特定设置
if(ANDROID)
    target_link_libraries(mnn_llm_jni log android)

    # OpenCL（GPU 加速）
    find_library(OPENCL_LIB OpenCL)
    if(OPENCL_LIB)
        target_link_libraries(mnn_llm_jni ${OPENCL_LIB})
        target_compile_definitions(mnn_llm_jni PRIVATE HAS_OPENCL=1)
    endif()
endif()
