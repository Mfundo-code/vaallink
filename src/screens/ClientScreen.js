import React, { useState } from 'react';
import { View, Text, TextInput, StyleSheet, ActivityIndicator } from 'react-native';
import Button from '../components/Button';
import { joinSession } from '../services/api';
import { startVpnService, stopVpnService, addVpnStatusListener } from '../services/vpnService';

const ClientScreen = ({ navigation }) => {
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [vpnActive, setVpnActive] = useState(false);

  useEffect(() => {
    // Add VPN status listener
    const unsubscribe = addVpnStatusListener((active) => {
      setVpnActive(active);
    });
    
    return () => {
      unsubscribe();
    };
  }, []);

  const handleConnect = async () => {
    if (code.length !== 6) {
      setError('Code must be 6 characters');
      return;
    }
    
    setLoading(true);
    setError('');
    setStatus('Connecting...');
    
    try {
      // Generate random device ID
      const deviceId = Math.random().toString(36).substring(2, 10).toUpperCase();
      const response = await joinSession(code.toUpperCase(), deviceId);
      
      if (response.status !== 'success') {
        throw new Error('Invalid session code');
      }
      
      setStatus('Configuring VPN...');
      
      // Start VPN service
      await startVpnService({
        role: 'client',
        relayServer: response.relay_server,
        udpPort: response.udp_port,
        tcpPort: response.tcp_port,
        sessionCode: code.toUpperCase()
      });
      
      setStatus('Connected successfully!');
    } catch (err) {
      setError('Connection failed: ' + err.message);
      setStatus('');
    } finally {
      setLoading(false);
    }
  };

  const handleDisconnect = async () => {
    try {
      await stopVpnService();
      navigation.goBack();
    } catch (err) {
      setError('Failed to disconnect: ' + err.message);
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Connect to Internet</Text>
      
      <Text style={styles.label}>Enter connection code:</Text>
      <TextInput
        style={styles.input}
        placeholder="e.g. A1B2C3"
        value={code}
        onChangeText={text => setCode(text.toUpperCase())}
        maxLength={6}
        autoCapitalize="characters"
        editable={!loading && !vpnActive}
      />
      
      {error ? <Text style={styles.error}>{error}</Text> : null}
      {status ? <Text style={styles.status}>{status}</Text> : null}
      
      {!vpnActive ? (
        <Button
          title={loading ? "CONNECTING..." : "CONNECT"}
          onPress={handleConnect}
          disabled={loading}
          style={styles.button}
          color={loading ? "#9E9E9E" : "#4CAF50"}
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
  },
  error: {
    color: '#F44336',
    marginBottom: 20,
    textAlign: 'center',
  },
  status: {
    color: '#2196F3',
    marginBottom: 20,
    textAlign: 'center',
  },
  button: {
    marginTop: 10,
  }
});

export default ClientScreen;