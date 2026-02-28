package com.docvault.config;

import com.docvault.entity.Role;
import com.docvault.enums.ERole;
import com.docvault.repository.RoleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner initRoles(RoleRepository roleRepository) {
        return args -> {
            for (ERole eRole : ERole.values()) {
                if (!roleRepository.existsByName(eRole)) {
                    roleRepository.save(Role.builder().name(eRole).build());
                }
            }
        };
    }
}
