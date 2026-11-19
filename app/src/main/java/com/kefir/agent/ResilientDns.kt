package com.kefir.agent

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS с резервированием: сначала системный DNS, а если он не смог
 * (нет Wi-Fi DNS, Private DNS, блокировщик рекламы, кривой роутер) —
 * запрос через DNS-over-HTTPS (Google 8.8.8.8 / Cloudflare 1.1.1.1).
 * Это чинит ошибки вида "Unable to resolve host ... No address
 * associated with hostname".
 */
object ResilientDns : Dns {

    private val doh: DnsOverHttps = DnsOverHttps.Builder()
        .client(
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("8.8.4.4")
        )
        .includeIPv6(false)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            try {
                doh.lookup(hostname)
            } catch (e2: UnknownHostException) {
                val uh = UnknownHostException(
                    "Не смог найти $hostname: ни системный DNS, ни DNS-over-HTTPS. " +
                        "Скорее всего нет интернета или соединение блокируется."
                )
                uh.initCause(e2)
                throw uh
            }
        }
    }
}
