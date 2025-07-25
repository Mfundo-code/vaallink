import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createStackNavigator } from '@react-navigation/stack';
import HomeScreen from './screens/HomeScreen';
import HostScreen from './screens/HostScreen';
import ClientScreen from './screens/ClientScreen';

const Stack = createStackNavigator();

export default function App() {
  return (
    <NavigationContainer>
      <Stack.Navigator screenOptions={{ headerShown: false }}>
        <Stack.Screen name="Home" component={HomeScreen} />
        <Stack.Screen name="Host" component={HostScreen} />
        <Stack.Screen name="Client" component={ClientScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}