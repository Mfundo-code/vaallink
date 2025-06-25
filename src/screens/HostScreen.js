import React, { useState, useEffect, useRef } from 'react';
import { View, Text, StyleSheet, Share, ActivityIndicator } from 'react-native';
import Button from '../components/Button';
import { createSession, cancelSession } from '../services/api';
import { 
  prepareVpnService,
  startVpnService, 
  stopVpnService, 
  listenForVpnStatus
} from '../services/vpnService';
import axios from 'axios';

const TEST_URL = 'https://www.google.com';

const HostScreen = ({ navigation }) => {
  const [sessionInfo, setSessionInfo] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [vpnActive, setVpnActive] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [statusMessage, setStatusMessage] = useState('Initializing...');
  
  const sessionInfoRef = useRef(null);
  const stopAttempted = useRef(false);
  const vpnSubscriptionRef = useRef(null);

  const testInternet = async () => {
    try {
      const response = await axios.get(TEST_URL, {
        timeout: 5000,
        headers: { 'Cache-Control': 'no-cache' }
      });
      return response.status === 200;
    } catch (error) {
      return false;
    }
  };

  useEffect(() => {
    const initSession = async () => {
      try {
        setStatusMessage('Checking permissions...');
        await prepareVpnService();
        
        setStatusMessage('Creating session...');
        const deviceId = Math.random().toString(36).substring(2, 10).toUpperCase();
        const response = await createSession(deviceId);
        
        setSessionInfo(response);
        sessionInfoRef.current = response;
        
        setStatusMessage('Configuring network...');
        await startVpnService({
          role: 'host',
          relayServer: response.relay_server,
          udpPort: response.udp_port,
          tcpPort: response.tcp_port,
          sessionCode: response.code
        });
        
        setStatusMessage('Verifying connection...');
        
        // Verify internet connection
        let connected = false;
        for (let i = 0; i < 5; i++) {
          if (await testInternet()) {
            connected = true;
            break;
          }
          await new Promise(resolve => setTimeout(resolve, 2000));
        }
        
        if (!connected) {
          throw new Error('Cannot reach internet after VPN activation');
        }
        
        setLoading(false);
      } catch (err) {
        console.error('Setup error:', err);
        setError('Setup failed: ' + err.message);
        setLoading(false);
        
        try {
          await stopVpnService();
          if (sessionInfoRef.current?.code) {
            await cancelSession(sessionInfoRef.current.code);
          }
        } catch (cleanupErr) {
          console.log('Cleanup error:', cleanupErr);
        }
      }
    };

    initSession();
    
    vpnSubscriptionRef.current = listenForVpnStatus((active) => {
      setVpnActive(active);
    });
    
    return () => {
      if (vpnSubscriptionRef.current) {
        vpnSubscriptionRef.current();
      }
    };
  }, []);

  const handleStopSharing = async () => {
    if (stopAttempted.current) return;
    stopAttempted.current = true;
    setStopping(true);
    
    try {
      await stopVpnService();
      
      if (sessionInfoRef.current?.code) {
        try {
          await cancelSession(sessionInfoRef.current.code);
        } catch (apiErr) {
          console.log('API cancel error:', apiErr);
        }
      }
      
      navigation.goBack();
    } catch (err) {
      console.error('Stop sharing error:', err);
      setError('Failed to stop sharing: ' + err.message);
      setStopping(false);
      stopAttempted.current = false;
    }
  };

  const shareCode = async () => {
    try {
      if (sessionInfo?.code) {
        await Share.share({
          message: `Connect to my internet! VaalLink code: ${sessionInfo.code}`,
        });
      } else {
        setError('No session code available to share.');
      }
    } catch (err) {
      setError('Sharing failed. Please copy manually.');
    }
  };

  if (loading) {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" color="#4CAF50" />
        <Text style={styles.loadingText}>{statusMessage}</Text>
        {error ? <Text style={styles.error}>{error}</Text> : null}
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Share Your Internet</Text>
      
      {error ? <Text style={styles.error}>{error}</Text> : null}
      
      <View style={styles.codeContainer}>
        <Text style={styles.codeLabel}>Connection Code:</Text>
        <Text style={styles.code}>{sessionInfo?.code || '—'}</Text>
      </View>
      
      <Button 
        title="SHARE CODE" 
        onPress={shareCode}
        style={styles.button}
        color="#FF9800"
        disabled={stopping}
      />
      
      <View style={styles.statusContainer}>
        <View style={[styles.statusIndicator, vpnActive && styles.activeIndicator]} />
        <Text style={styles.statusText}>
          {vpnActive ? 'VPN Active' : 'VPN Inactive'}
        </Text>
      </View>
      
      <Button 
        title={stopping ? "STOPPING..." : "STOP SHARING"} 
        onPress={handleStopSharing}
        style={styles.button}
        color="#F44336"
        loading={stopping}
        disabled={stopping}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    backgroundColor: '#FFFFFF',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 30,
    textAlign: 'center',
  },
  error: {
    color: '#F44336',
    textAlign: 'center',
    marginBottom: 20,
  },
  codeContainer: {
    alignItems: 'center',
    marginBottom: 30,
  },
  codeLabel: {
    fontSize: 18,
    marginBottom: 10,
    color: '#555555',
  },
  code: {
    fontSize: 36,
    fontWeight: 'bold',
    color: '#3F51B5',
  },
  button: {
    marginBottom: 20,
  },
  statusContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 30,
  },
  statusIndicator: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: '#F44336',
    marginRight: 10,
  },
  activeIndicator: {
    backgroundColor: '#4CAF50',
  },
  statusText: {
    fontSize: 16,
    color: '#555555',
  },
  loadingText: {
    marginTop: 20,
    fontSize: 16,
    color: '#555555',
  }
});

export default HostScreen;
