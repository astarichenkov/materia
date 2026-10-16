package com.lectureprocessor.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.support.TestProperties;

/**
 * Access control and CSRF behaviour for the administration surface.
 */
class AdminAccessServiceTest {

    private static AdminAccessService service(String adminToken) {
        AppProperties base = TestProperties.defaults("./target/test-data");
        AppProperties props = new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), base.deepseek(), new AppProperties.Security(adminToken,
                        base.security().masterKeyPath(), base.security().masterKeyEnv()),
                base.gitea(), base.codeReview());
        return new AdminAccessService(props);
    }

    @Test
    void loopbackIsAllowedWhenNoTokenConfigured() {
        AdminAccessService access = service("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/settings");
        request.setRemoteAddr("127.0.0.1");
        assertThat(access.isAuthorized(request)).isTrue();
        assertThat(access.tokenConfigured()).isFalse();
    }

    @Test
    void remoteAddressIsRejectedWhenNoTokenConfigured() {
        AdminAccessService access = service("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/settings");
        request.setRemoteAddr("203.0.113.7");
        assertThat(access.isAuthorized(request)).isFalse();
    }

    @Test
    void configuredTokenRequiresHeaderOrLogin() {
        AdminAccessService access = service("s3cret-admin");
        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/settings");
        anonymous.setRemoteAddr("203.0.113.7");
        assertThat(access.isAuthorized(anonymous)).isFalse();

        MockHttpServletRequest withHeader = new MockHttpServletRequest("GET", "/settings");
        withHeader.setRemoteAddr("203.0.113.7");
        withHeader.addHeader(AdminAccessService.ADMIN_TOKEN_HEADER, "s3cret-admin");
        assertThat(access.isAuthorized(withHeader)).isTrue();

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/settings/login");
        login.setRemoteAddr("203.0.113.7");
        assertThat(access.login(login, "wrong")).isFalse();
        assertThat(access.login(login, "s3cret-admin")).isTrue();

        MockHttpServletRequest after = new MockHttpServletRequest("GET", "/settings");
        after.setRemoteAddr("203.0.113.7");
        after.setSession((MockHttpSession) login.getSession(false));
        assertThat(access.isAuthorized(after)).isTrue();
    }

    @Test
    void csrfTokenIsStablePerSessionAndValidated() {
        AdminAccessService access = service("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/settings/tasks");
        request.setRemoteAddr("127.0.0.1");
        String token = access.csrfToken(request.getSession(true));
        assertThat(token).isNotBlank();
        assertThat(access.csrfToken(request.getSession())).isEqualTo(token);

        assertThat(access.csrfValid(request)).isFalse();
        request.setParameter(AdminAccessService.CSRF_PARAMETER, "nope");
        assertThat(access.csrfValid(request)).isFalse();
        request.setParameter(AdminAccessService.CSRF_PARAMETER, token);
        assertThat(access.csrfValid(request)).isTrue();
    }

    @Test
    void headerTokenRequestsAreExemptFromCsrf() {
        AdminAccessService access = service("s3cret-admin");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/providers/deepseek/credential");
        request.addHeader(AdminAccessService.ADMIN_TOKEN_HEADER, "s3cret-admin");
        assertThat(access.csrfRequired(request)).isFalse();
    }

    @Test
    void browserSessionRequestsRequireCsrf() {
        AdminAccessService access = service("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/settings/tasks");
        assertThat(access.csrfRequired(request)).isTrue();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/settings");
        assertThat(access.csrfRequired(get)).isFalse();
    }
}
