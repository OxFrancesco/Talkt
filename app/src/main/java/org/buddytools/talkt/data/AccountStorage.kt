package org.buddytools.talkt.data

import java.security.MessageDigest

fun accountStorageSuffix(userId: String?): String = userId?.let {
    MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}.orEmpty()
