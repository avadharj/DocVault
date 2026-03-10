package com.docvault.service;

import com.docvault.dto.UserProfileResponse;
import com.docvault.exception.ResourceNotFoundException;
import com.docvault.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<UserProfileResponse> getAllUsers() {
        List<UserProfileResponse> users = userRepository.findAll().stream()
                .map(UserProfileResponse::fromEntity)
                .collect(Collectors.toList());

        logger.info("Retrieved {} users", users.size());
        return users;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserById(Long id) {
        var user = userRepository.findById(id)
                .orElseThrow(() -> {
                    logger.warn("User not found with id: {}", id);
                    return new ResourceNotFoundException("User not found with id: " + id);
                });

        return UserProfileResponse.fromEntity(user);
    }
}
