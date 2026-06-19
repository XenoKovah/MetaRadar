package com.darkmentor.data.helpers

/**
 * Bluetooth SIG service-class UUID → human-readable name table.
 *
 * Source: https://bitbucket.org/bluetooth-SIG/public/src/main/assigned_numbers/uuids/service_class.yaml
 *
 * Used for the SDP Services section in DeviceDetails so a 0x110A row reads
 * "0x110A — Audio Source" instead of "0x110A — Unknown". The map is keyed by the 16-bit
 * SIG-allocated service class identifier (the `0x1100..0x1FFF` range).
 *
 * To regenerate from a fresh assigned-numbers checkout:
 *
 * ```
 * python3 -c "
 *   import re
 *   with open('public/assigned_numbers/uuids/service_class.yaml') as f:
 *       s = f.read()
 *   for uuid, name in re.findall(r'-\\s+uuid:\\s+0x([0-9A-Fa-f]+)\\s*\\n\\s+name:\\s+([^\\n]+)', s):
 *       n = name.strip().strip(\"'\").strip('\"').replace('\"', '\\\\\"')
 *       print(f'    0x{uuid.upper()} to \"{n}\",')
 * "
 * ```
 */
object SdpServiceClassNames {

    private val NAMES_BY_UUID16: Map<Int, String> = mapOf(
        0x1000 to "ServiceDiscoveryServerServiceClassID",
        0x1001 to "BrowseGroupDescriptorServiceClassID",
        0x1101 to "SerialPort",
        0x1102 to "LANAccessUsingPPP",
        0x1103 to "Dial-Up Networking",
        0x1104 to "IrMCSync",
        0x1105 to "OBEXObjectPush",
        0x1106 to "OBEX File Transfer",
        0x1107 to "IrMCSyncCommand",
        0x1108 to "Headset",
        0x1109 to "CordlessTelephony",
        0x110A to "Audio Source",
        0x110B to "Audio Sink",
        0x110C to "A/V Remote Control Target",
        0x110D to "Advanced Audio Distribution",
        0x110E to "A/V Remote Control",
        0x110F to "A/V Remote Control Controller",
        0x1110 to "Intercom",
        0x1111 to "Fax",
        0x1112 to "Headset Audio Gateway",
        0x1113 to "WAP",
        0x1114 to "WAP_CLIENT",
        0x1115 to "PANU",
        0x1116 to "NAP",
        0x1117 to "GN",
        0x1118 to "DirectPrinting",
        0x1119 to "ReferencePrinting",
        0x111A to "Imaging",
        0x111B to "Imaging Responder",
        0x111C to "Imaging Automatic Archive",
        0x111D to "Imaging Referenced Objects",
        0x111E to "Hands-Free",
        0x111F to "AG Hands-Free",
        0x1120 to "DirectPrintingReferencedObjectsService",
        0x1121 to "ReflectedUI",
        0x1122 to "BasicPrinting",
        0x1123 to "PrintingStatus",
        0x1124 to "HID",
        0x1125 to "HardcopyCableReplacement",
        0x1126 to "HCR_Print",
        0x1127 to "HCR_Scan",
        0x1128 to "Common_ISDN_Access",
        0x112D to "SIM Access",
        0x112E to "Phonebook Access Client",
        0x112F to "Phonebook Access Server",
        0x1130 to "Phonebook Access Profile",
        0x1131 to "Headset - HS",
        0x1132 to "Message Access Server",
        0x1133 to "Message Notification Server",
        0x1134 to "Message Access Profile",
        0x1135 to "GNSS",
        0x1136 to "GNSS_Server",
        0x1137 to "3D Display",
        0x1138 to "3D Glasses",
        0x1139 to "3D Synch Profile",
        0x113A to "Multi Profile Specification",
        0x113B to "MPS",
        0x113C to "CTN Access Service",
        0x113D to "CTN Notification Service",
        0x113E to "Calendar Tasks and Notes Profile",
        0x1200 to "PnPInformation",
        0x1201 to "Generic Networking",
        0x1202 to "GenericFileTransfer",
        0x1203 to "Generic Audio",
        0x1204 to "GenericTelephony",
        0x1205 to "UPNP_Service",
        0x1206 to "UPNP_IP_Service",
        0x1300 to "ESDP_UPNP_IP_PAN",
        0x1301 to "ESDP_UPNP_IP_LAP",
        0x1302 to "ESDP_UPNP_L2CAP",
        0x1303 to "Video Source",
        0x1304 to "Video Sink",
        0x1305 to "Video Distribution",
        0x1400 to "HDP",
        0x1401 to "HDP Source",
        0x1402 to "HDP Sink",
    )

