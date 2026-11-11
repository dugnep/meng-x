package com.mengx.server.auth;

import com.mengx.server.user.UserEntity;
import com.mengx.server.user.UserRepository;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** 登录即注册的结果：user + 是否新建账号 */
    public record LoginResult(UserEntity user, boolean created) {}

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * 登录或注册：账号存在则验密码（失败返回空），不存在则自动建号。
     * 昵称自动生成（点友+手机尾号），暂不支持修改。
     */
    public Optional<LoginResult> loginOrRegister(String phone, String rawPassword) {
        Optional<UserEntity> existing = userRepository.findByPhone(phone);
        if (existing.isPresent()) {
            return passwordEncoder.matches(rawPassword, existing.get().getPasswordHash())
                    ? Optional.of(new LoginResult(existing.get(), false))
                    : Optional.empty();
        }
        String nickname = "石友" + phone.substring(Math.max(0, phone.length() - 4));
        UserEntity created = userRepository.save(
                new UserEntity(phone, passwordEncoder.encode(rawPassword), nickname));
        return Optional.of(new LoginResult(created, true));
    }

    public String issueToken(Long userId) {
        return jwtService.issueToken(userId);
    }

    public Optional<UserEntity> findById(Long userId) {
        return userRepository.findById(userId);
    }
}
