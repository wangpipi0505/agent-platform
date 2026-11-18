package cn.agentplatform.engine.infra;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * R-5（返工卡）：SSE/文本响应 UTF-8 字节级保障——{@link StringHttpMessageConverter} 默认
 * 字符集显式注册为 UTF-8。台账发现：全仓此前无 UTF-8 StringHttpMessageConverter 注册，
 * MVC 默认 ISO-8859-1——SSE 帧内非 ASCII（中文任务标题/会话名，含四字节字符）在无显式
 * charset 的写出路径按默认字符集替换（MockMvc 下实测落 '?'），字节级不可与源一致。
 *
 * <p>接入方式：{@code extendMessageConverters} 对既有转换器列表<b>原位替换</b> String
 * 转换器（保持既有次序，Jackson 等 JSON 转换器行为不变——application/json 的字符串写出自
 * Spring 6 起本就按 UTF-8 兼容处理）；字符集已显式声明（带 charset 参数）的响应不受影响。
 * 真实端口字节级验证见 TaskEventsSseRealPortTest（Tomcat 真启动+原始字节比对，含四字节
 * emoji），standalone MockMvc 旧断言口径的更正说明见 TaskEventsSseTest。
 */
@Configuration(proxyBeanMethods = false)
public class Utf8StringMessageConvertersConfiguration implements WebMvcConfigurer {

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    var utf8 = new StringHttpMessageConverter(StandardCharsets.UTF_8);
    for (int i = 0; i < converters.size(); i++) {
      if (converters.get(i) instanceof StringHttpMessageConverter) {
        converters.set(i, utf8);
        return;
      }
    }
    // 无既有 String 转换器（非 Boot 自动配置上下文）：插到最前，保底承接字符串写出。
    converters.add(0, utf8);
  }
}
