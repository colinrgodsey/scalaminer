ScalaMiner
==========

**Overview**

ScalaMiner is a Scala/JVM implementation of several mining abilities available through software such as CGMiner and BFGMiner. This mining platform will give you a single process that can handle all your mining device needs. Compatible with popular LTC/BTC mining devices (Scrypt/SHA256) and more in the works, this will give you a stable, unified, cross-platform solution. It utilizes Akka actors for concurrency and usb4java/javax.usb for usb access. 

**Goals**

* Provide a performant but fault-tolerant implementation of popular miner control.
* Provide a portable and modular platform for mining device control, in which new driver implementation is neither destructive nor incompatible with existing drivers.
* Provide a more efficient and performant stratum proxy for older devices.
* Utilize reactive programming principles to reduce overhead and enhance scalability. 
* Implement functionality in a distributed way that will take advantage of multi-core systems.
* Support ARM devices, and keep a small RAM footprint (less than 100 MB)

**Libraries**

scrypt (https://github.com/wg/scrypt): offers a pluggable native driver for extra performance
usb4java (https://github.com/usb4java/usb4java): requires libusb native drive. See their instructions on how to compile it if the included drivers are broken are don’t include your platform.
* Akka
* Spray
* SLF4J

Akka actors provide a controlled mutability context within a parallel processing environment. This allows reactive principles to be implemented while relying on sequential access patterns for mutable data. This ultimately gives us an environment in which we can update state while seamlessly operating in a multi-threaded process. This functional distribution of concurrency units allows us to take advantage of a multi-core environment while respecting cache-coherency and freeing ourselves from mutexes and wait states. While avoiding wait/blocking states, we’re able to keep a lower thread count reducing context-switching and thread stack overhead.

**Known Issues**

libusb on OSX seems to have trouble sometimes. Look into compiling your own native driver, or help out the fine folks at libusb and/or usb4java diagnose the issue. 
