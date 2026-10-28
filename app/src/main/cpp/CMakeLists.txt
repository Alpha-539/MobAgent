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

# TEZLIK: CPU backend'ning BARCHA variantlari alohida kutubxona (libggml-cpu-*.so)
# sifatida quriladi va ilova ishga tushganda telefon protsessoriga eng mos
# varianti (dotprod / i8mm / SVE ...) AVTOMATIK tanlanadi (llama-android.cpp'da
# ggml_backend_load_all_from_path). Statik -march bayrog'i esa yangi buyruqlarni
# eski telefonlarda SIGILL (qulash) bilan yakunlantirardi — bu yo'l xavfsiz.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# 16 KB sahifa hizalanishi: variant modullari (MODULE) uchun ham.
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

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
