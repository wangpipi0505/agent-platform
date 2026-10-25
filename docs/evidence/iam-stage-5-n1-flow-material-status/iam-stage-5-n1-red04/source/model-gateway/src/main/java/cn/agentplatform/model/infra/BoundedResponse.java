package cn.agentplatform.model.infra;

import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;

import java.io.*;

/** 在JSON转换器之前限制实际网络字节，避免先把大对象读入堆再检查大小。 */
public final class BoundedResponse implements ClientHttpResponse {
    private final ClientHttpResponse response;
    private final InputStream body;

    public BoundedResponse(ClientHttpResponse response, int maximum) throws IOException {
        this.response = response;
        body =
                new FilterInputStream(response.getBody()) {
                    private int received;

                    private void count(int n) throws IOException {
                        if (n > 0 && (received += n) > maximum) throw new IOException("模型响应超过安全上限");
                    }

                    @Override
                    public int read() throws IOException {
                        int value = in.read();
                        count(value < 0 ? 0 : 1);
                        return value;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int n = in.read(bytes, offset, Math.min(length, maximum - received + 1));
                        count(n);
                        return n;
                    }

                    @Override
                    public long skip(long n) throws IOException {
                        long skipped = super.skip(Math.min(n, maximum - received + 1));
                        count((int) skipped);
                        return skipped;
                    }
                };
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return response.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
        return response.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
        return response.getHeaders();
    }

    @Override
    public InputStream getBody() {
        return body;
    }

    @Override
    public void close() {
        response.close();
    }
}
