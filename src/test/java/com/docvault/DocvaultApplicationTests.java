package com.docvault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DocvaultApplicationTests {

    @Test
    @DisplayName("Given test profile with H2, when application starts, then context loads successfully")
    void givenTestProfile_whenApplicationStarts_thenContextLoadsSuccessfully() {
        // If this test passes, it means:
        // - All beans wire up correctly
        // - JPA entities are valid and Hibernate can create the schema
        // - No circular dependencies exist
        // - DataSeeder runs and populates roles
    }
}
