package com.cuber.bookvoice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Есть ли сейчас Wi-Fi (24.10.2026).
 *
 *  Зачем. Обычное чтение теперь — голосом движка, и сетевой голос тянет звук из
 *  сети. У @Voice для этого есть галочка «Use cloud voices only on WiFi
 *  (recommended)»: по мобильной сети облачный голос не используется, чтобы не
 *  тратить трафик. У нас та же строка за дверью опытных настроек
 *  ([MainActivity.KEY_NET_WIFI_ONLY]).
 *
 *  Ошибку чтения состояния считаем «Wi-Fi есть» (как [SyncStore.online] считает
 *  «сеть есть»): пусть чтение попробует, вместо того чтобы молча отказать по
 *  непонятной причине. */
object NetNow {

    /** Текущая сеть — Wi-Fi или Ethernet (провод в адаптере тоже не мобильный трафик). */
    fun onWifi(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val net = cm.activeNetwork ?: return@runCatching true
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching true
        Caps.isWifi(caps)
    }.getOrDefault(true)

    /** Понимает ли система, что сейчас мобильная сеть (тогда Wi-Fi нет). */
    fun onMobile(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val net = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)

    private object Caps {
        fun isWifi(caps: NetworkCapabilities): Boolean =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
