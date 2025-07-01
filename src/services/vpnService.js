import { NativeModules, NativeEventEmitter } from 'react-native';
const { VpnService } = NativeModules;

const vpnEmitter = new NativeEventEmitter(VpnService);

export const prepareVpnService = async () => {
  try {
    const result = await VpnService.prepareVpn();
    return result;
  } catch (error) {
    console.error('VPN prepare error:', error);
    throw new Error('Failed to prepare VPN service');
  }
};

export const startVpnService = async (config) => {
  try {
    await prepareVpnService();
    
    const timeoutPromise = new Promise((_, reject) => 
      setTimeout(() => reject(new Error('VPN start timed out')), 15000)
    );
    
    await Promise.race([
      VpnService.startVpn({
        ...config,
        udpPort: 52000,
        tcpPort: 52001
      }),
      timeoutPromise
    ]);
    
    return true;
  } catch (error) {
    console.error('VPN start error:', error);
    throw new Error('Failed to start VPN: ' + error.message);
  }
};

export const stopVpnService = async () => {
  try {
    const success = await VpnService.stopVpn();
    if (!success) {
      throw new Error('VPN service was not running');
    }
    return true;
  } catch (error) {
    console.error('VPN stop error:', error);
    throw new Error('Failed to stop VPN service');
  }
};

export const getVpnStatus = async () => {
  try {
    const status = await VpnService.getStatus();
    return status;
  } catch (error) {
    console.error('VPN status error:', error);
    return { active: false, connected: false };
  }
};

export const listenForVpnStatus = (callback) => {
  const subscription = vpnEmitter.addListener('VpnStatus', (status) => {
    callback(status.active && status.connected);
  });
  
  const interval = setInterval(async () => {
    try {
      const status = await getVpnStatus();
      callback(status.active && status.connected);
    } catch (e) {
      callback(false);
    }
  }, 5000);

  return () => {
    subscription.remove();
    clearInterval(interval);
  };
};

export const testInternet = async () => {
  try {
    const response = await fetch('http://clients3.google.com/generate_204');
    return response.status === 204;
  } catch (error) {
    return false;
  }
};