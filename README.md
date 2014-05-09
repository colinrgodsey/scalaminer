ScalaMiner
==========

ScalaMiner is a Scala/JVM implementation of several mining abilities available through
software such as CGMiner and BFGMiner. This mining platform will give you a single
performant process that can handle all of your mining device needs. Compatible with popular
LTC/Scrypt and BTC/SHA256 mining devices (and more in the works), ScalaMiner will give
you a stable, unified, cross-platform solution. It utilizes Akka actors for
concurrency and usb4java/libusb for usb device control.

**Goals**

* Provide a performant but fault-tolerant implementation of popular miner control.
* Provide a portable and modular platform for mining device control, in which new driver
implementation is neither destructive nor incompatible with existing drivers.
* Provide a more efficient and performant stratum proxy for older devices.
* Provide a durable hot-swap system that is tolerant of device failures. No more segfaults
or disabled devices!
* Utilize reactive programming principles to reduce overhead and enhance scalability. 
* Implement functionality in a distributed way that will take advantage of multi-core systems.
* Support ARM devices, and keep a small RAM footprint (less than 100 MB)
* Minimize kernel load to improve latency with USB and network devices.
* Provide support for alternate JVMs (currently only tested in HotSpot 1.7 for ARM and x64)

**Libraries**

* [scrypt](https://github.com/wg/scrypt) - Offers a pluggable native driver for extra performance
* [usb4java](https://github.com/usb4java/usb4java) - requires libusb native driver.
See their instructions on how to compile it if the included drivers are broken or don’t
include your platform.
* io.Usb - A fully reactive implementation of libusb using the akka.io framework, written
 specifically for ScalaMiner. Currently unpublished, but the code is available in this repo.
* [Akka](http://akka.io/) - Amazingly strong actor-based concurrency platform for Scala/Java.
* [Spray](http://spray.io/) - Akka actor based HTTP library using the akka.io framework.
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

* [Stratum](http://mining.bitcoin.cz/stratum-mining) Proxy - Implements a
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
* [Bitmain](https://bitmaintech.com/) AntMiner U1/U2
* ASICMiner Block Erupter USB

**Hardware requests**

The current device support for ScalaMiner is based on available access to mining hardware.
If you are a hardware manufacturer/retailer/aficionado please file an issue request
or contact me (colinrgodsey) otherwise to get the device support added!

**Donations**

Donations are greatly appreciated, but there are alternate ways to support the project.
Hardware availability is the biggest factor limiting device support. If you are a HW owner and
developer, obviously the best way to contribute would be to create the driver for it!
Otherwise the best way to help is hardware donations and/or loans; this also tends to
have a much quicker turnaround for development (who wants to leave unused HW sitting there?).
Other remote development options may be available, but can be more inconvenient for each side.
If you have questions or are interested in one of these contribution paths,
open up a GitHub Issue or contact me (colinrgodsey) otherwise.

For classic coin donations, we accept BTC at 1HmmCUmGr1KYWRMosVa299z9NC9SRxyr8h

**Compiling/Configuring/Running**

* The project is managed with SBT. Running from source should be as simple as *sbt run*.
* To prepare a bundled jar, use *sbt assembly*. This will create a jar with all deps and
the default config in reference.conf (override using application.conf in the base dir).
* See the Known Issues section below for instructions on how to compile the native driver if one
of the provided ones doesn't work for you.
* Configuration is done using Typesafe Config. Refer to the
[*reference.conf*](https://github.com/colinrgodsey/scalaminer/blob/master/src/main/resources/reference.conf)
file for config fields/structure. Place your overrides in *application.conf* in the base
directory. Make sure to configure your pools, or it uses the default (as seen in *reference.conf*)!
* More info coming soon....

**Known Issues**

* libusb on OSX seems to have trouble sometimes (especially OSX 10.9). Look into [compiling
your own usb4java native driver](http://usb4java.org/nativelibs.html), or help
out the fine folks at libusb and/or usb4java diagnose the issue.
* No rules file for Debian/Ubuntu. Should be able to use the same one from
[cgminer](https://github.com/ckolivas/cgminer/blob/master/01-cgminer.rules) and add your
devices. Or just run the thing as root.

**[License](https://raw.githubusercontent.com/colinrgodsey/scalaminer/master/LICENSE)**
==========

ScalaMiner is offered as open source under GPL v3. Future licensing may allow closed-source
implementations of plugins/drivers (different license for plugin/common libraries).
