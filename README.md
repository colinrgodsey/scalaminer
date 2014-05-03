ScalaMiner
==========

**Overview**

ScalaMiner is a Scala/JVM implementation of several mining abilities available through
software such as CGMiner and BFGMiner. This mining platform will give you a single
process that can handle all your mining device needs. Compatible with popular
LTC/BTC mining devices (Scrypt/SHA256) and more in the works, this will give
you a stable, unified, cross-platform solution. It utilizes Akka actors for
concurrency and usb4java/javax.usb for usb access.

**Goals**

* Provide a performant but fault-tolerant implementation of popular miner control.
* Provide a portable and modular platform for mining device control, in which new driver
implementation is neither destructive nor incompatible with existing drivers.
* Provide a more efficient and performant stratum proxy for older devices.
* Provide a durable hot-swap system that is tolerant of device failures. No more segfaults
or disalbed devices!
* Utilize reactive programming principles to reduce overhead and enhance scalability. 
* Implement functionality in a distributed way that will take advantage of multi-core systems.
* Support ARM devices, and keep a small RAM footprint (less than 100 MB)

**Libraries**

* [scrypt](https://github.com/wg/scrypt) - offers a pluggable native driver for extra performance
* [usb4java](https://github.com/usb4java/usb4java) - requires libusb native driver.
See their instructions on how to compile it if the included drivers are broken or don’t
include your platform.
* io.Usb - A fully reactive implementation of libusb using the akka.io framework, written
 specifically for ScalaMiner. Currently unpublished, but the code is available in this repo.
* [Akka](http://akka.io/)
* [Spray](http://spray.io/)
* SLF4J

**Benefits**

Akka actors provide a controlled mutability context within a parallel processing environment.
This allows reactive principles to be implemented while relying on sequential access patterns
for mutable data. This ultimately gives us an environment in
which we can update state while seamlessly operating in a multi-threaded process.
This functional distribution of concurrency units allows us to take advantage of a
multi-core environment while respecting cache-coherency and freeing ourselves from
mutexes and wait states. While avoiding wait/blocking states, we’re able to keep a
lower thread counts, reduce context-switching and thread stack overhead.

**Devices**

* [Stratum](http://mining.bitcoin.cz/stratum-mining) Proxy - implements a
GetWork proxy that can be used with older devices to connect
to a stratum pool. The fully concurrent proxy will give you much improved performance over
the single-thread [Python proxy](https://github.com/slush0/stratum-mining-proxy) by Slush.
* [GridSeed](http://gridseed.com/) [DualMiner](http://www.dualminer.com/) -
Support for either dual LTC/BTC or single LTC/BTC. No special procedure
for dual mode! Just configure both a scrypt and sha256 stratum, set the switch on the device,
and plug it in. Dual mode is detected per-device by only the switch, enabling you to run some
devices in dual and some in single, all from the same process.
* [GridSeed](http://gridseed.com/) 5-Chip 320kH/s ASIC GC3355 - Currently only
LTC mode implemented (still trying to find non-conflicting specs).
* [Butterfly Labs](http://www.butterflylabs.com/) BitForce SC - Single device
only (no XLINK support). Has been tested with the 7.18 GH/s upgrade Jalapeno, should work
with regular Jalapeno devices and the Little Singles.

**Known Issues**

libusb on OSX seems to have trouble sometimes (especially 10.9). Look into [compiling
your own usb4java native driver](http://usb4java.org/nativelibs.html), or help
out the fine folks at libusb and/or usb4java diagnose the issue.


**[License](./LICENSE)**
ScalaMiner is offered as open source under GPL v3. Future licensing may allow closed-source
implementations of plugins/drivers (different license for plugin/common libraries).