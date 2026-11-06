package com.example.aifinanceworld;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(MySqlContainerConfig.class)
@ActiveProfiles("mysqltest")
class MySqlAmountPrecisionTest extends AmountPrecisionTest {
}
