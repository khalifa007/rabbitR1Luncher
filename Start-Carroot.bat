@echo off
echo Starting Root Shell Daemon for R1 Launcher...
adb wait-for-device
adb shell "nohup nc -L -p 1337 sh > /dev/null 2>&1 &"
echo Daemon started successfully! You can now use Wi-Fi and Cellular toggles.
pause
