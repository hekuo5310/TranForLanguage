// greeter asks the Go and Python programs for data and reacts to purchases they publish.
const t = require('tranforlanguage');

t.on('PlayerJoin', async (player) => {
  // Give economy a moment to see the same join event before asking for the balance.
  await new Promise((resolve) => setTimeout(resolve, 200));
  try {
    const [coins, apple] = await Promise.all([t.call('economy', 'balance', player), t.call('shop', 'price', 'apple')]);
    t.sendMessage(player, `You have ${coins} coins; an apple costs ${apple}. Type !buy apple`);
  } catch (error) {
    t.console(`greeter: ${error.message}`);
  }
});

t.subscribe('purchase', (from, player, item) => t.broadcast(`${player} bought ${item} (via ${from})`));

t.run();