    /**
     * Bluetooth-SIG profile / spec summaries keyed by service-class UUID. Used as a fallback
     * Purpose when CLUES doesn't have a community-curated entry for a SIG-allocated service
     * class. Hand-written from the published profile specs — kept short so it renders cleanly
     * under the same expansion-arrow UI that CLUES purposes use.
     */
    private val PURPOSES_BY_UUID16: Map<Int, String> = mapOf(
        0x1000 to "Service Discovery Protocol (SDP) server itself — every device exposes this so peers can browse its service-class list.",
        0x1001 to "Top-level browse group for SDP service hierarchies.",
        0x1101 to "Serial Port Profile (SPP). Emulates an RS-232 serial cable over RFCOMM. Used by legacy / industrial gear, GPS receivers, OBD-II adapters, and many embedded modules.",
        0x1102 to "LAN Access using PPP — pre-PAN method of routing IP over Bluetooth via a PPP server.",
        0x1103 to "Dial-up Networking Profile (DUN). Lets a host dial out via the phone's modem; largely superseded by tethering but still in some carrier-disabled use.",
        0x1104 to "IrMC Synchronization profile. Phonebook / calendar sync (legacy Nokia-era PIM sync).",
        0x1105 to "OBEX Object Push (OPP). One-shot 'push a vCard / vCal / file' transfer.",
        0x1106 to "OBEX File Transfer Profile (FTP). Browse-and-fetch file system access between peers.",
        0x1107 to "IrMC Synchronization Command service — paired with IrMCSync (0x1104).",
        0x1108 to "Headset Profile (HSP) — narrowband mono audio + basic call control. Predates HFP.",
        0x1109 to "Cordless Telephony Profile — DECT-style cordless extensions over Bluetooth.",
        0x110A to "A2DP Source role — high-quality stereo audio streaming source. Phones, computers, and music players advertise this when they can stream out.",
        0x110B to "A2DP Sink role — speakers, headphones, and car kits that receive A2DP audio.",
        0x110C to "AVRCP Target role — receives transport / volume commands. Headphones and speakers expose this.",
        0x110D to "Advanced Audio Distribution Profile (A2DP) parent service class.",
        0x110E to "AVRCP — media transport / metadata control profile parent service class.",
        0x110F to "AVRCP Controller role — sends transport / volume commands. Phones and watches advertise this.",
        0x1110 to "Intercom Profile — direct-to-handset voice between two phones.",
        0x1111 to "Fax Profile — send faxes through a connected phone or modem.",
        0x1112 to "Headset Profile audio gateway role (the phone-side peer of HSP).",
        0x1113 to "WAP service over Bluetooth (legacy mobile-internet bearer).",
        0x1114 to "WAP client role.",
        0x1115 to "PAN User (PANU) — Bluetooth IP-network client role.",
        0x1116 to "PAN Network Access Point (NAP) — Bluetooth IP gateway / hotspot role.",
        0x1117 to "PAN Group ad-hoc Network (GN) — peer-to-peer IP networking role.",
        0x1118 to "Basic Imaging — direct printing service.",
        0x1119 to "Basic Imaging — reference printing service.",
        0x111A to "Basic Imaging Profile (BIP) parent service class.",
        0x111B to "BIP Imaging Responder — device that holds / serves images.",
        0x111C to "BIP Automatic Archive — auto-pull images from camera to host.",
        0x111D to "BIP Referenced Objects — fetch images by handle.",
        0x111E to "Hands-Free Profile (HFP) hands-free role. Modern in-car kits, headsets, and Bluetooth earpieces; supports SCO / mSBC narrow- and wideband call audio plus dial / answer / volume.",
        0x111F to "HFP Audio Gateway role — the phone side of HFP.",
        0x1120 to "BPP Direct Printing Referenced Objects service.",
        0x1121 to "BPP Reflected UI service.",
        0x1122 to "Basic Printing Profile (BPP).",
        0x1123 to "BPP Printing Status service.",
        0x1124 to "Human Interface Device (HID). Keyboards, mice, game controllers, presenters, scanners — anything that delivers HID reports over L2CAP.",
        0x1125 to "Hardcopy Cable Replacement Profile (HCRP) — print over Bluetooth as a USB-printer-cable replacement.",
        0x1126 to "HCRP Print role.",
        0x1127 to "HCRP Scan role.",
        0x1128 to "Common ISDN Access — telephony bearer over Bluetooth.",
        0x112D to "SIM Access Profile (SAP) — let a car kit use the phone's SIM directly.",
        0x112E to "Phonebook Access Profile (PBAP) client — fetches contacts / call history (typical car kit role).",
        0x112F to "Phonebook Access Profile (PBAP) server — exposes contacts / call history to a peer.",
        0x1130 to "Phonebook Access Profile (PBAP) parent service class.",
        0x1131 to "Headset Profile — Headset (HS) role.",
        0x1132 to "Message Access Profile (MAP) Server — exposes SMS / MMS / email to a peer (used by car kits, watches).",
        0x1133 to "Message Access Profile (MAP) Notification Server — push new-message notifications.",
        0x1134 to "Message Access Profile (MAP) parent service class.",
        0x1135 to "Global Navigation Satellite System (GNSS) Profile — GPS data over Bluetooth.",
        0x1136 to "GNSS Server role.",
        0x1137 to "3D Synchronization Profile — display side (active-shutter glasses).",
        0x1138 to "3D Synchronization Profile — glasses side.",
        0x1139 to "3D Synchronization Profile parent service class.",
        0x113A to "Multi-Profile Specification (MPS) parent service class.",
        0x113B to "Multi-Profile Specification (MPS).",
        0x113C to "Calendar / Tasks / Notes Access Service.",
        0x113D to "Calendar / Tasks / Notes Notification Service.",
        0x113E to "Calendar / Tasks / Notes Profile parent service class.",
        0x1200 to "Device ID Profile — vendor ID, product ID, and version metadata so peers can identify the device model. Almost every modern Bluetooth peer advertises this.",
        0x1201 to "Generic Networking parent service class.",
        0x1202 to "Generic File Transfer parent service class.",
        0x1203 to "Generic Audio parent service class.",
        0x1204 to "Generic Telephony parent service class.",
        0x1205 to "UPnP Service over Bluetooth.",
        0x1206 to "UPnP-IP Service over Bluetooth.",
        0x1300 to "Extended Service Discovery — UPnP-IP over PAN.",
        0x1301 to "Extended Service Discovery — UPnP-IP over LAP.",
        0x1302 to "Extended Service Discovery — UPnP over L2CAP.",
        0x1303 to "Video Distribution Profile (VDP) Source role — stream video out.",
        0x1304 to "Video Distribution Profile (VDP) Sink role — display received video.",
        0x1305 to "Video Distribution Profile (VDP) parent service class.",
        0x1400 to "Health Device Profile (HDP) parent service class — medical / fitness sensors over Bluetooth.",
        0x1401 to "Health Device Profile Source role (sensor side).",
        0x1402 to "Health Device Profile Sink role (data-collector side).",
    )

