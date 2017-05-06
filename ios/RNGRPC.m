//
//  RCTGrpcModule.m
//  react-native-grpc
//
//  Created by RikuAyanokoji on 01/12/2016.
//  Copyright © 2016 Rikua. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <RealReachability/RealReachability.h>
#import <ProtoRPC/ProtoRPC.h>
#import <RxLibrary/GRXWriteable.h>
#import <RxLibrary/GRXWriter.h>
#import <GRPCClient/GRPCCall.h>
#import <GRPCClient/GRPCCall+ChannelArg.h>
#import <GRPCClient/GRPCCall+Tests.h>
#import <React/RCTBridge.h>
#import <React/RCTEventEmitter.h>
#import "RCTGrpcModule.h"

@interface RCTGRPCWriter : GRXWriter
@end

@implementation RCTGRPCWriter {
  id<GRXWriteable> _writeable;
  GRXWriterState _state;
  GRPCCall * _call;
}

#pragma mark GRXWriteable implementation

- (void)writeValue:(id)value {
  [_writeable writeValue:value];
}

- (void)writesFinishedWithError:(NSError *)errorOrNil {
  //  _writer = nil;
}

#pragma mark GRXWriter implementation

//- (GRXWriterState)state {
//  return _writer ? _writer.state : GRXWriterStateFinished;
//}

- (void)setState:(GRXWriterState)state {
  if (state == GRXWriterStateFinished) {
    _writeable = nil;
  }
  _state = state;
}

- (void) setCall:(GRPCCall *) call {
  _call = call;
}

- (void) setTimeout:(NSNumber *)timeout {
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)([timeout integerValue] * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    if (_state == GRXWriterStateStarted) {
      [_call cancel];
    }
  });
}

- (void)startWithWriteable:(id<GRXWriteable>)writeable {
  _writeable = writeable;
}
@end

@interface RCTGRPCCall : NSObject
@property (nonatomic, weak) GRPCProtoCall * call;
@property (nonatomic, weak) RCTGRPCWriter * writer;
@end

@implementation RCTGRPCCall

- (instancetype) initWithCall:(GRPCProtoCall *)call writer:(RCTGRPCWriter *)writer {
  if (self = [super init]) {
    self.call = call;
    self.writer = writer;
  }
  return self;
}

@end

@interface GRPCCall ()
- (void)cancelCall;
@end

@implementation RCTGRPCModule {
  NSMutableDictionary<NSNumber *, RCTGRPCCall *> * _calls;
  NSDictionary<NSString *, NSString *> * _defaultHeaders;
}

- (id) init {
  if (self = [super init]) {
    _calls = [[NSMutableDictionary alloc] init];
    [GLobalRealReachability startNotifier];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(networkChange:) name:kRealReachabilityChangedNotification object:nil];
  }
  return self;
}

- (void) closeOpenConnections {
  @synchronized (_calls) {
    // [GRPCCall closeOpenConnections] don't calcel executing calls, so manually cancel it here
    for (RCTGRPCCall* call in [_calls allValues]) {
      [call.call finishWithError:[NSError errorWithDomain:kGRPCErrorDomain
                                                     code:GRPCErrorCodeUnavailable
                                                 userInfo:@{NSLocalizedDescriptionKey: @"Network changed"}]];
      [call.call cancelCall];
    }
    [GRPCCall closeOpenConnections];
  }
}

RCT_EXPORT_MODULE()

- (NSArray<NSString *> *) supportedEvents {
  return @[@"didReceiveResponse", @"didCompleteCall"];
};

RCT_EXPORT_METHOD(setDefaultHeaders:(NSDictionary *)headers) {
  _defaultHeaders = headers;
}

RCT_EXPORT_METHOD(start:(NSString *)host
                  type:(NSString *)type
                  path:(NSString *)path
                  reactTag:(NSNumber * _Nonnull)rpcId
                  settings:(NSDictionary *)settings) {
  BOOL insecure = settings[@"insecure"];
  if (insecure) {
    [GRPCCall useInsecureConnectionsForHost:host];
  }
  RCTGRPCWriter* writer = [RCTGRPCWriter alloc];
  GRPCProtoCall* call = [[GRPCCall alloc] initWithHost:host path:path requestsWriter:writer];
  if (settings[@"headers"] != nil) {
    for (NSString* key in settings[@"headers"]) {
      call.requestHeaders[key] = settings[@"headers"];
    }
  }
  if (_defaultHeaders != nil) {
    for (NSString* key in _defaultHeaders) {
      call.requestHeaders[key] = _defaultHeaders[key];
    }
  }
  GRXWriteable* writeable = [[GRXWriteable alloc] initWithValueHandler:^(NSData *value) {
    [self sendEventWithName:@"didReceiveResponse" body:@{@"rpcId": rpcId,
                                                         @"data": [value base64EncodedStringWithOptions:0] }];
  } completionHandler:^(NSError *errorOrNil) {
    if (errorOrNil != nil) {
      [self sendEventWithName:@"didCompleteCall" body:@{@"rpcId": rpcId,
                                                        @"error": @{@"code": @([errorOrNil code]),
                                                                    @"domain": [errorOrNil domain],
                                                                    @"message": [errorOrNil description]} }];
      return;
    }
    [self sendEventWithName:@"didCompleteCall" body:@{@"rpcId": rpcId}];
    [_calls removeObjectForKey:rpcId];
  }];
  [call startWithWriteable:writeable];
  _calls[rpcId] = [[RCTGRPCCall alloc] initWithCall:call writer:writer];
}

RCT_EXPORT_METHOD(writeB64data:(NSString *)b64data
                  reactTag:(NSNumber * _Nonnull)rpcId) {
  RCTGRPCCall * call = _calls[rpcId];
  if (!call) {
    return;
  }
  NSData * data = [[NSData alloc] initWithBase64EncodedString:b64data options:0];;
  [call.writer writeValue:data];
}

- (void)networkChange:(NSNotification *)notification {
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_BACKGROUND, 0), ^() {
    ReachabilityStatus status = [GLobalRealReachability currentReachabilityStatus];
    [self closeOpenConnections];
    switch (status) {
      case RealStatusNotReachable:
        NSLog(@"Reachability: NotReachable");
        return;
      case RealStatusViaWiFi:
        NSLog(@"Reachability: WiFi");
        break;
      case RealStatusViaWWAN:
        NSLog(@"Reachability: WWAN");
        break;
    }
  });
}

@end
