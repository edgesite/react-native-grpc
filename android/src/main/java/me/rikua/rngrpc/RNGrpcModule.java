package me.rikua.rngrpc;

import android.os.Build;
import android.util.Base64;
import android.util.SparseArray;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.squareup.okhttp.ConnectionSpec;
import com.squareup.okhttp.TlsVersion;

import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.DnsNameResolverProvider;
import io.grpc.okhttp.NegotiationType;
import io.grpc.okhttp.OkHttpChannelBuilder;

@ReactModule(name = "GRPCModule")
public class RNGrpcModule extends ReactContextBaseJavaModule {
  private final ReactApplicationContext mContext;
  private final HashMap<String, Channel> mChannels;
  private final HashMap<String, MethodDescriptor> mMethodDescriptors;
  private final SparseArray<ClientCall> mCalls;
  private final Object mCallsLock = new Object();
  private final Object mChannelsLock = new Object();
  private DeviceEventManagerModule.RCTDeviceEventEmitter mEmitter;

  public RNGrpcModule(ReactApplicationContext context) {
    super(context);
    mContext = context;
    mChannels = new HashMap<>();
    mCalls = new SparseArray<>();
    mMethodDescriptors = new HashMap<>();
  }

  private void emit(String event, WritableMap data) {
    if (mEmitter == null) mEmitter = mContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
    mEmitter.emit(event, data);
  }

  private WritableNativeMap getMap(int rpcId) {
    WritableNativeMap map = new WritableNativeMap();
    map.putInt("rpcId", rpcId);
    return map;
  }

  @Override
  public String getName() {
    return "GRPCModule";
  }

  @ReactMethod
  public void start(String addr, final String typeName, String path, final int rpcId, ReadableMap settings, Promise promise) {
    Channel channel = getChannel(addr);

    final MethodDescriptor.MethodType type;
    try {
      type = MethodDescriptor.MethodType.valueOf(typeName);
    } catch(IllegalArgumentException e) {
      promise.reject(e);
      return;
    }

    CallOptions options = CallOptions.DEFAULT;
    if (settings.hasKey("timeout")) {
      options = options.withDeadline(Deadline.after(settings.getInt("timeout"), TimeUnit.MILLISECONDS))
    }

    final ClientCall call = channel.newCall(getMethodDescriptor(type, path), options);
    call.start(new ClientCall.Listener() {
      @Override
      public void onMessage(Object message) {
        super.onMessage(message);
        if (type != MethodDescriptor.MethodType.UNARY) {
          call.request(1);
        }
        WritableNativeMap map = getMap(rpcId);
        map.putString("b64data", Base64.encodeToString(((ByteArrayMessage) message).bytes, 0));
        emit("didReceiveResponse", map);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        super.onClose(status, trailers);
        WritableNativeMap map = getMap(rpcId);
        if (!status.isOk()) {
          WritableNativeMap errorInfo = new WritableNativeMap();
          errorInfo.putString("code", status.getCode().name());
          errorInfo.putString("message", status.getDescription());

          map.putMap("error", errorInfo);
          if (trailers != null) {
            map.putMap("trailers", toHeaders(trailers));
          }
        }
        emit("didCompleteCall", map);
      }

      @Override
      public void onReady() {
        super.onReady();
        emit("didReceiveReady", getMap(rpcId));
      }

      @Override
      public void onHeaders(Metadata headers) {
        super.onHeaders(headers);
        WritableNativeMap map = getMap(rpcId);
        if (headers != null) {
          map.putMap("headers", toHeaders(headers));
        }
        emit("didReceiveHeaders", map);
      }
    }, getMetadata(settings));

    synchronized (mCallsLock) {
      mCalls.put(rpcId, call);
    }
    WritableNativeMap map = new WritableNativeMap();
    map.putInt("rpcId", rpcId);
    promise.resolve(map);
  }

  private ReadableMap mDefaultHeaders;

  @ReactMethod
  public void setDefaultHeaders(ReadableMap headers) {
    mDefaultHeaders = headers;
  }

  @ReactMethod
  public void halfClose(int rpcId, Promise promise) {
    ClientCall call = mCalls.get(rpcId);
    if (call == null) {
      promise.reject(new RuntimeException(String.format(Locale.getDefault(), "call %d not found", rpcId)));
      return;
    }
    call.halfClose();
  }

  @ReactMethod
  public void writeB64data(String b64data, int rpcId, Promise promise) {
    ClientCall call = mCalls.get(rpcId);
    if (call == null) {
      promise.reject(new RuntimeException(String.format(Locale.getDefault(), "call %d not found", rpcId)));
      return;
    }
    byte[] data = Base64.decode(b64data, 0);
    call.sendMessage(new ByteArrayMessage(data));
    call.request(1);
    call.halfClose();
  }

  private Metadata getMetadata(ReadableMap settings) {
    Metadata md = new Metadata();
    if (settings.hasKey("headers")) {
      ReadableMap mdMap = settings.getMap("headers");
      ReadableMapKeySetIterator iterator = mdMap.keySetIterator();
      while (iterator.hasNextKey()) {
        String key = iterator.nextKey();
        md.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), mdMap.getString(key));
      }
    }
    if (mDefaultHeaders != null) {
      ReadableMapKeySetIterator iterator = mDefaultHeaders.keySetIterator();
      while (iterator.hasNextKey()) {
        String key = iterator.nextKey();
        md.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), mDefaultHeaders.getString(key));
      }
    }
    return md;
  }

  private WritableMap toHeaders(Metadata metadata) {
    WritableMap map = new WritableNativeMap();
    for (String key : metadata.keys()) {
      if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        Metadata.Key<byte[]> headerKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
        map.putString(key, Base64.encodeToString(metadata.get(headerKey), 0));
      } else {
        Metadata.Key<String> headerKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
        map.putString(key, metadata.get(headerKey));
      }
    }
    return map;
  }

  public Channel getChannel(String addr) {
    Channel channel = null;
    if (!mChannels.containsKey(addr)) {
      synchronized (mChannelsLock) {
        if (!mChannels.containsKey(addr)) {
          channel = buildChannel(addr);
          mChannels.put(addr, channel);
        }
      }
    }
    if (channel == null) {
      channel = mChannels.get(addr);
    }
    return channel;
  }

  private Channel buildChannel(String addr) {
    String[] segs = addr.split(":");
    if (segs.length > 2) {
      throw new RuntimeException("invalid address: expect host[:port], got " + addr);
    }
    String host = segs[0];
    int port = segs.length == 2 ? Integer.parseInt(segs[1], 10) : 443;
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress(host, port);
    builder.nameResolverFactory(new DnsNameResolverProvider());
//    builder.nameResolverFactory(new ResolverFactory());
//    builder.overrideAuthority()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
      try {
        builder.sslSocketFactory(new com.weilutv.oliver.security.TLSSocketFactory());
//        builder.negotiationType(NegotiationType.TLS);
        builder.connectionSpec(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .supportsTlsExtensions(true)
            .build());
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return builder.build();
  }

  private synchronized MethodDescriptor getMethodDescriptor(MethodDescriptor.MethodType type, String path) {
    String key = type.name() + path;
    MethodDescriptor descriptor;
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (mMethodDescriptors.containsKey(key)) {
      descriptor = mMethodDescriptors.get(key);
    } else {
      descriptor = io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          path,
          ByteArrayMarshaller.getInstance(),
          ByteArrayMarshaller.getInstance());
      mMethodDescriptors.put(key, descriptor);
    }
    return descriptor;
  }

}