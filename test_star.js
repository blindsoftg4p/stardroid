const WebSocket = require('ws');

const ws = new WebSocket('wss://star.blindsoft.net:7774', { rejectUnauthorized: false });

ws.on('open', function open() {
  console.log('Connected!');
  const handshake = { user: 4 };
  console.log('Sending handshake:', JSON.stringify(handshake));
  ws.send(JSON.stringify(handshake));
  
  setTimeout(() => {
    const ttsReq = { voice: 'tom', text: 'Hello world', id: '12345' };
    console.log('Sending TTS request:', JSON.stringify(ttsReq));
    ws.send(JSON.stringify(ttsReq));
  }, 2000);
});

ws.on('message', function incoming(data) {
  if (Buffer.isBuffer(data)) {
    console.log('Received binary data of length:', data.length);
    if (data.length > 2) {
      const idLength = data[0] | (data[1] << 8);
      console.log('ID length:', idLength);
      if (data.length >= 2 + idLength) {
          console.log('ID:', data.toString('utf8', 2, 2 + idLength));
      }
      if (data.length > 100) {
        console.log('Starts with:', data.slice(2 + idLength, 2 + idLength + 16).toString());
      }
    }
  } else {
    console.log('Received text message:', data.toString());
  }
});

ws.on('error', function error(err) {
  console.error('WebSocket Error:', err);
});

ws.on('close', function close() {
  console.log('Disconnected');
});
