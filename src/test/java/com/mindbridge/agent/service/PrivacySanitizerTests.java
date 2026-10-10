package com.mindbridge.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrivacySanitizerTests {

    private final PrivacySanitizer sanitizer = new PrivacySanitizer();

    @Test
    void masksSyntheticContactDetails() {
        assertThat(sanitizer.sanitize("邮箱 student@example.test，电话13800000000，备用138-0000-0000，数字邮箱13800000000@example.test"))
                .doesNotContain("@example.test", "13800000000", "138-0000-0000")
                .contains("[邮箱]", "[手机号]");
    }

    @Test
    void masksLabeledStudentIdsAndIdentityNumbersBeforePhoneMatching() {
        assertThat(sanitizer.sanitize("学号：13800000000，身份证：110000200001010000，备用110000200001010000"))
                .doesNotContain("13800000000", "110000200001010000")
                .contains("[学号]", "[证件号]");
    }

    @Test
    void masksExplicitNamesAndAddressesIdempotently() {
        String sanitized = sanitizer.sanitize("姓名：示例甲，地址：示例路0号；偏好简短回复");
        assertThat(sanitized).doesNotContain("示例甲", "示例路0号")
                .contains("[姓名]", "[地址]", "偏好简短回复");
        assertThat(sanitizer.sanitize(sanitized)).isEqualTo(sanitized);
    }

    @Test
    void preservesOrdinaryPreferenceText() {
        assertThat(sanitizer.sanitize("请记住，我更喜欢简短直接的回复。"))
                .isEqualTo("请记住，我更喜欢简短直接的回复。");
        assertThat(sanitizer.sanitize(null)).isEmpty();
    }
}
