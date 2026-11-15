const t = require('tranforlanguage');
const me = t.programId;
t.handle('echo', (value = '') => me + ':' + value);
t.handle('bounce', (target, value) => t.call(target, 'echo', value));
t.on('Ping', async (source, target) => { if (source === me) t.broadcast(me + '->' + await t.call(target, 'echo', me)); });
t.on('Nested', async (source, via) => { if (source === me) t.broadcast('nested ' + me + '=' + await t.call(via, 'bounce', me, me)); });
t.on('Missing', async (source) => {
  if (source !== me) return;
  try { await t.call('nobody', 'echo'); } catch (error) { t.broadcast('missing ' + me + ':' + error.message); }
});
t.on('Announce', (source) => { if (source === me) t.publish('greet', 'hi'); });
t.subscribe('greet', (sender, text) => t.broadcast('greet ' + me + ' from ' + sender + ': ' + text));
t.run();
