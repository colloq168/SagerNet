/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <sekai@neko.services>                    *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.hysteria

import cn.hutool.core.util.NumberUtil
import cn.hutool.json.JSONObject
import io.nekohasekai.sagernet.fmt.LOCALHOST

fun JSONObject.parseHysteria(): HysteriaBean {
    return HysteriaBean().apply {
        serverAddress = getStr("server").substringBefore(":")
        serverPort = getStr("server").substringAfter(":")
            .takeIf { NumberUtil.isInteger(it) }
            ?.toInt() ?: 443
        uploadMbps = getInt("up_mbps")
        downloadMbps = getInt("down_mbps")
        obfuscation = getStr("obfs")
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        getStr("auth_str")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        sni = getStr("server_name")
        allowInsecure = getBool("insecure")
    }
}

fun HysteriaBean.buildHysteriaConfig(port: Int): String {
    return JSONObject().also {
        it["server"] = "$serverAddress:$serverPort"
        it["up_mbps"] = uploadMbps
        it["down_mbps"] = downloadMbps
        it["socks5"] = JSONObject("listen" to "$LOCALHOST:$port")
        it["obfs"] = obfuscation
        when (authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> it["auth"] = authPayload
            HysteriaBean.TYPE_STRING -> it["auth_str"] = authPayload
        }
        if (sni.isNotBlank()) it["server_name"] = sni
        if (allowInsecure) it["insecure"] = true
    }.toStringPretty()
}
