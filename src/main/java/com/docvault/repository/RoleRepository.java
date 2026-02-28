package com.docvault.repository;

import com.docvault.entity.Role;
import com.docvault.enums.ERole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(ERole name);

    Boolean existsByName(ERole name);
}
