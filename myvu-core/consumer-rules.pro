# The MYVU SDK reaches hidden Bluetooth APIs through reflection; R8 must not
# strip or rename these classes in consuming apps.

# BluetoothDevice.createBond(int transport) via reflection.
-keep class dev.myvu.sdk.connection.Bonding { *; }

# BluetoothHeadset/BluetoothA2dp "connect" via reflection (best-effort profile
# connect so the glasses show "phone connected").
-keep class dev.myvu.sdk.connection.AudioProfiles { *; }

-dontwarn android.bluetooth.**
