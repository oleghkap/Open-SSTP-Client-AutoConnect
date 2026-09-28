package kittoku.osc.io

internal fun clampIpv4TcpMss(packet: ByteArray, offset: Int, length: Int, mss: Int): Boolean {
    if (mss <= 0 || length < 40) return false
    val ip = offset
    if (((packet[ip].toInt() ushr 4) and 0x0F) != 4) return false
    val ihl = (packet[ip].toInt() and 0x0F) * 4
    if (ihl < 20 || length < ihl + 20) return false
    if ((packet[ip + 9].toInt() and 0xFF) != 6) return false
    val totalLength = readU16(packet, ip + 2)
    if (totalLength < ihl + 20 || totalLength > length) return false
    val tcp = ip + ihl
    if ((packet[tcp + 13].toInt() and 0x02) == 0) return false
    val tcpHeaderLength = ((packet[tcp + 12].toInt() ushr 4) and 0x0F) * 4
    if (tcpHeaderLength < 20 || tcpHeaderLength > totalLength - ihl) return false
    var pos = tcp + 20
    val end = tcp + tcpHeaderLength
    while (pos < end) {
        when (packet[pos].toInt() and 0xFF) {
            0 -> break
            1 -> pos++
            2 -> {
                if (pos + 4 > end || (packet[pos + 1].toInt() and 0xFF) != 4) return false
                val old = readU16(packet, pos + 2)
                val newValue = minOf(old, mss).coerceIn(1, 65535)
                if (newValue == old) return false
                writeU16(packet, pos + 2, newValue)
                recalcIpv4HeaderChecksum(packet, ip, ihl)
                recalcTcpChecksumIpv4(packet, ip, tcp, totalLength - ihl)
                return true
            }
            else -> {
                val optionLength = if (pos + 1 < end) packet[pos + 1].toInt() and 0xFF else return false
                if (optionLength < 2 || pos + optionLength > end) return false
                pos += optionLength
            }
        }
    }
    return false
}

private fun readU16(a: ByteArray, p: Int): Int = ((a[p].toInt() and 0xFF) shl 8) or (a[p + 1].toInt() and 0xFF)
private fun writeU16(a: ByteArray, p: Int, v: Int) { a[p] = (v ushr 8).toByte(); a[p + 1] = v.toByte() }
private fun checksumSum(a: ByteArray, start: Int, length: Int, initial: Long = 0L): Long {
    var sum = initial; var i = start; val end = start + length
    while (i + 1 < end) { sum += readU16(a, i); i += 2 }
    if (i < end) sum += (a[i].toInt() and 0xFF) shl 8
    return sum
}
private fun foldChecksum(value: Long): Int {
    var sum = value
    while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
    return sum.inv().toInt() and 0xFFFF
}
private fun recalcIpv4HeaderChecksum(a: ByteArray, ip: Int, ihl: Int) {
    writeU16(a, ip + 10, 0); writeU16(a, ip + 10, foldChecksum(checksumSum(a, ip, ihl)))
}
private fun recalcTcpChecksumIpv4(a: ByteArray, ip: Int, tcp: Int, tcpLength: Int) {
    writeU16(a, tcp + 16, 0)
    var sum = checksumSum(a, ip + 12, 8) + 6L + tcpLength.toLong()
    sum = checksumSum(a, tcp, tcpLength, sum)
    writeU16(a, tcp + 16, foldChecksum(sum))
}
