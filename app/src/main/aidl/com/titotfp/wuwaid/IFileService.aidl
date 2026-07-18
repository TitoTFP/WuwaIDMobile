package com.titotfp.wuwaid;

interface IFileService {
    boolean copyFile(String srcPath, String destPath);
    boolean replaceFile(String srcPath, String destPath);
    boolean deleteFile(String path);
    boolean exists(String path);
    boolean mkdirs(String path);
    String[] listFiles(String path);
    String readText(String path);
    boolean writeTextAtomic(String path, String content);
    String sha1(String path);
    String sha256(String path);
    String lastError();
    void destroy();
}
