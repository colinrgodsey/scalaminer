OSX will require manually unloading of certain serial drivers.

*MCP2210*

There is no known way to disable the HID driver this uses. Doing so results in most of your
peripherals stopping.


*FTDI*

sudo kextunload -bundle-id com.apple.driver.AppleUSBFTDI


*STM*

sudo kextunload /System/Library/Extensions/IOUSBFamily.kext/Contents/PlugIns/AppleUSBCDC.kext

sudo kextunload /System/Library/Extensions/IOUSBFamily.kext/Contents/PlugIns/AppleUSBCDCACMControl.kext

sudo kextunload /System/Library/Extensions/IOUSBFamily.kext/Contents/PlugIns/AppleUSBCDCACMData.kext