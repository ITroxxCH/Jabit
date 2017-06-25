/*
 * Copyright 2015 Christian Basler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.dissem.bitmessage.repository;

import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * JdbcConfig to be used for tests. Uses an in-memory database and adds a useful {@link #reset()} method resetting
 * the database.
 */
public class TestJdbcConfig extends JdbcConfig {
    private static final Logger LOG = LoggerFactory.getLogger(TestJdbcConfig.class);

    static {
        try {
            Server.createTcpServer().start();
        } catch (SQLException e) {
            LOG.error(e.getMessage(), e);
        }
    }

    public TestJdbcConfig() {
        super("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", null);
    }

    public void reset() {
        getFlyway().clean();
        getFlyway().migrate();
    }
}
