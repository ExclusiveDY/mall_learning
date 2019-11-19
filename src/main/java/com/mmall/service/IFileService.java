package com.mmall.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * created by dy
 */
public interface IFileService {
    String upload(MultipartFile file, String path);
}
