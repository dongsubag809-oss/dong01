cmake_minimum_required(VERSION 3.22.1)
project(energyblue_serial C)
add_library(energyblue_serial SHARED serial.c)
find_library(log-lib log)
target_link_libraries(energyblue_serial ${log-lib})

add_library(energyblue_sensor SHARED sensor_serial.c)
target_link_libraries(energyblue_sensor ${log-lib})
