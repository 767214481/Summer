package com.swingfrog.summer.web;

import java.io.IOException;
import java.io.RandomAccessFile;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.multipart.FileUpload;

public class WebFileUpload {

	private final String fileName;
	private final ByteBuf byteBuf;
	
	WebFileUpload(FileUpload fileUpload) {
		fileName = fileUpload.getFilename();
		byteBuf = fileUpload.content();
	}
	
	public String getFileName() {
		return fileName;
	}
	
	public ByteBuf getByteBuf() {
		return byteBuf;
	}

	public boolean isEmpty() {
		return byteBuf.readableBytes() == 0;
	}
	
	public void saveToFile(String path) throws IOException {
		if (isEmpty())
			return;
		RandomAccessFile file = new RandomAccessFile(path, "rw");
		file.getChannel().write(byteBuf.nioBuffer());
		file.close();
	}
	
}
