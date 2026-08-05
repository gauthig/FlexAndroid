/*
 * FlexAndroid - remote control of a FlexRadio SDR over SmartLink.
 * Copyright (C) 2026 Garrett Gauthier
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.gauthig.flexandroid.net

/**
 * One line of the SmartSDR TCP/IP control protocol, decoded.
 *
 * The radio speaks five line types, each identified by its first character:
 *
 * ```
 * V1.4.0.0                                   version
 * H6F4EC23D                                  client handle
 * R21|0|2|OK Slice receiver 2 created...      response to command 21
 * SA4E3D1C9|slice 2 RF_frequency=14.2 ...     asynchronous status
 * M10000002|unknown client program            message
 * ```
 *
 * See docs/PROTOCOL.md for the full grammar.
 */
sealed interface FlexMessage {

    /** `V<d.e.a.b>` — protocol version announced on connect. */
    data class Version(val version: String) : FlexMessage

    /** `H<handle>` — the 32-bit hex handle identifying this client connection. */
    data class Handle(val handle: Long) : FlexMessage

    /**
     * `R<seq>|<hex>|<message>[|<debug>]` — the single reply to command [seq].
     *
     * [code] is 0 on success; any other value is a failure whose meaning is
     * documented per-command in the FlexRadio API wiki.
     */
    data class Response(
        val seq: Long,
        val code: Long,
        val message: String,
        val debug: String?,
    ) : FlexMessage {
        val isSuccess: Boolean get() = code == 0L
    }

    /**
     * `S<handle>|<object> <key>=<value> ...` — asynchronous object status.
     *
     * [obj] is the whitespace-delimited object path with its identifier still
     * attached, e.g. `slice 0`, `display pan 0x40000000`, `interlock`, `radio`.
     * [fields] holds the parsed key/value pairs.
     */
    data class Status(
        val handle: Long,
        val obj: String,
        val fields: Map<String, String>,
        val raw: String,
    ) : FlexMessage

    /** `M<num>|<text>` — free-form notice. Bits 24-25 of [number] carry severity. */
    data class Notice(val number: Long, val text: String) : FlexMessage {

        val severity: Severity
            get() = when ((number shr 24) and 0x3L) {
                0L -> Severity.INFO
                1L -> Severity.WARNING
                2L -> Severity.ERROR
                else -> Severity.FATAL
            }

        enum class Severity { INFO, WARNING, ERROR, FATAL }
    }

    /** A line we could not classify. Kept so the log can show it verbatim. */
    data class Unknown(val raw: String) : FlexMessage
}

/** Parses one line of the control protocol. Never throws on malformed input. */
object FlexMessageParser {

    fun parse(line: String): FlexMessage {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return FlexMessage.Unknown(trimmed)

        return when (trimmed[0]) {
            'V' -> FlexMessage.Version(trimmed.substring(1))
            'H' -> FlexMessage.Handle(parseHex(trimmed.substring(1)) ?: 0L)
            'R' -> parseResponse(trimmed)
            'S' -> parseStatus(trimmed)
            'M' -> parseNotice(trimmed)
            else -> FlexMessage.Unknown(trimmed)
        }
    }

    private fun parseResponse(line: String): FlexMessage {
        // R<seq>|<hex_code>|<message>[|<debug>]
        val parts = line.substring(1).split('|')
        if (parts.size < 2) return FlexMessage.Unknown(line)
        val seq = parts[0].trim().toLongOrNull() ?: return FlexMessage.Unknown(line)
        val code = parseHex(parts[1]) ?: return FlexMessage.Unknown(line)
        return FlexMessage.Response(
            seq = seq,
            code = code,
            message = parts.getOrNull(2).orEmpty(),
            // Debug output may itself contain '|', so rejoin everything past it.
            debug = if (parts.size > 3) parts.drop(3).joinToString("|") else null,
        )
    }

    private fun parseStatus(line: String): FlexMessage {
        // S<handle>|<body>
        val bar = line.indexOf('|')
        if (bar < 0) return FlexMessage.Unknown(line)
        val handle = parseHex(line.substring(1, bar)) ?: 0L
        val body = line.substring(bar + 1)

        // The object path is the leading run of tokens that carry no '='. That
        // covers both `slice 0 mode=USB` and `display pan 0x40000000 center=14.2`.
        val tokens = body.split(' ').filter { it.isNotEmpty() }
        val objTokens = tokens.takeWhile { !it.contains('=') }
        val fields = parseFields(tokens.drop(objTokens.size))

        return FlexMessage.Status(
            handle = handle,
            obj = objTokens.joinToString(" "),
            fields = fields,
            raw = line,
        )
    }

    private fun parseNotice(line: String): FlexMessage {
        val bar = line.indexOf('|')
        if (bar < 0) return FlexMessage.Unknown(line)
        val num = parseHex(line.substring(1, bar)) ?: 0L
        return FlexMessage.Notice(num, line.substring(bar + 1))
    }

    /** Splits `a=1 b=2` tokens into a map, keeping the last value on collision. */
    fun parseFields(tokens: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(tokens.size)
        for (token in tokens) {
            val eq = token.indexOf('=')
            if (eq > 0) out[token.substring(0, eq)] = token.substring(eq + 1)
        }
        return out
    }

    /**
     * Splits a space-separated `key=value` string into a map.
     *
     * Used for SmartLink server messages and for `meter list` style payloads
     * after they have been split on their own separator.
     */
    fun parseFields(text: String): Map<String, String> =
        parseFields(text.split(' ').filter { it.isNotEmpty() })

    /**
     * Parses a 32-bit hex quantity, tolerating a `0x` prefix.
     *
     * Values are returned as [Long] because the protocol's handles and stream
     * IDs routinely exceed [Int.MAX_VALUE] (`0x84000000`, for example) and
     * sign-extending them would corrupt comparisons.
     */
    fun parseHex(text: String): Long? {
        val s = text.trim().removePrefix("0x").removePrefix("0X")
        if (s.isEmpty()) return null
        return s.toLongOrNull(16)
    }
}
