cmake_minimum_required(VERSION 3.22.1)
project(llama-android)

# MUHIM: llama.cpp GitHub Actions workflow'ida avtomatik klonlanadi
# (build.yml'dagi "llama.cpp manba kodini yuklab olish" bosqichi).
# Lokal build qilsangiz, quyidagini qo'lda bajaring:
#   git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp topilmadi. Lokal build uchun quyidagini bajaring:\n"
        "  git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Android API 26 uchun Vulkan yoqilmasligi kerak.
# vkGetPhysicalDeviceFeatures2 symboli API 26 libvulkan.so'da mavjud emas.
# Qurilmada Vulkan drayveri bo'lmasa ham ggml avtomatik CPU'ga qaytadi —
# bu xavfsiz va qulay.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(llama-android SHARED
    llama-android.cpp
)

target_include_directories(llama-android PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

find_library(log-lib log)

# Google Play (targetSdk 35+): 16 KB sahifali qurilmalar uchun native kutubxona 16 KB hizalangan bo'lishi shart.
# NDK 28+ buni o'zi qiladi; eski NDK (26/27) uchun linker bayrog'i.
target_link_options(llama-android PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(llama-android
    llama
    ggml
    ${log-lib}
    android
)
