package com.titotfp.wuwaid

interface PrivilegedFiles {
    fun copyFile(source: String, destination: String): Boolean
    fun replaceFile(source: String, destination: String): Boolean
    fun deleteFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun mkdirs(path: String): Boolean
    fun listFiles(path: String): Array<String>
    fun readText(path: String): String
    fun writeTextAtomic(path: String, content: String): Boolean
    fun sha1(path: String): String
    fun sha256(path: String): String
    fun lastError(): String
}
