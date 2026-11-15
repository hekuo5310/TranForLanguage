const { on, run, sendMessage } = require('tranforlanguage');

on('PlayerJoin', (player) => {
  sendMessage(player, 'Welcome from JavaScript!');
});

run();
