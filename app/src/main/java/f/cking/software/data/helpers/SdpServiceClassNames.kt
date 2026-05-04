package f.cking.software.data.helpers

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
