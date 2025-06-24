import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import Button from '../components/Button';

const HomeScreen = ({ navigation }) => {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>VaalLink</Text>
      <Text style={styles.subtitle}>One Internet. Many Connections.</Text>
      
      <Button 
        title="SHARE MY INTERNET"
        onPress={() => navigation.navigate('Host')}
        style={styles.button}
        color="#4CAF50"
      />
      
      <Button
        title="USE SOMEONE'S INTERNET"
        onPress={() => navigation.navigate('Client')}
        style={styles.button}
        color="#2196F3"
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#F5F7FA',
    padding: 20,
  },
  title: {
    fontSize: 36,
    fontWeight: 'bold',
    color: '#2C3E50',
    marginBottom: 10,
  },
  subtitle: {
    fontSize: 18,
    color: '#7F8C8D',
    marginBottom: 40,
  },
  button: {
    marginVertical: 10,
    width: '80%',
  }
});

export default HomeScreen;