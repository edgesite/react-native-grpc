package me.rikua.rngrpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Created by riku on 08/12/2016.
 */
final class ByteArrayMarshaller<M extends ByteArrayMessage> implements MethodDescriptor.Marshaller<M> {
  private static class Singleton {
    private static ByteArrayMarshaller instance = new ByteArrayMarshaller();
  }

  static ByteArrayMarshaller getInstance() {
    return Singleton.instance;
  }

  @Override
  public InputStream stream(M value) {
    return new ByteArrayInputStream(value.bytes);
  }

  @Override
  public M parse(InputStream stream) {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int length;
    try {
      while ((length = stream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, length);
      }
    } catch (IOException e) {
      throw Status.UNAVAILABLE.withDescription("Invalid protobuf byte sequence")
          .withCause(e).asRuntimeException();
    }
//    M message = new M(outputStream.toByteArray());
    return (M) new ByteArrayMessage(outputStream.toByteArray());
  }
}
