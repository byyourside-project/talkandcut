# USB camera bridge

Minimal Java USB/UVC bridge and prebuilt native libraries from
https://github.com/jiangdongguo/AndroidUSBCamera at tag 3.3.3,
commit cd2a301ff5bd76f70930fccfe4b8e1073edc51f7.
Native sources are available at that revision under libuvc/src/main/jni.
Copyright notices are retained in source; license texts are in licenses/.
Local changes: modern explicit USB permission broadcasts and receiver flags.
USB/UVC/UAC classes and their utility dependencies are included. The UAC bridge
provides microphone input when a board's DSP USB audio path cannot initialize.
