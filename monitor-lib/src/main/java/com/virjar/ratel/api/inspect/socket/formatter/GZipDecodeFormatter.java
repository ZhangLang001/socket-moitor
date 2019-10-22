package com.virjar.ratel.api.inspect.socket.formatter;

import android.util.Log;

import com.virjar.ratel.api.inspect.socket.SocketPackEvent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import external.org.apache.commons.io.IOUtils;
import external.org.apache.commons.io.output.ByteArrayOutputStream;

public class GZipDecodeFormatter implements EventFormatter {
    @Override
    public void formatEvent(SocketPackEvent socketPackEvent) {
        if (!socketPackEvent.needDecodeHttpBody()) {
            return;
        }

        boolean isGzip = false;
        if ("gzip".equalsIgnoreCase(socketPackEvent.httpHeaders.get("Content-Encoding".toLowerCase(Locale.US)))) {
            isGzip = true;
        } else {
            String contentType = socketPackEvent.httpHeaders.get("Content-Type".toLowerCase(Locale.US));
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("application/zip")) {
                isGzip = true;
            }
        }

        if (!isGzip) {
            return;
        }

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(socketPackEvent.httpBodyContent);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream);
            IOUtils.copy(gzipInputStream, byteArrayOutputStream);
            gzipInputStream.close();
            byteArrayInputStream.close();
            byteArrayOutputStream.close();

            socketPackEvent.httpBodyContent = byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            Log.w("RATEL", "http unzip gzip failed", e);
        }

    }
}
