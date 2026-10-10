package com.mindbridge.agent.service;

import org.springframework.stereotype.Service;

@Service
/**
 * 输入隐私脱敏服务。
 *
 * <p>对发送给模型和评估链路的文本做轻量脱敏，降低敏感标识进入上下文的概率。</p>
 */
public class PrivacySanitizer {

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // 模型侧只需要语义，不需要手机号、学号、证件号等敏感标识。
        String sanitized = text;
        sanitized = sanitized.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[邮箱]");
        sanitized = sanitized.replaceAll("(?i)(学号|student\\s*id)[:：\\s]*[A-Za-z0-9_-]{6,20}", "$1:[学号]");
        sanitized = sanitized.replaceAll("(?i)(身份证|id\\s*card)[:：\\s]*[0-9xX]{15,18}", "$1:[证件号]");
        sanitized = sanitized.replaceAll("(?<!\\d)[1-9]\\d{16}[0-9xX](?!\\d)", "[证件号]");
        sanitized = sanitized.replaceAll("(?<!\\d)(?:\\+?86[-\\s]?)?1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}(?!\\d)", "[手机号]");
        // shortcut: 姓名和地址仅识别明确表达，需覆盖自由文本身份信息时再接入专用识别器。
        sanitized = sanitized.replaceAll("((?:真实)?姓名)[:：\\s]*[\\u4e00-\\u9fa5]{2,4}", "$1：[姓名]");
        sanitized = sanitized.replaceAll("(?i)((?:家庭|详细|联系|居住)?地址|address)[:：\\s]+[^，。；;\\r\\n]{2,100}", "$1：[地址]");
        sanitized = sanitized.replaceAll("我叫[\\u4e00-\\u9fa5]{2,4}", "我叫[姓名]");
        sanitized = sanitized.replaceAll("我是[\\u4e00-\\u9fa5]{2,4}", "我是[姓名]");
        return sanitized;
    }
}
