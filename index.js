import {
  NativeModules,
  NativeEventEmitter,
} from 'react-native';
import base64 from 'base64-js';

const RCTGRPCModule = NativeModules.GRPCModule || NativeModules.RNGRPCModule;

const calls = {};

class GRPCCall {
  constructor(addr, type, path, rpcId, respClass) {
    this.nextSubscribers = [];
    this.errorSubscribers = [];
    this.completeSubscribers = [];
    this.respClass = respClass;
    this.addr = addr;
    this.type = type;
    this.path = path;
    this.rpcId = rpcId;
  }

  start() {
    const { addr, type, path, rpcId } = this;
    calls[rpcId] = this;
    RCTGRPCModule.start(addr, type, path, rpcId, { insecure: false });
  }

  subscribe(next, error, complete) {
    if (next) {
      this.nextSubscribers.push(next);
    }
    if (error) {
      this.errorSubscribers.push(error);
    }
    if (complete) {
      this.completeSubscribers.push(complete);
    }
  }

  write(data) {
    if (data.serializeMessage) {
      data = data.serializeMessage();
    }
    RCTGRPCModule.writeB64data(base64.fromByteArray(data), this.rpcId);
  }

  next(data) {
    data = this.respClass.deserializeBinary(data);
    this.nextSubscribers.forEach(s => s(data));
  }

  complete(error) {
    delete calls[this.rpcId];
    if (error) {
      try {
        this.errorSubscribers.forEach(s => s(error));
      } catch (e) {
      }
    }
    this.completeSubscribers.forEach(s => s());
  }
}

let rpcId = 0;

function invokeCall(addr, path, resp) {
  const call = new GRPCCall(addr, "UNARY", path, rpcId++, resp);
  call.start();
  return call;
}

const ee = new NativeEventEmitter(RCTGRPCModule);
ee.addListener('didReceiveResponse', event => {
  const { rpcId, b64data, data } = event;
  const call = calls[rpcId];
  if (!call) {
    console.warn(`call ${rpcId} not found.`);
    return;
  }
  if (b64data) {
    call.next(base64.toByteArray(b64data.replace(/\n/g, '').trim()));
  }
  call.next(data);
});
ee.addListener('didCompleteCall', event => {
  const { rpcId } = event;
  const call = calls[rpcId];
  if (!call) {
    console.warn(`call ${rpcId} not found.`);
    return;
  }
  call.complete(event.error);
});

export {
  invokeCall,
};
