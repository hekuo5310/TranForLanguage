const { on, run, sendMessage } = require('tranforcpp');

on('PlayerJoin', (player) => {
  sendMessage(player, 'Welcome from JavaScript!');
});

run();
