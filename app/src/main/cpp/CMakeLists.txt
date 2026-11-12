cmake_minimum_required(VERSION 3.22.1)
project(oablaudio C)

add_library(oablaudio SHARED oabl_affinity.c)
target_link_libraries(oablaudio log)
