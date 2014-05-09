#!/bin/sh
test -f ~/.sbtconfig && . ~/.sbtconfig
exec java \
-XX:ParallelGCThreads=1 \
-Xms20m -Xmx256m -XX:MaxPermSize=128m \
-Xminf=10 -Xmaxf=15  \
-XX:CompileThreshold=1500 \
-XX:+AggressiveOpts -server \
-XX:ReservedCodeCacheSize=100m -XX:+UseFastAccessorMethods \
-XX:+BackgroundCompilation -XX:+UseCodeCacheFlushing \
-XX:MaxGCPauseMillis=10 -XX:+UseTLAB \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 \
	 ${SBT_OPTS} -jar sbt-launch.jar "$@"
