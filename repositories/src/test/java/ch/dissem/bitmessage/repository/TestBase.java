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

import ch.dissem.bitmessage.InternalContext;
import ch.dissem.bitmessage.ports.MultiThreadedPOWEngine;
import ch.dissem.bitmessage.cryptography.bc.BouncyCryptography;
import ch.dissem.bitmessage.utils.Singleton;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by chris on 20.07.15.
 */
public class TestBase {
    static {
        BouncyCryptography security = new BouncyCryptography();
        Singleton.initialize(security);
        InternalContext ctx = mock(InternalContext.class);
        when(ctx.getProofOfWorkEngine()).thenReturn(new MultiThreadedPOWEngine());
        security.setContext(ctx);
    }
}
