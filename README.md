# Buzzer Trivia

This repository hosts the source code for my Bachelor's dissertation project

It's a buzzer app with some rudimentary trivia game elements, build for Android using **Kotlin** and **Bluetooth Low Energy (BLE)** for device communication. The app defines two roles: **host** and **player**, which correspond to the BLE roles of _central_ and _peripheral_ respectively. A host can connect to multiple players simultaneously, send them questions, and receive buzzes.

The primary focus of the project was ensuring **reliable communication** and a **"fair" implementation** of the basic game mechanics. For example, handling graceful disconnections or failed BLE operations, synchronizing question display across all player devices, and mitigating packet loss or delays.

This functionality, along with other BLE related mechanisms (operations queue, heartbeat mechanism, etc.) is found in `BluetoothService.kt`, which should offer a good example of how to build a basic yet robust Bluetooth Low Energy communication system for anyone interested.