    /**
     * Look up a service-class name from a UUID string in any common form:
     *   - 4-char hex ("110a")
     *   - "0x"-prefixed hex ("0x110A")
     *   - canonical 36-char dashed full UUID ("0000110a-0000-1000-8000-00805f9b34fb")
     * Returns null when the UUID doesn't have the SIG base or isn't in the assigned-numbers
     * service_class.yaml.
     */
    fun lookup(uuid: String): String? {
        val short16 = parseShortUuid16(uuid) ?: return null
        return NAMES_BY_UUID16[short16]
    }

    /**
     * Look up the SIG-spec Purpose summary for [uuid]. Same UUID shapes as [lookup]. Returns
     * null when the UUID isn't a SIG-base UUID or the service class isn't covered by
     * [PURPOSES_BY_UUID16]. Caller treats this as a fallback for the CLUES-driven Purpose UI
     * — it never overrides a CLUES community entry, only fills in when one is absent.
     */
    fun lookupPurpose(uuid: String): String? {
        val short16 = parseShortUuid16(uuid) ?: return null
        return PURPOSES_BY_UUID16[short16]
    }

    private fun parseShortUuid16(uuid: String): Int? {
        val s = uuid.trim().lowercase().removePrefix("0x")
        // 4-char hex shorthand: "110a"
        if (s.length == 4) return s.toIntOrNull(16)
        // SIG-base 128-bit form: "0000XXXX-0000-1000-8000-00805f9b34fb"
        if (s.length == 36 && s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return s.substring(4, 8).toIntOrNull(16)
        }
        return null
    }
}
