package cn.smsforwarder

import javax.mail.internet.InternetAddress

object EmailAddressParser {
    fun parseSender(raw: String): Result<InternetAddress> {
        return runCatching {
            val addresses = parseAddresses(raw).getOrThrow()
            require(addresses.size == 1) { "发件邮箱只能填写一个地址" }
            addresses.first()
        }
    }

    fun parseRecipients(raw: String): Result<Array<InternetAddress>> {
        return parseAddresses(raw)
    }

    private fun parseAddresses(raw: String): Result<Array<InternetAddress>> {
        return runCatching {
            val normalized = raw
                .replace('；', ';')
                .replace('，', ',')
                .replace('\n', ',')
                .split(';')
                .joinToString(",")
                .trim()

            val addresses = InternetAddress.parse(normalized, false)
                .onEach { address ->
                    address.address = address.address?.trim()
                    address.personal = address.personal?.trim()
                    address.validate()
                }
                .filter { !it.address.isNullOrBlank() }
                .toTypedArray()

            require(addresses.isNotEmpty()) { "请填写收件邮箱" }
            addresses
        }
    }
}
