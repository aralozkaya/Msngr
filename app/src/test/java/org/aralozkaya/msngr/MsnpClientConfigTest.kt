package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.MessengerBannerAd
import org.aralozkaya.msngr.data.MessengerTabSite
import org.aralozkaya.msngr.data.MessengerTextAd
import org.aralozkaya.msngr.data.MsnpClientConfigService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MsnpClientConfigTest {
    @Test
    fun parsesWlm14MsgrConfigWithSlotsAdsAndToday() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <MsgrConfig>
                <LocalizedConfig Market="en-US">
                    <AdMainConfig>
                        <AdBanner20URL Refresh="300">http://ctsvcs.advertising.ugnet.xyz/ads/banner</AdBanner20URL>
                        <TextAdRotation>420</TextAdRotation>
                        <TextAdRefresh>1</TextAdRefresh>
                        <TextAdServer>http://ctsvcs.advertising.ugnet.xyz/ads/txt</TextAdServer>
                    </AdMainConfig>
                    <MsnTodayConfig>
                        <MsnTodaySiteID>6528</MsnTodaySiteID>
                        <MsnTodayURL>http://today.msgrsvcs.ctsrv.xyz/start?wlm=1</MsnTodayURL>
                    </MsnTodayConfig>
                    <TabConfig>
                        <RowNumber>1</RowNumber>
                        <slots>
                            <URL id="1">https://ctsvcs.advertising.ugnet.xyz/svcs/tab=1</URL>
                            <URL id="2">https://ctsvcs.advertising.ugnet.xyz/svcs/tab=2</URL>
                        </slots>
                    </TabConfig>
                </LocalizedConfig>
            </MsgrConfig>
        """.trimIndent()

        val parsed = MsnpClientConfigService.parseMsgrConfig(xml)
        assertEquals("https://today.msgrsvcs.ctsrv.xyz/start?wlm=1", parsed.todayUrl)
        assertEquals("https://ctsvcs.advertising.ugnet.xyz/ads/banner", parsed.bannerAdUrl)
        assertEquals(300, parsed.bannerRefreshSeconds)
        assertEquals("https://ctsvcs.advertising.ugnet.xyz/ads/txt", parsed.textAdUrl)
        assertEquals(420, parsed.textAdRotationSeconds)
        assertEquals(
            listOf(
                "1" to "https://ctsvcs.advertising.ugnet.xyz/svcs/tab=1",
                "2" to "https://ctsvcs.advertising.ugnet.xyz/svcs/tab=2",
            ),
            parsed.slotUrls,
        )
    }

    @Test
    fun parsesSoapGetClientConfigWithInlineTabs() {
        val soap = """
            <?xml version="1.0" encoding="utf-8" ?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                <soap:Body>
                    <GetClientConfigResponse xmlns="http://www.msn.com/webservices/Messenger/Client">
                        <GetClientConfigResult><![CDATA[<MsgrConfig>
                            <TabConfig>
                                <msntabdata>
                                    <tab>
                                        <type>page</type>
                                        <contenturl>http://wiby.me/</contenturl>
                                        <hiturl>http://wiby.me/</hiturl>
                                        <image>http://static.ugnet.xyz/svc/tab/wiby.png</image>
                                        <name>Wiby</name>
                                        <tooltip>Wiby Search</tooltip>
                                    </tab>
                                </msntabdata>
                            </TabConfig>
                            <LocalizedConfig Market="en-US">
                                <MsnTodayConfig>
                                    <MsnTodayURL>http://today.msgrsvcs.ctsrv.xyz/start?msn=1</MsnTodayURL>
                                </MsnTodayConfig>
                            </LocalizedConfig>
                        </MsgrConfig>]]></GetClientConfigResult>
                    </GetClientConfigResponse>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val parsed = MsnpClientConfigService.parseMsgrConfig(soap)
        assertEquals("https://today.msgrsvcs.ctsrv.xyz/start?msn=1", parsed.todayUrl)
        assertEquals(
            listOf(
                MessengerTabSite(
                    id = "1",
                    name = "Wiby",
                    tooltip = "Wiby Search",
                    contentUrl = "https://wiby.me/",
                    hitUrl = "https://wiby.me/",
                    iconUrl = "https://static.ugnet.xyz/svc/tab/wiby.png",
                ),
            ),
            parsed.inlineTabs,
        )
    }

    @Test
    fun parsesTabSlotAdXml() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <ads>
                <tabad>
                    <type>page</type>
                    <contenturl>http://theoldnet.com</contenturl>
                    <hiturl>http://theoldnet.com</hiturl>
                    <image>http://static.ugnet.xyz/svc/tab/theoldnet.png</image>
                    <name>TheOldNet</name>
                    <tooltip>TheOldNet</tooltip>
                </tabad>
            </ads>
        """.trimIndent()

        assertEquals(
            MessengerTabSite(
                id = "2",
                name = "TheOldNet",
                tooltip = "TheOldNet",
                contentUrl = "https://theoldnet.com",
                hitUrl = "https://theoldnet.com",
                iconUrl = "https://static.ugnet.xyz/svc/tab/theoldnet.png",
            ),
            MsnpClientConfigService.parseTabSlotXml(xml, slotId = "2"),
        )
    }

    @Test
    fun parsesBannerHtmlAndTextAdXmlAndRejectsUnsafeSchemes() {
        val html = """
            <!DOCTYPE HTML><html><body><a href="https://gdsk.retrosite.org" target="_blank"><img border="0" src="http://static.ugnet.xyz/svc/ads/ct/banner/gdsk.gif"></a></body></html>
        """.trimIndent()
        assertEquals(
            MessengerBannerAd(
                imageUrl = "https://static.ugnet.xyz/svc/ads/ct/banner/gdsk.gif",
                targetUrl = "https://gdsk.retrosite.org",
            ),
            MsnpClientConfigService.parseBannerAdHtml(html, "https://ctsvcs.advertising.ugnet.xyz/ads/banner"),
        )

        val unsafeHtml = """<a href="javascript:alert(1)"><img src="file:///etc/passwd"></a>"""
        assertNull(
            MsnpClientConfigService.parseBannerAdHtml(unsafeHtml, "https://ctsvcs.advertising.ugnet.xyz/ads/banner"),
        )

        val textXml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <TEXTAD>
                <adtext>Thank you for using CrossTalk!</adtext>
                <hiturl>https://crosstalk.im</hiturl>
            </TEXTAD>
        """.trimIndent()
        assertEquals(
            MessengerTextAd(
                text = "Thank you for using CrossTalk!",
                hitUrl = "https://crosstalk.im",
            ),
            MsnpClientConfigService.parseTextAdXml(textXml),
        )
    }

    @Test
    fun parsesDynamicServiceDiscoveryTagsAndPreservesCustomSchemes() {
        val communityXml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <MsgrConfig>
                <AbchCfg>
                    <abchconfig>
                        <url>http://contacts.ctsrv.xyz/abservice/abservice.asmx</url>
                    </abchconfig>
                </AbchCfg>
                <AppDirConfig>
                    <AppDirPageURL>http://apps.ctsrv.xyz/AppDirectory/Directory.aspx</AppDirPageURL>
                    <AppDirSeviceURL>http://apps.ctsrv.xyz/AppDirectory/AppDirectory.asmx</AppDirSeviceURL>
                </AppDirConfig>
                <ContactCard>
                    <GetItemVerUrl>http://storage.ctsrv.xyz/storageservice/SchematizedStore.asmx</GetItemVerUrl>
                </ContactCard>
            </MsgrConfig>
        """.trimIndent()
        val parsedCommunity = MsnpClientConfigService.parseMsgrConfig(communityXml)
        assertEquals(
            "https://contacts.ctsrv.xyz/abservice/abservice.asmx",
            parsedCommunity.addressBookUrl,
        )
        assertEquals(
            "https://apps.ctsrv.xyz/AppDirectory/AppDirectory.asmx",
            parsedCommunity.appDirServiceUrl,
        )
        assertEquals(
            "https://storage.ctsrv.xyz/storageservice/SchematizedStore.asmx",
            parsedCommunity.storageServiceUrl,
        )

        val customXml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <MsgrConfig>
                <AbchCfg>
                    <abchconfig>
                        <url>http://127.0.0.1:18080/abservice/abservice.asmx</url>
                    </abchconfig>
                </AbchCfg>
                <AppDirConfig>
                    <AppDirPageURL>http://127.0.0.1:18080/AppDirectory/Directory.aspx</AppDirPageURL>
                </AppDirConfig>
                <ContactCard>
                    <GetItemVerUrl>http://127.0.0.1:18080/storageservice/SchematizedStore.asmx</GetItemVerUrl>
                </ContactCard>
            </MsgrConfig>
        """.trimIndent()
        val parsedCustom = MsnpClientConfigService.parseMsgrConfig(customXml)
        assertEquals(
            "http://127.0.0.1:18080/abservice/abservice.asmx",
            parsedCustom.addressBookUrl,
        )
        assertEquals(
            "http://127.0.0.1:18080/AppDirectory/AppDirectory.asmx",
            parsedCustom.appDirServiceUrl,
        )
        assertEquals(
            "http://127.0.0.1:18080/storageservice/SchematizedStore.asmx",
            parsedCustom.storageServiceUrl,
        )
    }

    @Test
    fun candidateServiceEndpointsAndFallbackHandleHttpsToHttpOnlyOnIoErrors() {
        assertEquals(
            listOf(
                "https://custom.example.test:8080/RST.srf",
                "http://custom.example.test:8080/RST.srf",
            ),
            MsnpClientConfigService.candidateServiceEndpoints("custom.example.test:8080", "/RST.srf"),
        )
        assertEquals(
            listOf("http://custom.example.test:8080/RST.srf"),
            MsnpClientConfigService.candidateServiceEndpoints("http://custom.example.test:8080", "/RST.srf"),
        )

        val attemptedIo = mutableListOf<String>()
        val result = runBlocking {
            MsnpClientConfigService.withServiceEndpointFallback(
                listOf("https://custom.example.test/RST.srf", "http://custom.example.test/RST.srf"),
            ) { endpoint ->
                attemptedIo += endpoint
                if (endpoint.startsWith("https://")) throw IOException("TLS unavailable")
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(
            listOf("https://custom.example.test/RST.srf", "http://custom.example.test/RST.srf"),
            attemptedIo,
        )

        val attemptedAuthError = mutableListOf<String>()
        var nonIoThrown = false
        try {
            runBlocking {
                MsnpClientConfigService.withServiceEndpointFallback(
                    listOf("https://custom.example.test/RST.srf", "http://custom.example.test/RST.srf"),
                ) { endpoint ->
                    attemptedAuthError += endpoint
                    throw IllegalStateException("HTTP 401")
                }
            }
        } catch (_: IllegalStateException) {
            nonIoThrown = true
        }
        assertTrue(nonIoThrown)
        assertEquals(listOf("https://custom.example.test/RST.srf"), attemptedAuthError)
    }
}
