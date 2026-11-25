package com.example.mvp.app.auth;

import com.example.mvp.auth.TokenService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 站 1-A：演示用的发号端点。
 *
 * <p><b>生产环境不存在这个接口</b>：用户 id 来自真实登录态（手机号/账号），
 * 由服务端根据会话查出并签发令牌，绝不能让客户端自己报 userId。
 * 这里保留它只是为了让 demo 能一键拿到一个可用令牌。
 *
 * <p><b>对照 customer_work</b>：那边对应的是 {@code UserAuthController} 的登录端点，
 * 令牌由服务端根据账号密码校验后签发（JWT），前端不接触密钥材料。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * 签发令牌。
     *
     * @param request 可选，指定 userId；不传则随机生成一个
     */
    @PostMapping("/token")
    public Mono<TokenResponse> issue(@RequestBody(required = false) TokenRequest request) {
        String requested = request == null ? null : request.userId();
        String userId = (requested == null || requested.isBlank())
                ? tokenService.newDemoUserId()
                : requested.trim();
        String token = tokenService.issue(userId);
        return Mono.just(new TokenResponse(token, userId, tokenService.remainingTtlMillis(token)));
    }

    /** 请求体。 */
    public record TokenRequest(String userId) {
    }

    /** 响应体。 */
    public record TokenResponse(String token, String userId, long expiresInMillis) {
    }
}
