import React, { useState, useEffect } from 'react';
import { View, Text, TextInput, StyleSheet } from 'react-native';
import Button from '../components/Button';
import { joinSession } from '../services/api';
import {
  startVpnService,
  stopVpnService,
  listenForVpnStatus,
  testInternet
} from '../services/vpnService';

const ClientScreen = ({ navigation }) => {
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [vpnActive, setVpnActive] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('disconnected');
  const [sessionInfo, setSessionInfo] = useState(null);

  useEffect(() => {
    const unsubscribe = listenForVpnStatus(active => {
      setVpnActive(active);
      setConnectionStatus(active ? 'connected' : 'disconnected');
    });
    return unsubscribe;
  }, []);

  const handleConnect = async () => {
    if (code.length !== 6) {
      setError('Code must be 6 characters');
      return;
    }
    setLoading(true);
    setError('');
    setStatus('Connecting...');
    setConnectionStatus('connecting');
    try {
      const deviceId = Math.random().toString(36).substring(2, 10).toUpperCase();
      const response = await joinSession(code.toUpperCase(), deviceId);
      if (response.status !== 'success') throw new Error('Invalid session code or session expired');
      setSessionInfo(response);
      setStatus('Configuring VPN...');
      setConnectionStatus('configuring');
      await startVpnService({
        role: 'client',
        relayServer: response.relay_server,
        udpPort: response.udp_port,
        tcpPort: response.tcp_port,
        sessionCode: code.toUpperCase()
      });
      setStatus('Verifying connection...');
      let connected = false;
      for (let i = 0; i < 5; i++) {
        if (await testInternet()) {
          connected = true;
          break;
        }
        await new Promise(resolve => setTimeout(resolve, 2000));
      }
      if (connected) {
        setStatus('Connected successfully!');
        setConnectionStatus('connected');
      } else {
        throw new Error('No internet access through VPN');
      }
    } catch (err) {
      console.error('Connection error:', err);
      setError(err.message);
      setStatus('Connection failed');
      setConnectionStatus('error');
      try {
        await stopVpnService();
      } catch {}
    } finally {
      setLoading(false);
    }
  };

  const handleDisconnect = async () => {
    try {
      setStatus('Disconnecting...');
      setConnectionStatus('disconnecting');
      await stopVpnService();
      navigation.goBack();
    } catch (err) {
      console.error('Disconnection error:', err);
      setError('Failed to disconnect: ' + err.message);
      setConnectionStatus('error');
    }
  };

  const getStatusText = () => {
    switch (connectionStatus) {
      case 'connecting': return 'Connecting to host...';
      case 'configuring': return 'Configuring network...';
      case 'connected': return 'Connected! Internet shared';
      case 'disconnecting': return 'Disconnecting...';
      case 'error': return 'Connection error';
      default: return 'Enter connection code';
    }
  };

  const getStatusColor = () => {
    switch (connectionStatus) {
      case 'connected': return '#4CAF50';
      case 'error': return '#F44336';
      case 'connecting':
      case 'configuring':
      case 'disconnecting': return '#FF9800';
      default: return '#9E9E9E';
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Connect to Internet</Text>
      <View style={styles.statusContainer}>
        <View style={[styles.statusIndicator, { backgroundColor: getStatusColor() }]} />
        <Text style={styles.statusText}>{getStatusText()}</Text>
      </View>
      {!vpnActive && (
        <>
          <Text style={styles.label}>Enter connection code:</Text>
          <TextInput
            style={styles.input}
            placeholder="e.g. A1B2C3"
            placeholderTextColor="#999"
            value={code}
            onChangeText={text => setCode(text.replace(/[^a-zA-Z0-9]/g, '').toUpperCase())}
            maxLength={6}
            autoCapitalize="characters"
            editable={!loading}
            autoFocus
          />
        </>
      )}
      {error ? <Text style={styles.error}>{error}</Text> : null}
      {status ? <Text style={styles.status}>{status}</Text> : null}
      {sessionInfo && vpnActive && (
        <View style={styles.sessionInfo}>
          <Text style={styles.infoText}>Session: {sessionInfo.code}</Text>
          <Text style={styles.infoText}>Relay: {sessionInfo.relay_server}</Text>
        </View>
      )}
      <View style={styles.buttonContainer}>
        {!vpnActive ? (
          <Button
            title={loading ? "CONNECTING..." : "CONNECT"}
            onPress={handleConnect}
            disabled={loading || code.length !== 6}
            style={styles.button}
            color={loading || code.length !== 6 ? "#BDBDBD" : "#4CAF50"}
            loading={loading}
          />
        ) : (
          <Button
            title="DISCONNECT"
            onPress={handleDisconnect}
            style={styles.button}
            color="#F44336"
          />
        )}
      </View>
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
    marginBottom: 20,
    textAlign: 'center',
    color: '#2C3E50',
  },
  statusContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 25,
    paddingVertical: 10,
    backgroundColor: '#F5F7FA',
    borderRadius: 8,
  },
  statusIndicator: {
    width: 12,
    height: 12,
    borderRadius: 6,
    marginRight: 10,
  },
  statusText: {
    fontSize: 16,
    fontWeight: '500',
    color: '#555555',
  },
  label: {
    fontSize: 16,
    marginBottom: 10,
    color: '#555555',
  },
  input: {
    height: 50,
    borderWidth: 1,
    borderColor: '#DDDDDD',
    borderRadius: 8,
    paddingHorizontal: 15,
    fontSize: 18,
    marginBottom: 20,
    backgroundColor: '#FFFFFF',
    color: '#333333',
  },
  error: {
    color: '#F44336',
    marginBottom: 20,
    textAlign: 'center',
    fontWeight: '500',
  },
  status: {
    color: '#2196F3',
    marginBottom: 20,
    textAlign: 'center',
  },
  buttonContainer: {
    marginTop: 10,
    marginBottom: 20,
  },
  button: {
    marginVertical: 5,
  },
  sessionInfo: {
    marginBottom: 20,
    padding: 15,
    backgroundColor: '#E8F5E9',
    borderRadius: 8,
    borderLeftWidth: 4,
    borderLeftColor: '#4CAF50',
  },
  infoText: {
    fontSize: 14,
    color: '#2E7D32',
    marginBottom: 5,
  },
});

export default ClientScreen;
