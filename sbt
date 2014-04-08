#!/bin/sh
test -f ~/.sbtconfig && . ~/.sbtconfig
exec java \
 -XX:ParallelGCThreads=1 \
-Xms20m -Xmx256m -XX:MaxPermSize=128m \
-XX:+UseParallelGC -Xminf=10 -Xmaxf=15  \
-XX:CompileThreshold=1500 \
-Dcom.sun.management.jmxremote \
-Dcom.sun.management.jmxremote.authenticate=false \
-Dcom.sun.management.jmxremote.ssl=false \
-Dcom.sun.management.jmxremote.port=1100 \
-Djava.rmi.server.hostname=192.168.0.107 \
-XX:+AggressiveOpts -server \
-XX:ReservedCodeCacheSize=100m -XX:+UseFastAccessorMethods \
-XX:+BackgroundCompilation -XX:+UseCodeCacheFlushing \
-XX:MaxGCPauseMillis=10 -XX:+UseTLAB \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 \
	 ${SBT_OPTS} -jar sbt-launch.jar "$@"
