package dev.dawngasm.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
actual object PlatformSecureStorage : SecureStorage {
    private const val SERVICE = "dev.dawngasm.app.vault"

    actual override fun getString(key: String): String? = memScoped {
        val serviceCf = CFStringCreateWithCString(kCFAllocatorDefault, SERVICE, kCFStringEncodingUTF8) ?: return null
        val accountCf = CFStringCreateWithCString(kCFAllocatorDefault, key, kCFStringEncodingUTF8)
        if (accountCf == null) {
            CFRelease(serviceCf)
            return null
        }
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        if (query == null) {
            CFRelease(serviceCf)
            CFRelease(accountCf)
            return null
        }
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceCf)
            CFDictionarySetValue(query, kSecAttrAccount, accountCf)
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess || result.value == null) return null

            val nsData = CFBridgingRelease(result.value) as? NSData ?: return null
            val len = nsData.length.toInt()
            if (len == 0) return ""
            val bytes = ByteArray(len)
            bytes.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), nsData.bytes, nsData.length.convert())
            }
            bytes.decodeToString()
        } finally {
            CFRelease(query)
            CFRelease(accountCf)
            CFRelease(serviceCf)
        }
    }

    actual override fun putString(key: String, value: String) {
        remove(key)
        val serviceCf = CFStringCreateWithCString(kCFAllocatorDefault, SERVICE, kCFStringEncodingUTF8) ?: return
        val accountCf = CFStringCreateWithCString(kCFAllocatorDefault, key, kCFStringEncodingUTF8)
        if (accountCf == null) {
            CFRelease(serviceCf)
            return
        }
        val bytes = value.encodeToByteArray()
        val dataCf = bytes.usePinned { pinned ->
            val ptr = if (bytes.isNotEmpty()) pinned.addressOf(0).reinterpret<UByteVar>() else null
            CFDataCreate(kCFAllocatorDefault, ptr, bytes.size.convert())
        }
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        try {
            if (query != null && dataCf != null) {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, serviceCf)
                CFDictionarySetValue(query, kSecAttrAccount, accountCf)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                CFDictionarySetValue(query, kSecValueData, dataCf)
                SecItemAdd(query, null)
            }
        } finally {
            if (query != null) CFRelease(query)
            if (dataCf != null) CFRelease(dataCf)
            CFRelease(accountCf)
            CFRelease(serviceCf)
        }
    }

    actual override fun remove(key: String) {
        val serviceCf = CFStringCreateWithCString(kCFAllocatorDefault, SERVICE, kCFStringEncodingUTF8) ?: return
        val accountCf = CFStringCreateWithCString(kCFAllocatorDefault, key, kCFStringEncodingUTF8)
        if (accountCf == null) {
            CFRelease(serviceCf)
            return
        }
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        try {
            if (query != null) {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, serviceCf)
                CFDictionarySetValue(query, kSecAttrAccount, accountCf)
                SecItemDelete(query)
            }
        } finally {
            if (query != null) CFRelease(query)
            CFRelease(accountCf)
            CFRelease(serviceCf)
        }
    }
}
