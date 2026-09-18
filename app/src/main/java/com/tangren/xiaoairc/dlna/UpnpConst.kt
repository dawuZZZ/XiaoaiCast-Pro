package com.tangren.xiaoairc.dlna

import com.tangren.xiaoairc.BuildConfig

/**
 * UPnP / DLNA 常量与 XML 模板。
 *
 * XML 结构与 SOAP 报文格式直接沿用 MiAir（MIT License, KiriChen-Wind），
 * 这些模板是与网易云 / QQ 音乐 / QPlay 实际联调出来的，自己重写容易出玄学问题。
 */
object UpnpConst {

    const val SSDP_ADDR = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val SSDP_ALIVE_INTERVAL_MS = 30_000L

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AVTRANSPORT_URN = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL_URN = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER_URN = "urn:schemas-upnp-org:service:ConnectionManager:1"

    const val TRANSPORT_STATE_NO_MEDIA = "NO_MEDIA_PRESENT"
    const val TRANSPORT_STATE_STOPPED = "STOPPED"
    const val TRANSPORT_STATE_PLAYING = "PLAYING"
    const val TRANSPORT_STATE_PAUSED = "PAUSED_PLAYBACK"
    const val TRANSPORT_STATE_TRANSITIONING = "TRANSITIONING"

    const val UPNP_ERROR_INVALID_ACTION = 401
    const val UPNP_ERROR_INVALID_ARGS = 402
    const val UPNP_ERROR_ACTION_FAILED = 501
    const val UPNP_ERROR_TRANSITION_NOT_AVAILABLE = 701
    const val UPNP_ERROR_SEEK_MODE_NOT_SUPPORTED = 710

    /** ConnectionManager 对外声明的可接收格式 */
    const val SUPPORTED_PROTOCOLS =
        "http-get:*:audio/mpeg:*," +
            "http-get:*:audio/mp3:*," +
            "http-get:*:audio/mp4:*," +
            "http-get:*:audio/ogg:*," +
            "http-get:*:audio/flac:*," +
            "http-get:*:audio/x-flac:*," +
            "http-get:*:audio/wav:*," +
            "http-get:*:audio/x-wav:*," +
            "http-get:*:audio/aac:*," +
            "http-get:*:audio/x-aac:*," +
            "http-get:*:audio/x-m4a:*," +
            "http-get:*:audio/x-ms-wma:*," +
            "http-get:*:audio/L16:*," +
            "http-get:*:audio/vnd.dlna.adts:*," +
            "http-get:*:audio/ape:*," +
            "http-get:*:audio/*:*"

    fun deviceDescriptionXml(udn: String, friendlyName: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"
      xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>$DEVICE_TYPE</deviceType>
    <friendlyName>${esc(friendlyName)}</friendlyName>
    <manufacturer>XiaoaiDLNA</manufacturer>
    <manufacturerURL>https://github.com/dawuZZZ/XiaoaiCast-Pro</manufacturerURL>
    <modelDescription>XiaoaiDLNA - Xiaomi Speaker DLNA Audio Renderer</modelDescription>
    <modelName>XiaoaiDLNA Speaker</modelName>
    <modelNumber>${BuildConfig.VERSION_NAME}</modelNumber>
    <serialNumber>1</serialNumber>
    <UDN>uuid:$udn</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <dlna:X_DLNACAP>audio-only</dlna:X_DLNACAP>
    <qq:X_QPlay_SoftwareCapability xmlns:qq="http://www.tencent.com">QPlay:2</qq:X_QPlay_SoftwareCapability>
    <serviceList>
      <service>
        <serviceType>$AVTRANSPORT_URN</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/device/$udn/AVTransport.xml</SCPDURL>
        <controlURL>/device/$udn/AVTransport/control</controlURL>
        <eventSubURL>/device/$udn/AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>$RENDERING_CONTROL_URN</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/device/$udn/RenderingControl.xml</SCPDURL>
        <controlURL>/device/$udn/RenderingControl/control</controlURL>
        <eventSubURL>/device/$udn/RenderingControl/event</eventSubURL>
      </service>
      <service>
        <serviceType>$CONNECTION_MANAGER_URN</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/device/$udn/ConnectionManager.xml</SCPDURL>
        <controlURL>/device/$udn/ConnectionManager/control</controlURL>
        <eventSubURL>/device/$udn/ConnectionManager/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""

    fun soapResponse(serviceUrn: String, action: String, params: Map<String, String>): String {
        val body = params.entries.joinToString("\n") { "        <${it.key}>${esc(it.value)}</${it.key}>" }
        val bodyBlock = if (body.isEmpty()) "" else "$body\n"
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="$serviceUrn">
$bodyBlock    </u:${action}Response>
  </s:Body>
</s:Envelope>"""
    }

    fun soapFault(errorCode: Int, errorDescription: String): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$errorCode</errorCode>
          <errorDescription>${esc(errorDescription)}</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>"""

    /** GENA 事件通知体：LastChange 里的内容整体转义，这是标准做法 */
    fun lastChangeEvent(transportState: String, volume: Int): String {
        val parts = StringBuilder()
        if (transportState.isNotEmpty()) {
            parts.append("""<TransportState val="$transportState"/>""")
        }
        if (volume >= 0) {
            parts.append("""<Volume channel="Master" val="$volume"/>""")
        }
        val inner = """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">""" +
            """<InstanceID val="0">$parts</InstanceID></Event>"""
        return """<?xml version="1.0" encoding="utf-8"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
  <e:property>
    <LastChange>${esc(inner)}</LastChange>
  </e:property>
</e:propertyset>"""
    }

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
