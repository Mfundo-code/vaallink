import axios from 'axios';

const API_URL = 'http://164.68.125.31/api/';

export const createSession = async (deviceId) => {
  try {
    const response = await axios.post(API_URL + 'create/', {
      device_id: deviceId
    });
    return response.data;
  } catch (error) {
    console.error('API Error (createSession):', error);
    throw new Error('Failed to create session');
  }
};

export const joinSession = async (code, deviceId) => {
  try {
    const response = await axios.post(API_URL + 'join/', {
      code: code,
      device_id: deviceId
    });
    return response.data;
  } catch (error) {
    console.error('API Error (joinSession):', error);
    throw new Error('Failed to join session');
  }
};

// Updated with timeout and error handling
export const cancelSession = async (code) => {
  try {
    const response = await axios.post(API_URL + 'cancel/', {
      code: code
    }, {
      timeout: 5000 // 5-second timeout
    });
    return response.data;
  } catch (error) {
    console.error('API Error (cancelSession):', error);
    throw new Error('Failed to cancel session: ' + error.message);
  }
};