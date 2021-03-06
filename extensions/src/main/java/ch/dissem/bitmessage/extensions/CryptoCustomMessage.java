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

package ch.dissem.bitmessage.extensions;

import ch.dissem.bitmessage.entity.BitmessageAddress;
import ch.dissem.bitmessage.entity.CustomMessage;
import ch.dissem.bitmessage.entity.Streamable;
import ch.dissem.bitmessage.entity.payload.CryptoBox;
import ch.dissem.bitmessage.entity.payload.Pubkey;
import ch.dissem.bitmessage.exception.DecryptionFailedException;
import ch.dissem.bitmessage.factory.Factory;
import ch.dissem.bitmessage.utils.Encode;

import java.io.*;

import static ch.dissem.bitmessage.utils.Decode.*;
import static ch.dissem.bitmessage.utils.Singleton.cryptography;

/**
 * A {@link CustomMessage} implementation that contains signed and encrypted data.
 *
 * @author Christian Basler
 */
public class CryptoCustomMessage<T extends Streamable> extends CustomMessage {
    private static final long serialVersionUID = 7395193565986284426L;

    public static final String COMMAND = "ENCRYPTED";

    private final Reader<T> dataReader;
    private CryptoBox container;
    private BitmessageAddress sender;
    private T data;

    public CryptoCustomMessage(T data) throws IOException {
        super(COMMAND);
        this.data = data;
        this.dataReader = null;
    }

    private CryptoCustomMessage(CryptoBox container, Reader<T> dataReader) {
        super(COMMAND);
        this.container = container;
        this.dataReader = dataReader;
    }

    public static <T extends Streamable> CryptoCustomMessage<T> read(CustomMessage data, Reader<T> dataReader) throws IOException {
        CryptoBox cryptoBox = CryptoBox.read(new ByteArrayInputStream(data.getData()), data.getData().length);
        return new CryptoCustomMessage<>(cryptoBox, dataReader);
    }

    public BitmessageAddress getSender() {
        return sender;
    }

    public void signAndEncrypt(BitmessageAddress identity, byte[] publicKey) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Encode.varInt(identity.getVersion(), out);
        Encode.varInt(identity.getStream(), out);
        Encode.int32(identity.getPubkey().getBehaviorBitfield(), out);
        out.write(identity.getPubkey().getSigningKey(), 1, 64);
        out.write(identity.getPubkey().getEncryptionKey(), 1, 64);
        if (identity.getVersion() >= 3) {
            Encode.varInt(identity.getPubkey().getNonceTrialsPerByte(), out);
            Encode.varInt(identity.getPubkey().getExtraBytes(), out);
        }

        data.write(out);
        Encode.varBytes(cryptography().getSignature(out.toByteArray(), identity.getPrivateKey()), out);
        container = new CryptoBox(out.toByteArray(), publicKey);
    }

    public T decrypt(byte[] privateKey) throws IOException, DecryptionFailedException {
        SignatureCheckingInputStream in = new SignatureCheckingInputStream(container.decrypt(privateKey));

        long addressVersion = varInt(in);
        long stream = varInt(in);
        int behaviorBitfield = int32(in);
        byte[] publicSigningKey = bytes(in, 64);
        byte[] publicEncryptionKey = bytes(in, 64);
        long nonceTrialsPerByte = addressVersion >= 3 ? varInt(in) : 0;
        long extraBytes = addressVersion >= 3 ? varInt(in) : 0;

        sender = new BitmessageAddress(Factory.createPubkey(
                addressVersion,
                stream,
                publicSigningKey,
                publicEncryptionKey,
                nonceTrialsPerByte,
                extraBytes,
                behaviorBitfield
        ));

        data = dataReader.read(sender, in);

        in.checkSignature(sender.getPubkey());

        return data;
    }

    @Override
    public void write(OutputStream out) throws IOException {
        Encode.varString(COMMAND, out);
        container.write(out);
    }

    public interface Reader<T> {
        T read(BitmessageAddress sender, InputStream in) throws IOException;
    }

    private class SignatureCheckingInputStream extends InputStream {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final InputStream wrapped;

        private SignatureCheckingInputStream(InputStream wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public int read() throws IOException {
            int read = wrapped.read();
            if (read >= 0) out.write(read);
            return read;
        }

        public void checkSignature(Pubkey pubkey) throws IOException, IllegalStateException {
            if (!cryptography().isSignatureValid(out.toByteArray(), varBytes(wrapped), pubkey)) {
                throw new IllegalStateException("Signature check failed");
            }
        }
    }
}
