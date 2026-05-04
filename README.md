<img src='/metadata/en-US/images/header.png'/>

# Bluetooth Radar

<!-- <a href='https://play.google.com/store/apps/details?id=f.cking.software&pcampaignid=pcampaignidMKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img alt='Get it on Google Play' src='https://github.com/BLE-Research-Group/MetaRadar/assets/18288554/dd3fc41a-9b51-4747-9a2c-57b37d4706aa' width='200'/></a> -->
<a href='https://github.com/BLE-Research-Group/MetaRadar/releases?q=Release+Build&expanded=true'><img alt='Get it on IzzyOnDroid' src='https://github.com/BLE-Research-Group/MetaRadar/assets/18288554/b1cb44a9-3fd4-4f83-8700-485c10b090ba' width='200'/></a>
<a href='https://f-droid.org/en/packages/f.cking.software/'><img alt='Get it on F-Droid' src='https://github.com/BLE-Research-Group/MetaRadar/assets/18288554/c03a0cf2-b39a-4344-adb8-d4cde7ce4b61' width='200'/></a>
<a href='https://android.izzysoft.de/repo/apk/f.cking.software'><img alt='Get it on IzzyOnDroid' src='https://github.com/BLE-Research-Group/MetaRadar/assets/18288554/c0c85c9f-edc8-4fc7-97b1-bda925bf0833' width='200'/></a>

> [!WARNING]
> This project is developed **solely for educational, security research, and personal investigative purposes**. **The author does not endorse or encourage any use of this software for unlawful or unethical activities**. You are **solely responsible** for ensuring your use of this tool complies with all applicable laws and regulations.

Bluetooth Low Energy (BLE) is a widely used wireless protocol that powers a huge variety of devices around you — from headphones, smartwatches, and fitness trackers to AirTags, IoT devices, game controllers, and even modern intimate gadgets. BLE devices communicate by broadcasting small packets of data, which can include device identifiers, metadata, and other information necessary for connectivity.

These broadcasts, while essential for device functionality, can also be used to track your presence and movements. For example, a cheap pair of wireless headphones may expose a persistent identifier publicly. Anyone listening to BLE packets nearby — whether governments, companies, or malicious actors — could potentially use that information to track you without your consent.

Fortunately, many modern devices implement privacy features in BLE to prevent tracking, such as randomized addresses and limited advertising. BLE Radar helps you distinguish between devices that protect your privacy and those that might be trackable. By analyzing BLE traffic in your surroundings, the app gives you insight into which devices are safe to use.

By making this app, the goal is to empower you with knowledge and control over the BLE devices in your environment. Understanding which devices are broadcasting trackable information and which are privacy-conscious allows you to make informed decisions about what you use, wear, and interact with daily.

In general, the app is capable:
* Scan and analyze Bluetooth devices around you;
* Filter and search the scanned device list;
* Connect to a device and enumerate its GATT services and characteristics, automatically reading every readable characteristic;
* Parse advertisement records into the AD types defined by the Bluetooth Core Specification Supplement;
* Capture every scan, GATT enumeration, and characteristic/descriptor read into a BTIDES-format JSON log that can be exported via ADB;
* Define the device type from advertised metadata;
* Estimate approximate distance to the device.

This application does not share your personal data or geolocation, all work is offline.

<img src='/metadata/en-US/images/phoneScreenshots/Screenshot_2.png' width='200'/> <img src='/metadata/en-US/images/phoneScreenshots/Screenshot_4.png' width='200'/> <img src='/metadata/en-US/images/phoneScreenshots/Screenshot_5.png' width='200'/> <img src='/metadata/en-US/images/phoneScreenshots/Screenshot_7.png' width='200'/>
